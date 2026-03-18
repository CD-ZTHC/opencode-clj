(ns anima-agent-clj.channel.adapter
  "Channel Adapter for integrating the Agent Cluster with channels.

   The ChannelAdapter bridges messaging channels (CLI, RabbitMQ, etc.)
   with the new Agent Cluster architecture:
   - CoreAgent: Reasoning and orchestration
   - WorkerPool: Task execution
   - ParallelPool: Concurrent processing
   - SpecialistPool: Domain-specific routing

   Architecture:
   ┌─────────────────────────────────────────────────────────────┐
   │                    ChannelAdapter                           │
   │                                                              │
   │  Channels ────→ Inbound Router ────→ Agent Cluster          │
   │    │               │                    │                    │
   │    │               ├─→ CoreAgent ───────┤                    │
   │    │               ├─→ SpecialistPool ──┤                    │
   │    │               └─→ ParallelPool ────┤                    │
   │    │                                     │                    │
   │    └────────────← Outbound Router ←──────┘                   │
   │                                                              │
   └─────────────────────────────────────────────────────────────┘

   Usage:
   (def adapter (create-adapter {:bus bus :core-agent agent}))
   (start-adapter! adapter)
   ;; Adapter now routes messages between channels and agent cluster"
  (:require
   [clojure.core.async :as async]
   [anima-agent-clj.channel :as ch]
   [anima-agent-clj.channel.registry :as registry]
   [anima-agent-clj.channel.dispatch :as dispatch]
   [anima-agent-clj.bus :as bus]
   [anima-agent-clj.agent.core-agent :as core-agent]
   [anima-agent-clj.agent.parallel-pool :as parallel-pool]
   [anima-agent-clj.agent.specialist-pool :as specialist-pool]
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Adapter Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord RoutingRule
           [pattern        ; Regex or predicate for matching
            target         ; :core-agent, :specialist-pool, :parallel-pool
            options])      ; Additional routing options

(defrecord AdapterStats
           [messages-in    ; atom<int> - Messages received
            messages-out   ; atom<int> - Messages sent
            routing-errors ; atom<int> - Routing failures
            start-time])   ; Date - When adapter started

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Adapter Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ChannelAdapter
           [id              ; UUID - Adapter identifier
            bus             ; Bus instance
            registry        ; ChannelRegistry
            core-agent      ; CoreAgent instance
            parallel-pool   ; ParallelPool instance (optional)
            specialist-pool ; SpecialistPool instance (optional)
            routing-rules   ; atom<vector> - RoutingRule list
            status          ; atom<keyword> - :stopped, :running
            stats           ; AdapterStats
            dispatch-stats  ; dispatch/DispatchStats
            config          ; Configuration
            metrics-collector ; MetricsCollector
            outbound-loop   ; atom - Outbound dispatch loop
            routing-loop])  ; atom - Routing loop

;; ══════════════════════════════════════════════════════════════════════════════
;; Configuration
;; ══════════════════════════════════════════════════════════════════════════════

(def default-config
  {:enable-parallel false      ; Enable parallel pool
   :enable-specialist false    ; Enable specialist pool
   :default-timeout 60000      ; Default timeout for routing
   :routing-strategy :default  ; :default, :specialist-first, :parallel-first
   :buffer-size 100})          ; Internal buffer size

;; ══════════════════════════════════════════════════════════════════════════════
;; Routing Rules
;; ══════════════════════════════════════════════════════════════════════════════

(defn add-routing-rule!
  "Add a routing rule to the adapter.

   Pattern can be:
   - A regex matched against message content
   - A predicate function: (fn [inbound-msg] boolean)
   - A keyword for metadata field matching

   Target can be:
   - :core-agent - Route to CoreAgent (default)
   - :specialist-pool - Route to SpecialistPool
   - :parallel-pool - Route to ParallelPool

   Example:
   (add-routing-rule! adapter
                      #\"^/analyze\"
                      :specialist-pool
                      {:task-type :code-analysis})"
  [adapter pattern target options]
  (let [rule (->RoutingRule pattern target (or options {}))]
    (swap! (:routing-rules adapter) conj rule)
    rule))

(defn clear-routing-rules!
  "Clear all routing rules."
  [adapter]
  (reset! (:routing-rules adapter) []))

(defn- matches-pattern?
  "Check if a message matches a routing pattern."
  [pattern inbound-msg]
  (cond
    (fn? pattern)
    (pattern inbound-msg)

    (instance? java.util.regex.Pattern pattern)
    (some? (re-matches pattern (or (:content inbound-msg) "")))

    (keyword? pattern)
    (some? (get-in inbound-msg [:metadata pattern]))

    :else false))

(defn- find-routing-target
  "Determine routing target for a message based on rules and config."
  [adapter inbound-msg]
  (let [rules @(:routing-rules adapter)
        config (:config adapter)]
    (or
     ;; Check explicit routing rules
     (some (fn [rule]
             (when (matches-pattern? (:pattern rule) inbound-msg)
               {:target (:target rule) :options (:options rule)}))
           rules)

     ;; Default routing based on strategy
     (case (:routing-strategy config)
       :specialist-first
       (if (and (:specialist-pool adapter)
                (seq (specialist-pool/list-capabilities (:specialist-pool adapter))))
         {:target :specialist-pool :options {}}
         {:target :core-agent :options {}})

       :parallel-first
       (if (:parallel-pool adapter)
         {:target :parallel-pool :options {}}
         {:target :core-agent :options {}})

       ;; Default: always use CoreAgent
       {:target :core-agent :options {}}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Message Routing
;; ══════════════════════════════════════════════════════════════════════════════

(defn- route-to-core-agent
  "Route message to CoreAgent (default path)."
  [adapter inbound-msg]
  ;; Just forward to the inbound bus - CoreAgent will pick it ;; publish to the bus
  (bus/publish-inbound! (:bus adapter) inbound-msg)
  {:status :routed :target :core-agent})

(defn- route-to-specialist-pool
  "Route message to SpecialistPool for specialized handling."
  [adapter inbound-msg options]
  (if-let [sp (:specialist-pool adapter)]
    (let [task-type (get options :task-type :default)
          task-spec {:type task-type
                     :payload {:content (:content inbound-msg)
                               :metadata (:metadata inbound-msg)}
                     :trace-id (:id inbound-msg)}]
      (async/go
        (let [result (async/<! (specialist-pool/route-task! sp task-spec))]
          ;; Send result back through bus
          (when result
            (let [outbound (bus/make-outbound
                            {:channel (:channel inbound-msg)
                             :chat-id (:chat-id inbound-msg)
                             :content (str (:result result) (:error result))
                             :reply-target (:sender-id inbound-msg)
                             :stage :final})]
              (bus/publish-outbound! (:bus adapter) outbound)))))
      {:status :routed :target :specialist-pool})
    {:status :error :error "SpecialistPool not configured"}))

(defn- route-to-parallel-pool
  "Route message to ParallelPool for concurrent processing."
  [adapter inbound-msg options]
  (if-let [pp (:parallel-pool adapter)]
    (let [tasks (get options :tasks
                     [{:type :api-call
                       :payload {:content (:content inbound-msg)}
                       :trace-id (:id inbound-msg)}])]
      (async/go
        (let [result (async/<! (parallel-pool/execute-parallel-async! pp tasks))]
          ;; Send result back through bus
          (when result
            (let [outbound (bus/make-outbound
                            {:channel (:channel inbound-msg)
                             :chat-id (:chat-id inbound-msg)
                             :content (str "Parallel execution: "
                                          (:status result)
                                          " - "
                                          (:successful result)
                                          "/"
                                          (:total result)
                                          " succeeded")
                             :reply-target (:sender-id inbound-msg)
                             :stage :final})]
              (bus/publish-outbound! (:bus adapter) outbound)))))
      {:status :routed :target :parallel-pool})
    {:status :error :error "ParallelPool not configured"}))

(defn route-message
  "Route an inbound message to the appropriate handler.

   Returns routing result map."
  [adapter inbound-msg]
  (swap! (:messages-in (:stats adapter)) inc)
  (when-let [mc (:metrics-collector adapter)]
    (metrics/counter-inc! mc "adapter_messages_in"))

  (let [{:keys [target options]} (find-routing-target adapter inbound-msg)]
    (try
      (let [result (case target
                     :core-agent (route-to-core-agent adapter inbound-msg)
                     :specialist-pool (route-to-specialist-pool adapter inbound-msg options)
                     :parallel-pool (route-to-parallel-pool adapter inbound-msg options)
                     (route-to-core-agent adapter inbound-msg))]
        (when (= :error (:status result))
          (swap! (:routing-errors (:stats adapter)) inc)
          (when-let [mc (:metrics-collector adapter)]
            (metrics/counter-inc! mc "adapter_routing_errors")))
        result)
      (catch Exception e
        (swap! (:routing-errors (:stats adapter)) inc)
        (when-let [mc (:metrics-collector adapter)]
          (metrics/counter-inc! mc "adapter_routing_errors"))
        {:status :error :error (.getMessage e)}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Integration
;; ══════════════════════════════════════════════════════════════════════════════

(defn register-channel!
  "Register a channel with the adapter.

   The channel will receive outbound messages and can send inbound messages."
  [adapter channel]
  (registry/register (:registry adapter) channel)
  channel)

(defn unregister-channel!
  "Unregister a channel from the adapter."
  [adapter channel-name account-id]
  (registry/unregister-with-account (:registry adapter) channel-name account-id))

(defn receive-message!
  "Receive a message from a channel and route it.

   Called by channels when they receive messages."
  [adapter channel-message]
  (let [inbound (bus/make-inbound
                 {:channel (or (:channel channel-message) "unknown")
                  :sender-id (:sender channel-message)
                  :chat-id (:session-id channel-message)
                  :content (:content channel-message)
                  :session-key (:session-id channel-message)
                  :metadata (merge (:metadata channel-message)
                                   {:account-id (:account-id channel-message)
                                    :message-id (:message-id channel-message)})})]
    (route-message adapter inbound)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dispatch Loops
;; ══════════════════════════════════════════════════════════════════════════════

(defn- outbound-dispatch-loop
  "Process outbound messages and deliver to channels."
  [adapter]
  (async/go-loop []
    (when (= :running @(:status adapter))
      (when-let [msg (async/<! (:outbound-chan (:bus adapter)))]
        (swap! (:messages-out (:stats adapter)) inc)
        (when-let [mc (:metrics-collector adapter)]
          (metrics/counter-inc! mc "adapter_messages_out"))
        (dispatch/dispatch-outbound-message
         msg
         (:registry adapter)
         (:dispatch-stats adapter))
        (recur)))))

(defn- routing-dispatch-loop
  "Process inbound messages and route to appropriate handler."
  [adapter]
  (async/go-loop []
    (when (= :running @(:status adapter))
      ;; Note: CoreAgent consumes from bus.inbound directly
      ;; This loop is for additional processing if needed
      (async/<! (async/timeout 100))
      (recur))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-adapter!
  "Start the channel adapter.

   Starts:
   - Outbound dispatch loop
   - CoreAgent (if not already running)
   - ParallelPool (if configured)
   - SpecialistPool (if configured)

   Returns the adapter."
  [adapter]
  (when (= :stopped @(:status adapter))
    (reset! (:status adapter) :running)

    ;; Start outbound dispatch loop
    (reset! (:outbound-loop adapter) (outbound-dispatch-loop adapter))

    ;; Start routing loop
    (reset! (:routing-loop adapter) (routing-dispatch-loop adapter))

    ;; Start CoreAgent
    (when-let [ca (:core-agent adapter)]
      (core-agent/start-core-agent! ca))

    ;; Start ParallelPool if configured
    (when-let [pp (:parallel-pool adapter)]
      (parallel-pool/start-parallel-pool! pp))

    ;; Start SpecialistPool if configured
    (when-let [sp (:specialist-pool adapter)]
      (specialist-pool/start-specialist-pool! sp)))

  adapter)

(defn stop-adapter!
  "Stop the channel adapter.

   Stops all components gracefully.
   Returns the adapter."
  [adapter]
  (when (= :running @(:status adapter))
    (reset! (:status adapter) :stopping)

    ;; Stop routing loop
    (when-let [rl @(:routing-loop adapter)]
      (async/close! rl)
      (reset! (:routing-loop adapter) nil))

    ;; Stop outbound loop
    (when-let [ol @(:outbound-loop adapter)]
      (async/close! ol)
      (reset! (:outbound-loop adapter) nil))

    ;; Stop SpecialistPool
    (when-let [sp (:specialist-pool adapter)]
      (specialist-pool/stop-specialist-pool! sp))

    ;; Stop ParallelPool
    (when-let [pp (:parallel-pool adapter)]
      (parallel-pool/stop-parallel-pool! pp))

    ;; Stop CoreAgent
    (when-let [ca (:core-agent adapter)]
      (core-agent/stop-core-agent! ca))

    (reset! (:status adapter) :stopped))

  adapter)

(defn restart-adapter!
  "Restart the channel adapter.
   Returns the adapter."
  [adapter]
  (stop-adapter! adapter)
  (start-adapter! adapter))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status & Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn adapter-status
  "Get adapter status."
  [adapter]
  {:id (:id adapter)
   :status @(:status adapter)
   :core-agent (when-let [ca (:core-agent adapter)]
                 {:id (:id ca)
                  :status @(:status ca)})
   :parallel-pool (when-let [pp (:parallel-pool adapter)]
                    (parallel-pool/parallel-pool-status pp))
   :specialist-pool (when-let [sp (:specialist-pool adapter)]
                      (specialist-pool/specialist-pool-status sp))
   :stats {:messages-in @(:messages-in (:stats adapter))
           :messages-out @(:messages-out (:stats adapter))
           :routing-errors @(:routing-errors (:stats adapter))
           :uptime-ms (- (System/currentTimeMillis)
                         (.getTime (:start-time (:stats adapter))))}
   :dispatch-stats (dispatch/get-stats (:dispatch-stats adapter))
   :routing-rules (count @(:routing-rules adapter))})

(defn adapter-metrics
  "Get detailed metrics from all components."
  [adapter]
  (merge
   {:adapter {:messages-in @(:messages-in (:stats adapter))
              :messages-out @(:messages-out (:stats adapter))
              :routing-errors @(:routing-errors (:stats adapter))}}
   (when-let [pp (:parallel-pool adapter)]
     {:parallel-pool (parallel-pool/parallel-pool-metrics pp)})
   (when-let [sp (:specialist-pool adapter)]
     {:specialist-pool (specialist-pool/specialist-pool-metrics sp)})
   (when-let [ca (:core-agent adapter)]
     {:core-agent (core-agent/core-agent-status ca)})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Convenience Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn send-message-through-adapter
  "Send a message through the adapter's outbound channel.

   Useful for system messages or notifications."
  [adapter channel-name chat-id content opts]
  (let [outbound (bus/make-outbound
                  (merge
                   {:channel channel-name
                    :chat-id chat-id
                    :content content}
                   opts))]
    (bus/publish-outbound! (:bus adapter) outbound)))

(defn broadcast-message
  "Broadcast a message to all registered channels."
  [adapter content opts]
  (let [channels (registry/all-channels (:registry adapter))]
    (doseq [ch channels]
      (let [outbound (bus/make-outbound
                      (merge
                       {:channel (ch/channel-name ch)
                        :content content}
                       opts))]
        (bus/publish-outbound! (:bus adapter) outbound)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-adapter
  "Create a new ChannelAdapter.

   Required options:
     :bus          - Bus instance

   Optional options:
     :registry           - ChannelRegistry (will create if not provided)
     :core-agent         - CoreAgent instance
     :parallel-pool      - ParallelPool instance
     :specialist-pool    - SpecialistPool instance
     :enable-parallel    - Create parallel pool from core-agent's worker-pool
     :enable-specialist  - Create specialist pool from core-agent's worker-pool
     :routing-strategy   - :default, :specialist-first, :parallel-first
     :buffer-size        - Internal buffer size (default: 100)
     :metrics-collector  - MetricsCollector instance

   Example:
   (create-adapter
     {:bus bus
      :core-agent agent
      :enable-parallel true
      :routing-strategy :default})"
  [{:keys [bus registry core-agent parallel-pool specialist-pool
           enable-parallel enable-specialist routing-strategy
           buffer-size metrics-collector]
    :or {enable-parallel false
         enable-specialist false
         routing-strategy :default
         buffer-size 100}}]
  (when-not bus
    (throw (ex-info "Bus is required" {})))

  (let [reg (or registry (registry/create-registry))
        ;; Create parallel pool if enabled and not provided
        pp (or parallel-pool
               (when (and enable-parallel core-agent)
                 (parallel-pool/create-parallel-pool
                  {:worker-pool (:worker-pool core-agent)
                   :metrics-collector metrics-collector})))
        ;; Create specialist pool if enabled and not provided
        sp (or specialist-pool
               (when (and enable-specialist core-agent)
                 (specialist-pool/create-specialist-pool
                  {:worker-pool (:worker-pool core-agent)
                   :metrics-collector metrics-collector})))]

    (->ChannelAdapter
     (str (UUID/randomUUID))
     bus
     reg
     core-agent
     pp
     sp
     (atom [])
     (atom :stopped)
     (->AdapterStats
      (atom 0)
      (atom 0)
      (atom 0)
      (Date.))
     (dispatch/create-dispatch-stats)
     (merge default-config
            {:enable-parallel enable-parallel
             :enable-specialist enable-specialist
             :routing-strategy routing-strategy
             :buffer-size buffer-size})
     metrics-collector
     (atom nil)
     (atom nil))))
