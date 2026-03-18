(ns anima-agent-clj.agent.worker-pool
  "Worker Pool for managing a group of WorkerAgents.

   The WorkerPool provides:
   - Worker lifecycle management (creation, start, stop)
   - Dynamic scaling (add/remove workers)
   - Load balancing across workers
   - Health monitoring
   - Task distribution with retry logic

   Phase 3: Added metrics integration for monitoring

   Usage:
   (def pool (create-pool {:opencode-client client :size 4}))
   (start-pool! pool)
   (let [result (submit-task! pool task)]
     ... handle result ...)
   (stop-pool! pool)"
  (:require
   [clojure.core.async :as async]
   [anima-agent-clj.agent.worker-agent :as worker]
   [anima-agent-clj.dispatcher.router :as router]
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Pool Configuration
;; ══════════════════════════════════════════════════════════════════════════════

(def default-config
  {:min-size 1
   :max-size 10
   :initial-size 2
   :default-timeout-ms 60000
   :health-check-interval-ms 30000
   :scaling-cooldown-ms 5000
   :task-queue-size 100})

;; ══════════════════════════════════════════════════════════════════════════════
;; Worker Pool Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord WorkerPool
           [id ; UUID - Pool identifier
            opencode-client ; Map - OpenCode client config
            config ; Map - Pool configuration
            workers ; atom<map> - Worker ID -> WorkerAgent
            worker-ids ; atom<vector> - Ordered list for round-robin
            last-idx ; atom<int> - Last used index for round-robin
            status ; atom<keyword> - :stopped, :running, :scaling
            pool-metrics ; atom<map> - Pool metrics
            health-loop ; atom - Health check loop
            metrics-collector]) ; MetricsCollector - Optional metrics collector

;; ══════════════════════════════════════════════════════════════════════════════
;; Worker Management
;; ══════════════════════════════════════════════════════════════════════════════

(defn- add-worker!
  "Add a new worker to the pool. Returns the worker."
  [pool]
  (let [w (worker/create-worker
           {:opencode-client (:opencode-client pool)
            :timeout-ms (get-in pool [:config :default-timeout-ms])})
        worker-id (:id w)]
    (swap! (:workers pool) assoc worker-id w)
    (swap! (:worker-ids pool) conj worker-id)
    w))

(defn pool-size
  "Get current pool size."
  [pool]
  (count @(:workers pool)))

(defn scale-to
  "Scale pool to target size."
  [pool target-size]
  (let [current (pool-size pool)
        config (:config pool)
        min-size (:min-size config)
        max-size (:max-size config)
        target (max min-size (min max-size target-size))]
    (cond
      (> current target)
      ;; Scale down - remove excess workers
      (let [to-remove (- current target)
            workers-to-remove (take to-remove (reverse @(:worker-ids pool)))]
        (doseq [wid workers-to-remove]
          (when-let [w (get @(:workers pool) wid)]
            (worker/stop-worker! w)
            (swap! (:workers pool) dissoc wid)
            (swap! (:worker-ids pool)
                   (fn [ids] (filterv #(not= % wid) ids))))))

      (< current target)
      ;; Scale up - add new workers
      (let [to-add (- target current)]
        (dotimes [_ to-add]
          (let [w (add-worker! pool)]
            (worker/start-worker! w)))))

    (swap! (:pool-metrics pool) assoc :target-size target)
    target))

;; ══════════════════════════════════════════════════════════════════════════════
;; Health Check
;; ══════════════════════════════════════════════════════════════════════════════

(defn- health-check-loop-fn
  "Periodic health check for workers."
  [pool interval-ms]
  (async/go-loop []
    (when (= :running @(:status pool))
      (async/<! (async/timeout interval-ms))
      (doseq [[_id w] @(:workers pool)]
        (let [st @(:status w)]
          (when (= :error st)
            ;; Try to recover error worker
            (worker/stop-worker! w)
            (worker/start-worker! w))))
      ;; Update metrics gauges
      (when-let [mc (:metrics-collector pool)]
        (let [workers (vals @(:workers pool))
              active (count (filter #(= :busy @(:status %)) workers))
              idle (count (filter #(= :idle @(:status %)) workers))]
          (metrics/update-worker-gauges! mc active idle)))
      (recur))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Pool Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-pool!
  "Start the worker pool.
   Initializes workers and begins health monitoring.
   Returns the pool."
  [pool]
  (when (= :stopped @(:status pool))
    (reset! (:status pool) :running)

    ;; Start initial workers
    (let [initial-size (get-in pool [:config :initial-size])]
      (dotimes [_ initial-size]
        (let [w (add-worker! pool)]
          (worker/start-worker! w))))

    ;; Start health check loop
    (reset! (:health-loop pool)
            (health-check-loop-fn pool
                                  (get-in pool [:config :health-check-interval-ms]))))
  pool)

(defn stop-pool!
  "Stop the worker pool gracefully.
   Stops all workers and cleans up resources.
   Returns the pool."
  [pool]
  (when (= :running @(:status pool))
    (reset! (:status pool) :stopping)

    ;; Stop health check loop
    (when-let [hl @(:health-loop pool)]
      (async/close! hl)
      (reset! (:health-loop pool) nil))

    ;; Stop all workers
    (doseq [[_id w] @(:workers pool)]
      (worker/stop-worker! w))

    ;; Clear workers
    (reset! (:workers pool) {})
    (reset! (:worker-ids pool) [])

    (reset! (:status pool) :stopped))
  pool)

(defn restart-pool!
  "Restart the worker pool.
   Returns the pool."
  [pool]
  (stop-pool! pool)
  (start-pool! pool))

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Submission
;; ══════════════════════════════════════════════════════════════════════════════

(defn- get-available-worker
  "Get an available worker using round-robin strategy."
  [pool]
  (let [workers-vec (vec (vals @(:workers pool)))
        idle-workers (filter #(= :idle @(:status %)) workers-vec)]
    (when (seq idle-workers)
      (router/round-robin idle-workers (:last-idx pool)))))

(defn submit-task!
  "Submit a task to the pool.
   Finds an available worker and dispatches the task.
   Returns a channel that will receive the TaskResult.

   If no worker is immediately available, waits up to :max-wait-ms
   before returning nil."
  [pool task]
  (let [max-wait-ms (or (get-in pool [:config :max-wait-ms]) 5000)
        result-ch (async/promise-chan)
        start-time (System/currentTimeMillis)
        mc (:metrics-collector pool)]
    ;; Increment task counter
    (when mc (metrics/counter-inc! mc "tasks_submitted"))
    (swap! (:pool-metrics pool) update :tasks-dispatched inc)
    (async/go
      (loop [waited 0]
        (if-let [w (get-available-worker pool)]
          ;; Found worker, submit task
          (let [worker-result-ch (worker/submit-task! w task)]
            (when-let [result (async/<! worker-result-ch)]
              ;; Record metrics
              (let [duration (- (System/currentTimeMillis) start-time)]
                (when mc (metrics/histogram-record! mc "task_duration" duration))
                (if (= :success (:status result))
                  (when mc (metrics/counter-inc! mc "tasks_completed"))
                  (when mc (metrics/counter-inc! mc "tasks_failed"))))
              (async/>! result-ch result)))
          ;; No worker available
          (if (< waited max-wait-ms)
            (do
              (async/<! (async/timeout 100))
              (recur (+ waited 100)))
            ;; Timeout, return failure
            (do
              (when mc (metrics/counter-inc! mc "tasks_failed"))
              (async/>! result-ch
                        (worker/make-task-result
                         {:task-id (:id task)
                          :trace-id (:trace-id task)
                          :status :failure
                          :error "No available worker"
                          :duration waited
                          :worker-id nil})))))))
    result-ch))

;; ══════════════════════════════════════════════════════════════════════════════
;; Scaling Operations
;; ══════════════════════════════════════════════════════════════════════════════

(defn scale-up!
  "Add workers to the pool.
   Returns new pool size."
  [pool n]
  (let [current (pool-size pool)
        target (+ current n)]
    (scale-to pool target)))

(defn scale-down!
  "Remove workers from the pool.
   Returns new pool size."
  [pool n]
  (let [current (pool-size pool)
        target (- current n)]
    (scale-to pool target)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Pool Status & Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn pool-status
  "Get current pool status."
  [pool]
  {:id (:id pool)
   :status @(:status pool)
   :size (pool-size pool)
   :config (:config pool)
   :workers (into {}
                  (for [[id w] @(:workers pool)]
                    [id (worker/worker-status w)]))
   :metrics @(:pool-metrics pool)})

(defn pool-metrics
  "Get pool performance metrics."
  [pool]
  (let [base-metrics @(:pool-metrics pool)
        worker-metrics (for [[_ w] @(:workers pool)]
                         @(:metrics w))]
    (merge base-metrics
           {:total-tasks-completed (reduce + (map :tasks-completed worker-metrics))
            :total-timeouts (reduce + (map :timeouts worker-metrics))
            :total-errors (reduce + (map :errors worker-metrics))
            :avg-task-duration (if (seq worker-metrics)
                                 (/ (reduce + (map :total-duration worker-metrics))
                                    (max 1 (reduce + (map :tasks-completed worker-metrics))))
                                 0)})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-pool
  "Create a new WorkerPool.

   Options:
     :opencode-client    - OpenCode client map {:base-url \"...\"} (required)
     :min-size           - Minimum workers (default: 1)
     :max-size           - Maximum workers (default: 10)
     :initial-size       - Starting number of workers (default: 2)
     :default-timeout-ms - Default task timeout (default: 60000)
     :max-wait-ms        - Max wait time for available worker (default: 5000)
     :metrics-collector  - Optional MetricsCollector instance (Phase 3)"
  [{:keys [opencode-client min-size max-size initial-size
           default-timeout-ms max-wait-ms metrics-collector]
    :or {min-size 1
         max-size 10
         initial-size 2
         default-timeout-ms 60000
         max-wait-ms 5000}}]
  (let [config (merge default-config
                      {:min-size min-size
                       :max-size max-size
                       :initial-size initial-size
                       :default-timeout-ms default-timeout-ms
                       :max-wait-ms max-wait-ms})]
    (->WorkerPool
     (str (UUID/randomUUID))
     opencode-client
     config
     (atom {})
     (atom [])
     (atom 0)
     (atom :stopped)
     (atom {:tasks-dispatched 0
            :tasks-queued 0
            :target-size initial-size})
     (atom nil)
     metrics-collector)))
