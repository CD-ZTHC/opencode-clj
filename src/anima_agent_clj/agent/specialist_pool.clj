(ns anima-agent-clj.agent.specialist-pool
  "Specialist Agent Pool for domain-specific task routing.

   The SpecialistPool provides:
   - Expert registration and discovery
   - Task routing based on task type/capability
   - Load balancing across specialists
   - Health monitoring and failover

   Architecture:
   ┌─────────────────────────────────────────────────────────────┐
   │                    SpecialistPool                           │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │              Expert Registry                         │   │
   │  │  :code-analysis  → [Specialist1, Specialist2]       │   │
   │  │  :documentation  → [Specialist3]                    │   │
   │  │  :testing        → [Specialist4, Specialist5]       │   │
   │  └─────────────────────────────────────────────────────┘   │
   │                          ↓                                  │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │              Task Router                             │   │
   │  │  - Capability matching                               │   │
   │  │  - Load balancing                                    │   │
   │  │  - Health checking                                   │   │
   │  └─────────────────────────────────────────────────────┘   │
   └─────────────────────────────────────────────────────────────┘

   Usage:
   (def pool (create-specialist-pool {:worker-pool wp}))
   (register-specialist! pool {:id \"code-expert-1\"
                               :capabilities [:code-analysis :refactoring]
                               :handler my-handler-fn})
   (route-task! pool {:type :code-analysis :payload {...}})"
  (:require
   [clojure.core.async :as async]
   [anima-agent-clj.agent.worker-agent :as worker]
   [anima-agent-clj.agent.worker-pool :as pool]
   [anima-agent-clj.dispatcher.router :as router]
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Specialist Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord Specialist
           [id              ; UUID - Specialist identifier
            name            ; String - Human-readable name
            capabilities    ; Set<keyword> - What this specialist can do
            handler         ; Fn - Custom handler function (optional)
            priority        ; Integer - Higher priority specialists preferred
            max-concurrent  ; Integer - Max concurrent tasks
            current-load    ; atom<Integer> - Current task count
            status          ; atom<keyword> - :available, :busy, :offline
            metrics         ; atom<map> - Performance metrics
            metadata])      ; Map - Additional metadata

(defrecord RoutedTask
           [id              ; UUID - Task identifier
            trace-id        ; UUID - Parent trace ID
            task-spec       ; Original task specification
            specialist-id   ; UUID - Assigned specialist
            status          ; :pending, :routed, :executing, :completed, :failed
            result          ; Task result
            error           ; Error info
            routed-at       ; Date - When routed
            completed-at])  ; Date - When completed

;; ══════════════════════════════════════════════════════════════════════════════
;; Specialist Pool Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord SpecialistPool
           [id              ; UUID - Pool identifier
            worker-pool     ; WorkerPool - Fallback for non-specialized tasks
            registry        ; atom<map> - capability -> [specialist-ids]
            specialists     ; atom<map> - id -> Specialist
            config          ; Map - Pool configuration
            status          ; atom<keyword> - :stopped, :running
            metrics         ; atom<map> - Pool metrics
            metrics-collector]) ; MetricsCollector - Optional metrics collector

;; ══════════════════════════════════════════════════════════════════════════════
;; Configuration
;; ══════════════════════════════════════════════════════════════════════════════

(def default-config
  {:default-timeout 60000      ; Default task timeout
   :max-retries 2              ; Max retries on specialist failure
   :fallback-to-worker true    ; Use worker pool if no specialist
   :health-check-interval 30000 ; Health check interval in ms
   :load-balance-strategy :least-loaded}) ; :round-robin, :least-loaded, :random

;; ══════════════════════════════════════════════════════════════════════════════
;; Specialist Registration
;; ══════════════════════════════════════════════════════════════════════════════

(defn register-specialist!
  "Register a new specialist.

   Options:
     :id              - Unique identifier (auto-generated if not provided)
     :name            - Human-readable name
     :capabilities    - Set of capabilities this specialist handles
     :handler         - Optional custom handler function
     :priority        - Priority (higher = preferred, default: 0)
     :max-concurrent  - Max concurrent tasks (default: 5)
     :metadata        - Additional metadata

   Returns the created Specialist."
  [specialist-pool opts]
  (let [{:keys [id name capabilities handler priority max-concurrent metadata]
         :or {id (str (UUID/randomUUID))
              name "Unnamed Specialist"
              priority 0
              max-concurrent 5
              metadata {}}} opts
        specialist (->Specialist
                    id
                    name
                    (set capabilities)
                    handler
                    priority
                    max-concurrent
                    (atom 0)
                    (atom :available)
                    (atom {:tasks-processed 0
                           :tasks-succeeded 0
                           :tasks-failed 0
                           :total-duration 0})
                    metadata)]

    ;; Add to specialists map
    (swap! (:specialists specialist-pool) assoc id specialist)

    ;; Update capability registry
    (doseq [cap capabilities]
      (swap! (:registry specialist-pool)
             (fn [reg]
               (update reg cap (fnil conj []) id))))

    specialist))

(defn unregister-specialist!
  "Remove a specialist from the pool."
  [specialist-pool specialist-id]
  (when-let [specialist (get @(:specialists specialist-pool) specialist-id)]
    ;; Remove from specialists map
    (swap! (:specialists specialist-pool) dissoc specialist-id)

    ;; Remove from capability registry
    (doseq [cap (:capabilities specialist)]
      (swap! (:registry specialist-pool)
             (fn [reg]
               (update reg cap (fn [ids]
                                 (filterv #(not= % specialist-id) ids))))))

    specialist))

(defn get-specialist
  "Get specialist by ID."
  [specialist-pool specialist-id]
  (get @(:specialists specialist-pool) specialist-id))

(defn list-specialists
  "List all specialists, optionally filtered by capability."
  ([specialist-pool]
   (vals @(:specialists specialist-pool)))
  ([specialist-pool capability]
   (let [ids (get @(:registry specialist-pool) capability [])]
     (keep #(get-specialist specialist-pool %) ids))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Specialist Selection
;; ══════════════════════════════════════════════════════════════════════════════

(defn- get-available-specialists
  "Get specialists that can handle a task type."
  [specialist-pool task-type]
  (let [registry @(:registry specialist-pool)
        specialists @(:specialists specialist-pool)
        ids (get registry task-type [])]
    (->> ids
         (map #(get specialists %))
         (filter some?)
         (filter #(= :available @(:status %)))
         (filter #(< @(:current-load %) (:max-concurrent %))))))

(defn- select-by-strategy
  "Select a specialist using the configured strategy."
  [specialists strategy last-idx-atom]
  (when (seq specialists)
    (case strategy
      :round-robin
      (router/round-robin specialists last-idx-atom)

      :least-loaded
      (apply min-key #(or @(:current-load %) 0) specialists)

      :random
      (rand-nth specialists)

      ;; Default: highest priority, then least loaded
      (let [sorted (sort-by (juxt #(- (:priority %)) #(:current-load %))
                            specialists)]
        (first sorted)))))

(defn- select-specialist
  "Select the best specialist for a task."
  [specialist-pool task-type]
  (let [config (:config specialist-pool)
        strategy (get config :load-balance-strategy :least-loaded)
        available (get-available-specialists specialist-pool task-type)]
    (select-by-strategy available strategy (atom 0))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Routing
;; ══════════════════════════════════════════════════════════════════════════════

(declare execute-with-specialist!)

(defn- execute-with-handler
  "Execute task using specialist's custom handler."
  [specialist task-spec result-ch]
  (async/go
    (try
      (let [start-time (System/currentTimeMillis)
            result ((:handler specialist) task-spec)
            end-time (System/currentTimeMillis)]
        (swap! (:metrics specialist)
               (fn [m]
                 (-> m
                     (update :tasks-processed inc)
                     (update :tasks-succeeded inc)
                     (update :total-duration + (- end-time start-time)))))
        (async/>! result-ch {:status :success :result result}))
      (catch Exception e
        (swap! (:metrics specialist) update :tasks-failed inc)
        (async/>! result-ch {:status :failure :error (.getMessage e)}))
      (finally
        (swap! (:current-load specialist) dec)
        (when (= @(:current-load specialist) 0)
          (reset! (:status specialist) :available))))))

(defn- execute-with-worker-pool
  "Execute task using the worker pool."
  [specialist-pool task-spec result-ch]
  (async/go
    (if-let [wp (:worker-pool specialist-pool)]
      (let [task (worker/make-task task-spec)
            worker-result (async/<! (pool/submit-task! wp task))]
        (if worker-result
          (async/>! result-ch worker-result)
          (async/>! result-ch {:status :failure :error "No worker available"})))
      (async/>! result-ch {:status :failure :error "No worker pool available"}))))

(defn route-task!
  "Route a task to an appropriate specialist.

   Returns a channel that will receive the task result.

   Options in task-spec:
     :type        - Task type (required for routing)
     :timeout     - Task timeout in ms
     :fallback    - Allow fallback to worker pool (default: true)

   Example:
   (route-task! pool {:type :code-analysis
                      :payload {:code \"...\"}})"
  [specialist-pool task-spec]
  (let [config (:config specialist-pool)
        mc (:metrics-collector specialist-pool)
        task-type (:type task-spec)
        timeout-ms (or (:timeout task-spec)
                       (get config :default-timeout 60000))
        result-ch (async/promise-chan)
        routed-task (->RoutedTask
                     (str (UUID/randomUUID))
                     (or (:trace-id task-spec) (str (UUID/randomUUID)))
                     task-spec
                     nil
                     :pending
                     nil
                     nil
                     (Date.)
                     nil)]

    (when mc (metrics/counter-inc! mc "tasks_routed"))

    (if-let [specialist (select-specialist specialist-pool task-type)]
      ;; Found a specialist
      (do
        (reset! (:status specialist) :busy)
        (swap! (:current-load specialist) inc)
        (assoc routed-task :specialist-id (:id specialist) :status :routed)

        (if (:handler specialist)
          ;; Use custom handler
          (execute-with-handler specialist task-spec result-ch)
          ;; Use worker pool with specialist context
          (execute-with-worker-pool specialist-pool task-spec result-ch)))

      ;; No specialist found
      (if (and (get config :fallback-to-worker true)
               (:fallback task-spec true))
        ;; Fallback to worker pool
        (execute-with-worker-pool specialist-pool task-spec result-ch)
        ;; No fallback, return error
        (async/put! result-ch
                    {:status :failure
                     :error (str "No specialist available for task type: " task-type)})))

    result-ch))

(defn route-task-async!
  "Route task asynchronously with callback."
  [specialist-pool task-spec callback]
  (async/go
    (when-let [result (async/<! (route-task! specialist-pool task-spec))]
      (callback result))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Batch Routing
;; ══════════════════════════════════════════════════════════════════════════════

(defn route-batch!
  "Route multiple tasks in parallel.

   Returns a map of task-id -> result-channel.

   Example:
   (route-batch! pool [{:type :code-analysis :payload {...}}
                       {:type :documentation :payload {...}}])"
  [specialist-pool task-specs]
  (let [result-chs (mapv #(route-task! specialist-pool %) task-specs)]
    (into {}
          (map-indexed (fn [idx ch]
                        [(str "task-" idx) ch])
                      result-chs))))

(defn collect-batch-results
  "Collect results from a batch of routed tasks.

   Returns a vector of results in order."
  [batch-map timeout-ms]
  (let [timeout-ch (async/timeout timeout-ms)]
    (async/go
      (loop [remaining (vals batch-map)
             results []]
        (if (empty? remaining)
          results
          (let [[result port] (async/alts! (conj remaining timeout-ch))]
            (cond
              (= port timeout-ch)
              (into results (repeat (count remaining) {:status :timeout}))

              (some? result)
              (recur (rest remaining) (conj results result))

              :else
              (recur (rest remaining)
                     (conj results {:status :failure :error "Channel closed"})))))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Health Monitoring
;; ══════════════════════════════════════════════════════════════════════════════

(defn- check-specialist-health
  "Check health of a single specialist."
  [specialist]
  (let [status @(:status specialist)
        load @(:current-load specialist)
        metrics @(:metrics specialist)]
    {:id (:id specialist)
     :name (:name specialist)
     :status status
     :load load
     :max-concurrent (:max-concurrent specialist)
     :available (< load (:max-concurrent specialist))
     :metrics metrics}))

(defn health-check
  "Perform health check on all specialists."
  [specialist-pool]
  (let [specialists (vals @(:specialists specialist-pool))
        health-reports (map check-specialist-health specialists)]
    {:total (count specialists)
     :available (count (filter :available health-reports))
     :busy (count (filter #(= :busy (:status %)) health-reports))
     :offline (count (filter #(= :offline (:status %)) health-reports))
     :specialists health-reports}))

(defn set-specialist-status!
  "Manually set specialist status."
  [specialist-pool specialist-id status]
  (when-let [specialist (get-specialist specialist-pool specialist-id)]
    (reset! (:status specialist) status)
    specialist))

;; ══════════════════════════════════════════════════════════════════════════════
;; Pool Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(declare health-check-loop)

(defn start-specialist-pool!
  "Start the specialist pool."
  [specialist-pool]
  (when (= :stopped @(:status specialist-pool))
    (reset! (:status specialist-pool) :running)
    ;; Ensure worker pool is started
    (when-let [wp (:worker-pool specialist-pool)]
      (when (= :stopped @(:status wp))
        (pool/start-pool! wp))))
  specialist-pool)

(defn stop-specialist-pool!
  "Stop the specialist pool."
  [specialist-pool]
  (reset! (:status specialist-pool) :stopped)
  specialist-pool)

;; ══════════════════════════════════════════════════════════════════════════════
;; Status & Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn specialist-pool-status
  "Get specialist pool status."
  [specialist-pool]
  {:id (:id specialist-pool)
   :status @(:status specialist-pool)
   :specialist-count (count @(:specialists specialist-pool))
   :capability-count (count @(:registry specialist-pool))
   :config (:config specialist-pool)
   :metrics @(:metrics specialist-pool)})

(defn specialist-pool-metrics
  "Get aggregated metrics from all specialists."
  [specialist-pool]
  (let [specialists (vals @(:specialists specialist-pool))]
    (reduce
     (fn [acc specialist]
       (let [m @(:metrics specialist)]
         (-> acc
             (update :total-processed + (:tasks-processed m 0))
             (update :total-succeeded + (:tasks-succeeded m 0))
             (update :total-failed + (:tasks-failed m 0))
             (update :total-duration + (:total-duration m 0)))))
     {:total-processed 0
      :total-succeeded 0
      :total-failed 0
      :total-duration 0
      :specialist-count (count specialists)}
     specialists)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Capability Management
;; ══════════════════════════════════════════════════════════════════════════════

(defn list-capabilities
  "List all registered capabilities."
  [specialist-pool]
  (keys @(:registry specialist-pool)))

(defn get-specialists-for-capability
  "Get all specialists that have a specific capability."
  [specialist-pool capability]
  (let [ids (get @(:registry specialist-pool) capability [])]
    (keep #(get-specialist specialist-pool %) ids)))

(defn add-capability!
  "Add a capability to an existing specialist."
  [specialist-pool specialist-id capability]
  (when-let [specialist (get-specialist specialist-pool specialist-id)]
    (when-not (contains? (:capabilities specialist) capability)
      ;; Add to specialist
      (swap! (:specialists specialist-pool)
             (fn [specs]
               (update specs specialist-id
                       (fn [s]
                         (update s :capabilities conj capability)))))
      ;; Add to registry
      (swap! (:registry specialist-pool)
             (fn [reg]
               (update reg capability (fnil conj []) specialist-id))))
    specialist))

(defn remove-capability!
  "Remove a capability from a specialist."
  [specialist-pool specialist-id capability]
  (when-let [specialist (get-specialist specialist-pool specialist-id)]
    (when (contains? (:capabilities specialist) capability)
      ;; Remove from specialist
      (swap! (:specialists specialist-pool)
             (fn [specs]
               (update specs specialist-id
                       (fn [s]
                         (assoc s :capabilities
                                (disj (:capabilities s) capability))))))
      ;; Remove from registry
      (swap! (:registry specialist-pool)
             (fn [reg]
               (update reg capability
                       (fn [ids]
                         (filterv #(not= % specialist-id) ids))))))
    specialist))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-specialist-pool
  "Create a new SpecialistPool.

   Options:
     :worker-pool            - WorkerPool instance for fallback (optional)
     :default-timeout        - Default task timeout (default: 60000)
     :max-retries            - Max retries on failure (default: 2)
     :fallback-to-worker     - Use worker pool if no specialist (default: true)
     :health-check-interval  - Health check interval in ms (default: 30000)
     :load-balance-strategy  - Strategy: :round-robin, :least-loaded, :random
     :metrics-collector      - Optional MetricsCollector instance"
  [{:keys [worker-pool default-timeout max-retries fallback-to-worker
           health-check-interval load-balance-strategy metrics-collector]
    :or {default-timeout 60000
         max-retries 2
         fallback-to-worker true
         health-check-interval 30000
         load-balance-strategy :least-loaded}}]
  (->SpecialistPool
   (str (UUID/randomUUID))
   worker-pool
   (atom {})
   (atom {})
   (merge default-config
          {:default-timeout default-timeout
           :max-retries max-retries
           :fallback-to-worker fallback-to-worker
           :health-check-interval health-check-interval
           :load-balance-strategy load-balance-strategy})
   (atom :stopped)
   (atom {:tasks-routed 0
          :tasks-completed 0
          :tasks-failed 0})
   metrics-collector))
