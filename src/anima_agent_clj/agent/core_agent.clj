(ns anima-agent-clj.agent.core-agent
  "Core Agent for the Agent Cluster Architecture.

   The CoreAgent is the brain of the cluster, responsible for:
   - Reasoning: Analyze incoming tasks and determine execution strategy
   - Orchestration: Coordinate WorkerAgents to execute subtasks
   - Memory: Manage conversation context and session history

   Architecture:
   ┌─────────────────────────────────────────────────────────────┐
   │                        CoreAgent                            │
   │  ┌───────────┐  ┌───────────────┐  ┌───────────────────┐   │
   │  │ Reasoner  │  │ Orchestrator  │  │ Memory Manager    │   │
   │  │           │→ │               │→ │                   │   │
   │  │ (Plan)    │  │ (Execute)     │  │ (Context/History) │   │
   │  └───────────┘  └───────────────┘  └───────────────────┘   │
   │         ↓                ↓                    ↓            │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │                   WorkerPool                         │   │
   │  │  [Worker1] [Worker2] [Worker3] ... [WorkerN]        │   │
   │  └─────────────────────────────────────────────────────┘   │
   └─────────────────────────────────────────────────────────────┘

   Phase 3 Integration:
   - ContextManager: L1/L2/L3 tiered context storage
   - ResultCache: LRU/TTL caching for API responses
   - Metrics: Performance monitoring and statistics

   Usage:
   (def agent (create-core-agent {:bus bus :opencode-client client}))
   (start-core-agent! agent)
   ;; ... processes messages from bus ...
   (stop-core-agent! agent)"
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string]
   [anima-agent-clj.bus :as bus]
   [anima-agent-clj.bus.core :as bus-core]
   [anima-agent-clj.agent.worker-agent :as worker]
   [anima-agent-clj.agent.worker-pool :as pool]
   [anima-agent-clj.channel.session :as session]
   [anima-agent-clj.messages :as messages]
   [anima-agent-clj.sessions :as sessions]
   ;; Phase 3: Context, Cache, Metrics
   [anima-agent-clj.context.manager :as context-manager]
   [anima-agent-clj.cache.lru :as cache-lru]
   [anima-agent-clj.cache.core :as cache-core]
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Memory & Context Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord MemoryEntry
           [id              ; UUID
            session-id      ; Session identifier
            type            ; :user-message, :assistant-message, :task, :result
            content         ; The actual content
            timestamp       ; Date
            metadata])      ; Additional info

(defrecord SessionContext
           [session-id      ; OpenCode session ID
            chat-id         ; External chat ID
            channel         ; Channel name
            history         ; Vector of MemoryEntry
            metadata        ; Session metadata
            created-at      ; Date
            updated-at])    ; Date

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Plan Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord TaskPlan
           [id              ; UUID
            trace-id        ; Trace chain ID
            goal            ; High-level goal description
            steps           ; Vector of planned steps
            dependencies    ; Map of step-id -> [dependent-step-ids]
            status          ; :pending, :executing, :completed, :failed
            created-at      ; Date
            metadata])

(defrecord ExecutionStep
           [id              ; UUID
            type            ; :api-call, :parallel, :sequential, :transform
            task            ; Worker task specification
            status          ; :pending, :running, :completed, :failed
            result          ; Step result
            error           ; Error info
            retries         ; Number of retries attempted
            dependencies])  ; [step-ids] that must complete first

;; ══════════════════════════════════════════════════════════════════════════════
;; Core Agent Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord CoreAgent
           [id              ; UUID - Agent identifier
            bus             ; Bus instance
            opencode-client ; OpenCode client config
            session-manager ; SessionStore
            worker-pool     ; WorkerPool instance
            ;; Phase 3: New components
            context-manager ; ContextManager - Tiered context storage
            result-cache    ; LRUCache - API response caching
            metrics-collector ; MetricsCollector - Performance monitoring
            ;; Legacy (deprecated - use context-manager instead)
            memory          ; atom<map> - session-id -> SessionContext
            active-plans    ; atom<map> - plan-id -> TaskPlan
            status          ; atom<keyword> - :stopped, :running
            main-loop       ; atom - Main processing loop
            config])        ; Agent configuration

;; ══════════════════════════════════════════════════════════════════════════════
;; Memory Management
;; ══════════════════════════════════════════════════════════════════════════════

(defn- make-memory-entry
  "Create a new memory entry."
  [type content metadata]
  (->MemoryEntry
   (str (UUID/randomUUID))
   nil
   type
   content
   (Date.)
   metadata))

(defn- get-or-create-context
  "Get or create session context."
  [agent chat-id channel]
  (let [memory (:memory agent)
        key (str channel ":" chat-id)]
    (if-let [ctx (get @memory key)]
      ctx
      (let [new-ctx (->SessionContext
                     nil
                     chat-id
                     channel
                     []
                     {}
                     (Date.)
                     (Date.))]
        (swap! memory assoc key new-ctx)
        new-ctx))))

(defn- update-context
  "Update session context with new entry."
  [agent chat-id channel entry]
  (let [memory (:memory agent)
        key (str channel ":" chat-id)]
    (swap! memory update key
           (fn [ctx]
             (-> ctx
                 (update :history conj entry)
                 (assoc :updated-at (Date.)))))))

(defn- set-session-id
  "Set OpenCode session ID for a chat."
  [agent chat-id channel session-id]
  (let [memory (:memory agent)
        key (str channel ":" chat-id)]
    (swap! memory update key
           (fn [ctx]
             (-> ctx
                 (assoc :session-id session-id)
                 (assoc :updated-at (Date.)))))))

(defn- get-context-history
  "Get conversation history for a session."
  [agent chat-id channel]
  (let [memory (:memory agent)
        key (str channel ":" chat-id)]
    (get-in @memory [key :history] [])))

;; ══════════════════════════════════════════════════════════════════════════════
;; Reasoner - Task Analysis & Planning
;; ══════════════════════════════════════════════════════════════════════════════

(defmulti analyze-task
  "Analyze a task and create an execution plan.
   Dispatches on message type or content pattern."
  (fn [_agent inbound-msg]
    (let [content (:content inbound-msg)
          metadata (:metadata inbound-msg)]
      (cond
        (get metadata :system-command) :system-command
        (string/starts-with? (or content "") "/") :command
        (get metadata :multi-step) :multi-step
        :else :default))))

(defmethod analyze-task :system-command
  [agent inbound-msg]
  ;; System commands are handled directly without worker
  {:type :direct
   :action :system-command
   :payload inbound-msg})

(defmethod analyze-task :command
  [agent inbound-msg]
  ;; Slash commands may need special handling
  {:type :single
   :task (worker/make-task
          {:type :api-call
           :payload {:content (:content inbound-msg)}
           :trace-id (:id inbound-msg)})})

(defmethod analyze-task :multi-step
  [agent inbound-msg]
  ;; Multi-step tasks are decomposed
  {:type :sequential
   :steps [(worker/make-task
            {:type :session-create
             :trace-id (:id inbound-msg)})
           (worker/make-task
            {:type :api-call
             :payload {:content (:content inbound-msg)}
             :trace-id (:id inbound-msg)})]})

(defmethod analyze-task :default
  [_agent inbound-msg]
  ;; Default: single API call
  {:type :single
   :task (worker/make-task
          {:type :api-call
           :payload {:content (:content inbound-msg)}
           :trace-id (:id inbound-msg)})})

;; ══════════════════════════════════════════════════════════════════════════════
;; Orchestrator - Task Execution
;; ══════════════════════════════════════════════════════════════════════════════

(declare execute-plan)

(defn- execute-single-task
  "Execute a single task and return result channel."
  [agent task session-id]
  (let [task-with-session (assoc-in task [:payload :opencode-session-id] session-id)
        worker-pool (:worker-pool agent)]
    (pool/submit-task! worker-pool task-with-session)))

(defn- execute-sequential-tasks
  "Execute tasks sequentially, passing results between them."
  [agent tasks session-id result-ch]
  (async/go
    (loop [remaining tasks
           results []
           last-result nil]
      (if (empty? remaining)
        (async/>! result-ch {:status :success :results results})
        (let [task (first remaining)
              ;; Inject previous result if needed
              task (if last-result
                     (update-in task [:payload] merge {:previous-result last-result})
                     task)
              result (async/<! (execute-single-task agent task session-id))]
          (if (= :success (:status result))
            (recur (rest remaining)
                   (conj results result)
                   (:result result))
            (async/>! result-ch {:status :failure
                                 :error (:error result)
                                 :partial-results results})))))))

(defn- execute-parallel-tasks
  "Execute tasks in parallel and collect all results."
  [agent tasks session-id result-ch]
  (async/go
    (let [result-chs (mapv #(execute-single-task agent % session-id) tasks)
          results (vec (for [rc result-chs]
                         (async/<! rc)))
          failures (filter #(not= :success (:status %)) results)]
      (if (empty? failures)
        (async/>! result-ch {:status :success :results results})
        (async/>! result-ch {:status :partial-failure
                             :results results
                             :failed (count failures)})))))

(defn- execute-plan
  "Execute an execution plan."
  [agent plan session-id]
  (let [result-ch (async/chan)]
    (case (:type plan)
      :direct
      (do
        (async/put! result-ch {:status :success :result (:payload plan)})
        result-ch)

      :single
      (async/go
        (let [result (async/<! (execute-single-task agent (:task plan) session-id))]
          (async/>! result-ch result)))

      :sequential
      (execute-sequential-tasks agent (:steps plan) session-id result-ch)

      :parallel
      (execute-parallel-tasks agent (:steps plan) session-id result-ch)

      ;; Unknown type
      (do
        (async/put! result-ch {:status :failure :error "Unknown plan type"})
        result-ch))
    result-ch))

;; ══════════════════════════════════════════════════════════════════════════════
;; Session Management
;; ══════════════════════════════════════════════════════════════════════════════

(defn- get-or-create-opencode-session
  "Get or create an OpenCode session."
  [agent inbound-msg]
  (let [{:keys [opencode-client session-manager]} agent
        chat-id (:chat-id inbound-msg)
        channel (:channel inbound-msg)
        session-key (:session-key inbound-msg)]
    (if-let [ctx (get @(:memory agent) (str channel ":" chat-id))]
      ;; Check if we have an existing session
      (if-let [existing-id (:session-id ctx)]
        existing-id
        ;; Create new session
        (try
          (let [result (sessions/create-session opencode-client)]
            (when-let [new-id (:id result)]
              (set-session-id agent chat-id channel new-id)
              new-id))
          (catch Exception e
            (println "Failed to create OpenCode session:" (.getMessage e))
            nil)))
      ;; No context, create both
      (try
        (let [result (sessions/create-session opencode-client)]
          (when-let [new-id (:id result)]
            (get-or-create-context agent chat-id channel)
            (set-session-id agent chat-id channel new-id)
            new-id))
        (catch Exception e
          (println "Failed to create OpenCode session:" (.getMessage e))
          nil)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Response Handling
;; ══════════════════════════════════════════════════════════════════════════════

(defn- extract-response-text
  "Extract text from OpenCode API response."
  [response]
  (cond
    ;; Extract from response parts (direct format)
    (:parts response)
    (let [parts (:parts response)
          reasoning (->> parts
                         (filter #(= "reasoning" (:type %)))
                         (map :text)
                         (string/join "\n"))
          text (->> parts
                    (filter #(= "text" (:type %)))
                    (map :text)
                    (string/join "\n"))]
      (if (not-empty reasoning)
        (str "【Reasoning】\n" reasoning "\n【End Reasoning】\n\n" text)
        text))

    ;; Extract from nested messages format
    (get-in response [:data :messages])
    (->> (get-in response [:data :messages])
         (mapcat :parts)
         (filter #(or (= "text" (:type %))
                      (= "reasoning" (:type %))))
         (map :text)
         (string/join "\n"))

    ;; Direct content
    (:content response)
    (:content response)

    ;; Fallback
    :else (str response)))

(defn- send-response
  "Send response back through the bus."
  [agent inbound-msg response-text]
  (let [{:keys [bus]} agent
        channel-name (:channel inbound-msg)]
    (let [outbound (bus/make-outbound
                    {:channel channel-name
                     :account-id (get-in inbound-msg [:metadata :account-id] "default")
                     :chat-id (:chat-id inbound-msg)
                     :content response-text
                     :reply-target (:sender-id inbound-msg)
                     :stage :final})]
      (bus/publish-outbound! bus outbound)
      outbound)))

(defn- send-error-response
  "Send error response back through the bus."
  [agent inbound-msg error-msg]
  (let [{:keys [bus]} agent
        channel-name (:channel inbound-msg)]
    (let [outbound (bus/make-outbound
                    {:channel channel-name
                     :content (str "Error: " error-msg)
                     :reply-target (:sender-id inbound-msg)
                     :stage :final})]
      (bus/publish-outbound! bus outbound)
      outbound)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Main Processing Loop
;; ══════════════════════════════════════════════════════════════════════════════

(defn- process-inbound-message
  "Process a single inbound message."
  [agent inbound-msg]
  (let [start-time (System/currentTimeMillis)
        metrics (:metrics-collector agent)]
    (metrics/counter-inc! metrics "messages_received")
    (try
      ;; 1. Store in memory
      (let [chat-id (:chat-id inbound-msg)
            channel (:channel inbound-msg)
            entry (make-memory-entry :user-message
                                     (:content inbound-msg)
                                     {:sender-id (:sender-id inbound-msg)})]
        (update-context agent chat-id channel entry)
        ;; Also store in new context manager
        (context-manager/add-to-session-history!
         (:context-manager agent)
         (str chat-id)
         {:role :user :content (:content inbound-msg) :timestamp (Date.)}))

      ;; 2. Get or create OpenCode session
      (let [opencode-session-id (get-or-create-opencode-session agent inbound-msg)]
        (if opencode-session-id
          ;; 3. Analyze and plan
          (let [plan (analyze-task agent inbound-msg)]

            ;; 4. Execute plan
            (if (= :direct (:type plan))
              ;; Direct execution (system commands, etc.)
              (do
                (metrics/counter-inc! metrics "messages_processed")
                (send-response agent inbound-msg "Command processed."))
              ;; Normal execution through workers
              (let [result-ch (execute-plan agent plan opencode-session-id)]
                (async/go
                  (when-let [result (async/<! result-ch)]
                    (let [duration (- (System/currentTimeMillis) start-time)]
                      (metrics/histogram-record! metrics "message_latency" duration))
                    (if (= :success (:status result))
                      (let [response-text (extract-response-text
                                           (get-in result [:result]))]
                        ;; Store assistant response in memory
                        (let [entry (make-memory-entry :assistant-message
                                                       response-text
                                                       {})]
                          (update-context agent
                                          (:chat-id inbound-msg)
                                          (:channel inbound-msg)
                                          entry))
                        ;; Also store in context manager
                        (context-manager/add-to-session-history!
                         (:context-manager agent)
                         (str (:chat-id inbound-msg))
                         {:role :assistant :content response-text :timestamp (Date.)})
                        ;; Send response
                        (metrics/counter-inc! metrics "messages_processed")
                        (send-response agent inbound-msg response-text))
                      (do
                        (metrics/counter-inc! metrics "messages_failed")
                        (send-error-response agent inbound-msg
                                             (or (:error result) "Task execution failed")))))))))

          ;; Failed to get session
          (do
            (metrics/counter-inc! metrics "messages_failed")
            (send-error-response agent inbound-msg
                                 "Failed to create OpenCode session. Make sure opencode-server is running."))))

      (catch Exception e
        (metrics/counter-inc! metrics "messages_failed")
        (println "CoreAgent error processing message:" (.getMessage e))
        (send-error-response agent inbound-msg (.getMessage e))))))

(defn- main-loop-fn
  "Main processing loop."
  [agent]
  (async/go-loop []
    (when (= :running @(:status agent))
      (when-let [msg (async/<! (get-in (:bus agent) [:inbound :chan]))]
        (process-inbound-message agent msg)
        (recur)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Lifecycle Management
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-core-agent!
  "Start the core agent.
   Starts the worker pool and begins processing messages.
   Returns the agent."
  [agent]
  (when (= :stopped @(:status agent))
    (reset! (:status agent) :running)

    ;; Start worker pool
    (pool/start-pool! (:worker-pool agent))

    ;; Start main loop
    (reset! (:main-loop agent) (main-loop-fn agent)))
  agent)

(defn stop-core-agent!
  "Stop the core agent gracefully.
   Stops the worker pool and cleans up resources.
   Returns the agent."
  [agent]
  (when (= :running @(:status agent))
    (reset! (:status agent) :stopping)

    ;; Stop main loop
    (when-let [ml @(:main-loop agent)]
      (async/close! ml)
      (reset! (:main-loop agent) nil))

    ;; Stop worker pool
    (pool/stop-pool! (:worker-pool agent))

    (reset! (:status agent) :stopped))
  agent)

(defn restart-core-agent!
  "Restart the core agent.
   Returns the agent."
  [agent]
  (stop-core-agent! agent)
  (start-core-agent! agent))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status & Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn core-agent-status
  "Get current core agent status."
  [agent]
  {:id (:id agent)
   :status @(:status agent)
   :sessions-count (count @(:memory agent))
   :active-plans-count (count @(:active-plans agent))
   :worker-pool (pool/pool-status (:worker-pool agent))
   ;; Phase 3: New component status
   :context-manager (context-manager/manager-status (:context-manager agent))
   :cache (cache-core/stats (:result-cache agent))
   :metrics (metrics/get-metrics (:metrics-collector agent))})

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-core-agent
  "Create a new CoreAgent.

   Options:
     :bus              - Bus instance (required)
     :opencode-client  - OpenCode client map {:base-url \"...\"}
     :session-manager  - SessionStore instance
     :opencode-url     - OpenCode server URL (default: http://127.0.0.1:9711)
     :pool-config      - WorkerPool configuration map
       :min-size       - Minimum workers (default: 1)
       :max-size       - Maximum workers (default: 10)
       :initial-size   - Starting workers (default: 2)
     :context-config   - ContextManager configuration (Phase 3)
       :enable-l2      - Enable L2 file storage (default: true)
       :l1-max-entries - L1 max entries (default: 10000)
     :cache-config     - ResultCache configuration (Phase 3)
       :max-entries    - Cache max entries (default: 1000)
       :default-ttl    - Default TTL in ms (default: 5 min)
     :metrics-config   - Metrics configuration (Phase 3)
       :prefix         - Metric prefix (default: \"anima\")"
  [{:keys [bus opencode-client session-manager opencode-url pool-config
           context-config cache-config metrics-config]
    :or {opencode-url "http://127.0.0.1:9711"}}]
  (let [client (or opencode-client {:base-url opencode-url})
        sm (or session-manager (session/create-store))
        worker-pool (pool/create-pool
                     (merge {:opencode-client client}
                            pool-config))
        ;; Phase 3: Initialize new components
        ctx-manager (context-manager/create-manager
                     (merge {:enable-l2 true
                             :l1-max-entries 10000}
                            context-config))
        result-cache (cache-lru/create-lru-cache
                      (merge {:max-entries 1000
                              :default-ttl (* 5 60 1000)}
                             cache-config))
        metrics-coll (metrics/create-collector
                      (merge {:prefix "anima"}
                             metrics-config))]
    ;; Register agent metrics
    (metrics/register-agent-metrics metrics-coll)
    (->CoreAgent
     (str (UUID/randomUUID))
     bus
     client
     sm
     worker-pool
     ctx-manager
     result-cache
     metrics-coll
     (atom {})   ; memory (legacy)
     (atom {})   ; active-plans
     (atom :stopped)
     (atom nil)
     {:opencode-url opencode-url})))
