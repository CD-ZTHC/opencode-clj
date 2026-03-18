(ns anima-agent-clj.dispatcher.core
  "Event Dispatcher for the Agent Cluster Architecture.

   The Event Dispatcher is the central routing hub for all messages,
   implementing priority-based scheduling, load balancing, and fault tolerance.

   Architecture:
   ┌─────────────────────────────────────────────────────────────┐
   │                    Event Dispatcher                          │
   ├─────────────────────────────────────────────────────────────┤
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │           Priority Queue (优先级队列)                 │   │
   │  │   P0: 系统控制 (扩缩容、配置更新)                      │   │
   │  │   P1-P3: 实时交互 (用户对话)                          │   │
   │  │   P4-P6: 普通任务 (批量处理)                          │   │
   │  │   P7-P9: 后台任务 (日志、统计)                        │   │
   │  └─────────────────────────────────────────────────────┘   │
   │                          ↓                                  │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │           Router (路由器)                            │   │
   │  │   - 任务类型识别                                      │   │
   │  │   - 目标 Agent 选择                                   │   │
   │  │   - 负载均衡策略                                      │   │
   │  └─────────────────────────────────────────────────────┘   │
   │                          ↓                                  │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │         Circuit Breaker (熔断器)                     │   │
   │  │   - 健康检查                                          │   │
   │  │   - 熔断降级                                          │   │
   │  │   - 限流控制                                          │   │
   │  └─────────────────────────────────────────────────────┘   │
   └─────────────────────────────────────────────────────────────┘

   Usage:
   (def dispatcher (create-dispatcher {:bus bus :agents [agent1 agent2]}))
   (start-dispatcher! dispatcher)
   (dispatch! dispatcher message)
   (stop-dispatcher! dispatcher)"
  (:require [clojure.core.async :as async]
            [anima-agent-clj.bus.core :as bus-core]
            [anima-agent-clj.dispatcher.router :as router]
            [anima-agent-clj.dispatcher.balancer :as balancer]
            [anima-agent-clj.dispatcher.circuit-breaker :as cb]
            [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date Comparator]
           [java.util.concurrent PriorityBlockingQueue]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Priority Definitions
;; ══════════════════════════════════════════════════════════════════════════════

(def priority-levels
  "Priority level definitions (0 = highest, 9 = lowest)"
  {:system 0 ; System control (scaling, config)
   :realtime 1 ; Real-time interaction (user chat) - P1-P3
   :interactive 2
   :urgent 3
   :normal 5 ; Normal tasks (batch processing) - P4-P6
   :batch 6
   :background 8 ; Background tasks (logs, stats) - P7-P9
   :low 9})

(defn get-priority
  "Get numeric priority from keyword or number."
  [p]
  (if (keyword? p)
    (get priority-levels p 5)
    (max 0 (min 9 (or p 5)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dispatch Message Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord DispatchMessage
           [id ; UUID - Message ID
            trace-id ; UUID - Trace chain ID
            source ; String - Source component
            target ; String/keyword - Target agent or :any
            type ; Keyword - Message type
            priority ; Integer (0-9) - Priority level
            payload ; Any - Message payload
            metadata ; Map - Additional metadata
            timestamp ; Date - Creation time
            ttl ; Long - Time to live in ms
            retry-count ; Integer - Number of retries attempted
            result-ch]) ; Channel - Where to send result

(defn make-dispatch-message
  "Create a new DispatchMessage."
  [{:keys [source target type priority payload metadata ttl]
    :or {priority 5
         metadata {}
         ttl 60000}}]
  (->DispatchMessage
   (str (UUID/randomUUID))
   (str (UUID/randomUUID))
   source
   target
   type
   (get-priority priority)
   payload
   metadata
   (Date.)
   ttl
   0
   (async/promise-chan)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Priority Queue Implementation
;; ══════════════════════════════════════════════════════════════════════════════

(defn- create-priority-queue
  "Create a priority queue for dispatch messages."
  []
  (PriorityBlockingQueue.
   11
   (reify Comparator
     (compare [_ m1 m2]
       (let [p1 (:priority m1 5)
             p2 (:priority m2 5)]
         (compare p1 p2))))))

(defn- queue-offer!
  "Add message to priority queue. Returns true if successful."
  [queue msg]
  (.offer queue msg))

(defn- queue-poll!
  "Take highest priority message from queue. Returns nil if empty."
  [queue timeout-ms]
  (.poll queue timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS))

(defn- queue-size
  "Get current queue size."
  [queue]
  (.size queue))

(defn- queue-clear!
  "Clear all messages from queue."
  [queue]
  (.clear queue))

;; ══════════════════════════════════════════════════════════════════════════════
;; Agent Registration
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord AgentInfo
           [id ; String - Agent identifier
            type ; Keyword - :core, :worker, :parallel, :specialist
            capabilities ; Set<keyword> - What this agent can do
            ref ; Agent instance reference
            status ; atom<keyword> - :available, :busy, :offline
            load ; atom<int> - Current load
            last-heartbeat ; atom<Date> - Last heartbeat time
            metadata]) ; Map - Additional info

(defn register-agent!
  "Register an agent with the dispatcher."
  [dispatcher agent-info]
  (let [id (:id agent-info)]
    (swap! (:agents dispatcher) assoc id agent-info)
    ;; Also add to balancer
    (balancer/add-target! (:balancer dispatcher)
                          (balancer/make-target
                           {:id id
                            :weight (case (:type agent-info)
                                      :core 10
                                      :worker 5
                                      :parallel 3
                                      :specialist 7
                                      5)
                            :capacity (get-in agent-info [:metadata :capacity] 10)}))
    agent-info))

(defn unregister-agent!
  "Unregister an agent from the dispatcher."
  [dispatcher agent-id]
  (when-let [agent-info (get @(:agents dispatcher) agent-id)]
    (swap! (:agents dispatcher) dissoc agent-id)
    (balancer/remove-target! (:balancer dispatcher) agent-id)
    agent-info))

(defn get-agent
  "Get agent by ID."
  [dispatcher agent-id]
  (get @(:agents dispatcher) agent-id))

(defn list-agents
  "List all registered agents."
  [dispatcher]
  (vals @(:agents dispatcher)))

(defn list-available-agents
  "List all available agents."
  [dispatcher]
  (filter #(= :available @(:status %))
          (vals @(:agents dispatcher))))

(defn list-agents-by-type
  "List agents by type."
  [dispatcher type]
  (filter #(= type (:type %))
          (vals @(:agents dispatcher))))

(defn list-agents-by-capability
  "List agents that have a specific capability."
  [dispatcher capability]
  (filter #(contains? (:capabilities %) capability)
          (vals @(:agents dispatcher))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Event Dispatcher Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord EventDispatcher
           [id ; String - Unique identifier
            bus ; Bus instance
            agents ; atom<map> - id -> AgentInfo
            balancer ; Balancer instance
            circuit-breaker ; CircuitBreaker instance
            priority-queue ; PriorityBlockingQueue
            config ; Map - Configuration
            status ; atom<keyword> - :stopped, :running
            stats ; atom<map> - Statistics
            metrics ; MetricsCollector - Optional
            dispatch-loop ; atom - Main dispatch loop
            heartbeat-loop]) ; atom - Heartbeat check loop

(def default-config
  {:max-queue-size 10000
   :dispatch-timeout-ms 5000
   :heartbeat-interval-ms 30000
   :agent-timeout-ms 60000
   :max-retries 3})

;; ══════════════════════════════════════════════════════════════════════════════
;; Agent Selection
;; ══════════════════════════════════════════════════════════════════════════════

(defn- select-agent
  "Select the best agent for a message."
  [dispatcher message]
  (let [target (:target message)
        msg-type (:type message)
        available (list-available-agents dispatcher)]

    (cond
      ;; Specific target requested
      (and target (not= :any target))
      (when-let [agent (get-agent dispatcher target)]
        (when (= :available @(:status agent))
          agent))

      ;; Find agent by message type capability
      msg-type
      (let [capable (filter #(contains? (:capabilities %) msg-type) available)]
        (if (seq capable)
          (first capable)
          ;; Fallback to any available agent
          (first available)))

      ;; Default: any available agent
      :else
      (first available))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dispatch Loop
;; ══════════════════════════════════════════════════════════════════════════════

(defn- dispatch-message
  "Dispatch a single message to an agent."
  [dispatcher message]
  (let [start-time (System/currentTimeMillis)
        mc (:metrics dispatcher)]
    (try
      ;; Check circuit breaker
      (if (and (:circuit-breaker dispatcher)
               (not (cb/allows-request? (:circuit-breaker dispatcher))))
        {:status :failed
         :error "Circuit breaker is open"
         :circuit-breaker :open}

        ;; Select agent
        (if-let [agent (select-agent dispatcher message)]
          (let [agent-ref (:ref agent)
                result-ch (:result-ch message)]

            ;; Update agent status
            (swap! (:load agent) inc)
            (reset! (:status agent) :busy)

            ;; Mark agent as busy in balancer
            (balancer/update-load! (:balancer dispatcher) (:id agent) :inc)

            ;; Process through circuit breaker
            (let [cb-result
                  (if (:circuit-breaker dispatcher)
                    (cb/with-circuit-breaker
                      (:circuit-breaker dispatcher)
                      (fn []
                        ;; Send message to agent
                        (when (and (:bus dispatcher) agent-ref)
                          (let [bus-msg (bus-core/make-message
                                         {:source (:source message)
                                          :target (:id agent)
                                          :type (:type message)
                                          :priority (:priority message)
                                          :payload (:payload message)
                                          :trace-id (:trace-id message)})]
                            (bus-core/publish! (:inbound (:bus dispatcher)) bus-msg)))
                        {:status :dispatched}))
                    (do
                      ;; Without circuit breaker
                      (when (and (:bus dispatcher) agent-ref)
                        (let [bus-msg (bus-core/make-message
                                       {:source (:source message)
                                        :target (:id agent)
                                        :type (:type message)
                                        :priority (:priority message)
                                        :payload (:payload message)
                                        :trace-id (:trace-id message)})]
                          (bus-core/publish! (:inbound (:bus dispatcher)) bus-msg)))
                      {:status :dispatched}))]

              ;; Update stats
              (let [duration (- (System/currentTimeMillis) start-time)]
                (swap! (:stats dispatcher)
                       (fn [s]
                         (-> s
                             (update :total-dispatched inc)
                             (update :total-duration + duration))))
                (when mc
                  (metrics/counter-inc! mc "messages_dispatched")
                  (metrics/histogram-record! mc "dispatch_duration" duration)))

              ;; Update agent status back to available
              (swap! (:load agent) dec)
              (when (zero? @(:load agent))
                (reset! (:status agent) :available))
              (balancer/update-load! (:balancer dispatcher) (:id agent) :dec)

              cb-result))

          ;; No agent available
          (do
            (swap! (:stats dispatcher) update :no-agent-failures inc)
            (when mc (metrics/counter-inc! mc "dispatch_no_agent"))
            {:status :failed
             :error "No available agent"
             :retryable true})))

      (catch Exception e
        (swap! (:stats dispatcher) update :errors inc)
        (when mc (metrics/counter-inc! mc "dispatch_errors"))
        {:status :failed
         :error (.getMessage e)
         :exception e}))))

(defn- dispatch-loop-fn
  "Main dispatch loop that processes messages from the priority queue."
  [dispatcher]
  (async/go-loop []
    (when (= :running @(:status dispatcher))
      (try
        (let [msg (queue-poll! (:priority-queue dispatcher) 100)]
          (when msg
            (let [result (dispatch-message dispatcher msg)]
              (when-let [result-ch (:result-ch msg)]
                (async/put! result-ch result)))))
        (catch Exception e
          (println "Dispatch loop error:" (.getMessage e))))
      (recur))))

(defn- heartbeat-loop-fn
  "Check agent health periodically."
  [dispatcher interval-ms]
  (async/go-loop []
    (when (= :running @(:status dispatcher))
      (async/<! (async/timeout interval-ms))
      (try
        (let [now (System/currentTimeMillis)
              timeout-ms (get-in dispatcher [:config :agent-timeout-ms] 60000)]
          (doseq [[id agent] @(:agents dispatcher)]
            (let [last-hb @(:last-heartbeat agent)
                  hb-time (when last-hb (.getTime last-hb))]
              (when (and hb-time (> (- now hb-time) timeout-ms))
                ;; Agent timed out
                (reset! (:status agent) :offline)
                (balancer/mark-target-offline! (:balancer dispatcher) id)))))
        (catch Exception e
          (println "Heartbeat check error:" (.getMessage e))))
      (recur))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Public API
;; ══════════════════════════════════════════════════════════════════════════════

(defn dispatch!
  "Dispatch a message through the event dispatcher.

   Returns a channel that will receive the dispatch result.

   Options:
     :source - Source component (required)
     :target - Target agent ID or :any (default: :any)
     :type - Message type (used for agent capability matching)
     :priority - Priority level (keyword or 0-9, default: 5)
     :payload - Message payload
     :metadata - Additional metadata
     :ttl - Time to live in ms (default: 60000)

   Example:
   (dispatch! dispatcher {:source \"channel-1\"
                         :type :api-call
                         :priority :realtime
                         :payload {:content \"Hello\"}})"
  [dispatcher opts]
  (let [msg (make-dispatch-message opts)
        queue-size (queue-size (:priority-queue dispatcher))
        max-size (get-in dispatcher [:config :max-queue-size] 10000)]
    (if (< queue-size max-size)
      (do
        (queue-offer! (:priority-queue dispatcher) msg)
        (swap! (:stats dispatcher) update :messages-queued inc)
        (:result-ch msg))
      ;; Queue full
      (let [ch (async/promise-chan)]
        (async/put! ch {:status :failed
                        :error "Queue full"
                        :retryable true})
        ch))))

(defn dispatch-sync!
  "Synchronous dispatch - blocks until result is ready."
  [dispatcher opts timeout-ms]
  (let [result-ch (dispatch! dispatcher opts)]
    (async/<!! result-ch)))

(defn dispatch-async!
  "Async dispatch with callback."
  [dispatcher opts callback]
  (let [result-ch (dispatch! dispatcher opts)]
    (async/go
      (when-let [result (async/<! result-ch)]
        (callback result)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-dispatcher!
  "Start the event dispatcher."
  [dispatcher]
  (when (= :stopped @(:status dispatcher))
    (reset! (:status dispatcher) :running)

    ;; Start dispatch loop
    (reset! (:dispatch-loop dispatcher)
            (dispatch-loop-fn dispatcher))

    ;; Start heartbeat loop
    (reset! (:heartbeat-loop dispatcher)
            (heartbeat-loop-fn dispatcher
                               (get-in dispatcher [:config :heartbeat-interval-ms] 30000))))

  dispatcher)

(defn stop-dispatcher!
  "Stop the event dispatcher."
  [dispatcher]
  (when (= :running @(:status dispatcher))
    (reset! (:status dispatcher) :stopping)

    ;; Stop loops
    (when-let [dl @(:dispatch-loop dispatcher)]
      (async/close! dl)
      (reset! (:dispatch-loop dispatcher) nil))

    (when-let [hl @(:heartbeat-loop dispatcher)]
      (async/close! hl)
      (reset! (:heartbeat-loop dispatcher) nil))

    (reset! (:status dispatcher) :stopped))

  dispatcher)

(defn restart-dispatcher!
  "Restart the dispatcher."
  [dispatcher]
  (stop-dispatcher! dispatcher)
  (start-dispatcher! dispatcher))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status and Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn dispatcher-status
  "Get dispatcher status."
  [dispatcher]
  {:id (:id dispatcher)
   :status @(:status dispatcher)
   :queue-size (queue-size (:priority-queue dispatcher))
   :agent-count (count @(:agents dispatcher))
   :available-agents (count (list-available-agents dispatcher))
   :circuit-breaker (when (:circuit-breaker dispatcher)
                      (cb/circuit-breaker-status (:circuit-breaker dispatcher)))
   :config (:config dispatcher)
   :stats @(:stats dispatcher)})

(defn dispatcher-metrics
  "Get dispatcher metrics."
  [dispatcher]
  (merge
   @(:stats dispatcher)
   {:queue-size (queue-size (:priority-queue dispatcher))
    :agent-count (count @(:agents dispatcher))
    :available-count (count (list-available-agents dispatcher))
    :avg-dispatch-duration (let [stats @(:stats dispatcher)
                                 total (:total-dispatched stats 0)
                                 duration (:total-duration stats 0)]
                             (if (pos? total)
                               (/ duration total)
                               0))}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-dispatcher
  "Create a new EventDispatcher.

   Options:
     :id - Unique identifier (auto-generated if not provided)
     :bus - Bus instance for message routing
     :agents - Initial list of agents
     :balancer - Balancer instance (created if not provided)
     :circuit-breaker - CircuitBreaker instance (created if not provided)
     :metrics - MetricsCollector instance
     :max-queue-size - Max queue size (default: 10000)
     :dispatch-timeout-ms - Dispatch timeout (default: 5000)
     :heartbeat-interval-ms - Heartbeat check interval (default: 30000)
     :agent-timeout-ms - Agent timeout (default: 60000)

   Example:
   (create-dispatcher {:bus bus
                      :agents [agent1 agent2]
                      :metrics collector})"
  [{:keys [id bus agents balancer circuit-breaker metrics
           max-queue-size dispatch-timeout-ms heartbeat-interval-ms agent-timeout-ms]
    :or {max-queue-size 10000
         dispatch-timeout-ms 5000
         heartbeat-interval-ms 30000
         agent-timeout-ms 60000}
    :as opts}]
  (let [config (merge default-config
                      {:max-queue-size max-queue-size
                       :dispatch-timeout-ms dispatch-timeout-ms
                       :heartbeat-interval-ms heartbeat-interval-ms
                       :agent-timeout-ms agent-timeout-ms})
        disp (->EventDispatcher
              (or id (str (UUID/randomUUID)))
              bus
              (atom {})
              (or balancer
                  (balancer/create-balancer {:strategy :least-connections}))
              (or circuit-breaker
                  (cb/create-circuit-breaker {:failure-threshold 10
                                              :timeout-ms 60000
                                              :metrics metrics}))
              (create-priority-queue)
              config
              (atom :stopped)
              (atom {:messages-queued 0
                     :total-dispatched 0
                     :total-duration 0
                     :no-agent-failures 0
                     :errors 0})
              metrics
              (atom nil)
              (atom nil))]

    ;; Register initial agents
    (doseq [agent (or agents [])]
      (register-agent! disp agent))

    disp))
