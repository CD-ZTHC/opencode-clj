(ns anima-agent-clj.agent.parallel-pool
  "Parallel Agent Pool for concurrent task execution.

   The ParallelPool provides:
   - Concurrent execution of independent tasks
   - Fault isolation (one task failure doesn't affect others)
   - Timeout control and partial success handling
   - Integration with existing WorkerPool

   Architecture:
   ┌─────────────────────────────────────────────────────────────┐
   │                      ParallelPool                           │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │              Task Dispatcher                         │   │
   │  │  ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐           │   │
   │  │  │Task 1 │ │Task 2 │ │Task 3 │ │Task N │           │   │
   │  │  └───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘           │   │
   │  └──────┼─────────┼─────────┼─────────┼────────────────┘   │
   │         ↓         ↓         ↓         ↓                   │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │              Result Collector                        │   │
   │  │  [Result1] [Result2] [Error3] [ResultN]             │   │
   │  └─────────────────────────────────────────────────────┘   │
   └─────────────────────────────────────────────────────────────┘

   Usage:
   (def pool (create-parallel-pool {:worker-pool wp}))
   (let [results (execute-parallel! pool tasks)]
     ... handle results ...)"
  (:require
   [clojure.core.async :as async]
   [anima-agent-clj.agent.worker-agent :as worker]
   [anima-agent-clj.agent.worker-pool :as pool]
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Parallel Execution Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ParallelTask
           [id              ; UUID - Task identifier
            trace-id        ; UUID - Parent trace ID
            task-spec       ; Worker task specification
            status          ; :pending, :running, :completed, :failed, :timeout
            result          ; Task result (on success)
            error           ; Error info (on failure)
            started-at      ; Date - When execution started
            completed-at    ; Date - When execution completed
            worker-id])     ; UUID - Worker that processed this

(defrecord ParallelResult
           [id              ; UUID - Result batch identifier
            trace-id        ; UUID - Parent trace ID
            total           ; Integer - Total tasks
            successful      ; Integer - Successful count
            failed          ; Integer - Failed count
            timed-out       ; Integer - Timeout count
            results         ; Vector<ParallelTask> - All task results
            duration        ; ms - Total execution time
            status          ; :success, :partial, :failure
            completed-at])  ; Date

;; ══════════════════════════════════════════════════════════════════════════════
;; Parallel Pool Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ParallelPool
           [id              ; UUID - Pool identifier
            worker-pool     ; WorkerPool - Underlying worker pool
            config          ; Map - Pool configuration
            status          ; atom<keyword> - :stopped, :running
            metrics         ; atom<map> - Performance metrics
            metrics-collector]) ; MetricsCollector - Optional metrics collector

;; ══════════════════════════════════════════════════════════════════════════════
;; Configuration
;; ══════════════════════════════════════════════════════════════════════════════

(def default-config
  {:max-concurrent 10        ; Maximum concurrent tasks
   :default-timeout 60000    ; Default timeout in ms
   :fail-fast false          ; Stop on first failure
   :min-success-ratio 0.5})  ; Minimum success ratio for :success status

;; ══════════════════════════════════════════════════════════════════════════════
;; Parallel Task Creation
;; ══════════════════════════════════════════════════════════════════════════════

(defn make-parallel-task
  "Create a ParallelTask from a task specification."
  [{:keys [task-spec trace-id]}]
  (->ParallelTask
   (str (UUID/randomUUID))
   (or trace-id (str (UUID/randomUUID)))
   task-spec
   :pending
   nil
   nil
   nil
   nil
   nil))

(defn- wrap-worker-task
  "Wrap a task spec into a worker task with result channel."
  [parallel-task timeout-ms]
  (let [task (worker/make-task
              (merge (:task-spec parallel-task)
                     {:trace-id (:trace-id parallel-task)
                      :timeout timeout-ms}))]
    (assoc task :parallel-id (:id parallel-task))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Parallel Execution
;; ══════════════════════════════════════════════════════════════════════════════

(defn- execute-single!
  "Execute a single parallel task through the worker pool."
  [parallel-pool parallel-task timeout-ms]
  (let [wp (:worker-pool parallel-pool)
        worker-task (wrap-worker-task parallel-task timeout-ms)
        result-ch (async/promise-chan)]
    (async/go
      (let [start-time (System/currentTimeMillis)
            worker-result (async/<! (pool/submit-task! wp worker-task))
            end-time (System/currentTimeMillis)]
        (if worker-result
          (let [status (:status worker-result)]
            (async/>! result-ch
                      (assoc parallel-task
                             :status status
                             :result (:result worker-result)
                             :error (:error worker-result)
                             :started-at (Date. start-time)
                             :completed-at (Date. end-time)
                             :worker-id (:worker-id worker-result))))
          (async/>! result-ch
                    (assoc parallel-task
                           :status :failed
                           :error "No result from worker"
                           :started-at (Date. start-time)
                           :completed-at (Date. end-time))))))
    result-ch))

(defn- collect-results
  "Collect results from parallel execution channels."
  [result-chs timeout-ms]
  (let [timeout-ch (async/timeout timeout-ms)]
    (async/<!!
     (async/go
       (loop [remaining (vec result-chs)
              collected []]
         (if (empty? remaining)
           collected
           (let [[result port] (async/alts! (conj remaining timeout-ch))]
             (cond
               (= port timeout-ch)
               (into collected
                     (for [_ (filter #(not= % timeout-ch) remaining)]
                       {:status :timeout :error "Timeout"}))

               (some? result)
               (recur (vec (remove #(= % port) remaining))
                      (conj collected result))

               :else
               (recur (vec (remove #(= % port) remaining)) collected)))))))))

(defn- compute-result-status
  "Compute overall status based on task results."
  [results min-success-ratio]
  (let [total (count results)
        successful (count (filter #(= :success (:status %)) results))
        ratio (if (pos? total) (/ successful total) 0)]
    (cond
      (= successful total) :success
      (>= ratio min-success-ratio) :partial
      :else :failure)))

(defn execute-parallel!
  "Execute multiple tasks in parallel.

   Returns a ParallelResult containing all task results.

   Options (in task-specs):
     :timeout         - Per-task timeout in ms (default: from config)
     :trace-id        - Parent trace ID for correlation

   Example:
   (execute-parallel! pool
     [{:type :api-call :payload {...}}
      {:type :transform :payload {...}}])"
  [parallel-pool task-specs]
  (let [config (:config parallel-pool)
        mc (:metrics-collector parallel-pool)
        timeout-ms (get config :default-timeout 60000)
        trace-id (str (UUID/randomUUID))
        start-time (System/currentTimeMillis)]

    (when mc (metrics/counter-inc! mc "parallel_tasks_submitted"))

    ;; Create parallel tasks
    (let [parallel-tasks (mapv #(make-parallel-task
                                 {:task-spec %
                                  :trace-id trace-id})
                               task-specs)
          ;; Execute all tasks
          result-chs (mapv #(execute-single! parallel-pool % timeout-ms)
                           parallel-tasks)
          ;; Collect results with overall timeout
          overall-timeout (* timeout-ms 1.5) ; Allow some buffer
          results (collect-results result-chs overall-timeout)]

      (let [end-time (System/currentTimeMillis)
            successful (count (filter #(= :success (:status %)) results))
            failed (count (filter #(= :failed (:status %)) results))
            timed-out (count (filter #(= :timeout (:status %)) results))
            status (compute-result-status results
                                          (get config :min-success-ratio 0.5))]

        ;; Update metrics
        (when mc
          (metrics/counter-add! mc "parallel_tasks_completed" successful)
          (metrics/counter-add! mc "parallel_tasks_failed" (+ failed timed-out))
          (metrics/histogram-record! mc "parallel_duration"
                                     (- end-time start-time)))

        (swap! (:metrics parallel-pool)
               (fn [m]
                 (-> m
                     (update :batches-executed inc)
                     (update :total-tasks + (count results))
                     (update :successful-tasks + successful)
                     (update :failed-tasks + failed)
                     (update :timed-out-tasks + timed-out))))

        (->ParallelResult
         (str (UUID/randomUUID))
         trace-id
         (count task-specs)
         successful
         failed
         timed-out
         results
         (- end-time start-time)
         status
         (Date.))))))

(defn execute-parallel-async!
  "Execute tasks in parallel, returning a channel for the result."
  [parallel-pool task-specs]
  (async/go
    (execute-parallel! parallel-pool task-specs)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Batch Operations
;; ══════════════════════════════════════════════════════════════════════════════

(defn execute-batch!
  "Execute a batch of tasks with batch size control.

   Splits tasks into batches and executes each batch in parallel.
   Useful for large task sets that would overwhelm the worker pool.

   Options:
     :batch-size     - Tasks per batch (default: 10)
     :batch-delay-ms - Delay between batches (default: 100)"
  [parallel-pool task-specs opts]
  (let [batch-size (get opts :batch-size 10)
        batch-delay-ms (get opts :batch-delay-ms 100)
        batches (partition-all batch-size task-specs)
        trace-id (str (UUID/randomUUID))
        start-time (System/currentTimeMillis)]
    (async/go
      (loop [remaining batches
             all-results []]
        (if (empty? remaining)
          (let [end-time (System/currentTimeMillis)
                all-tasks (mapcat :results all-results)]
            (->ParallelResult
             (str (UUID/randomUUID))
             trace-id
             (count task-specs)
             (count (filter #(= :success (:status %)) all-tasks))
             (count (filter #(= :failed (:status %)) all-tasks))
             (count (filter #(= :timeout (:status %)) all-tasks))
             all-tasks
             (- end-time start-time)
             (compute-result-status all-tasks 0.5)
             (Date.)))
          (do
            (when (pos? batch-delay-ms)
              (async/<! (async/timeout batch-delay-ms)))
            (let [batch-result (execute-parallel! parallel-pool (first remaining))]
              (recur (rest remaining)
                     (conj all-results batch-result)))))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Map-Reduce Pattern
;; ══════════════════════════════════════════════════════════════════════════════

(defn map-parallel!
  "Apply a function to each item in parallel (map pattern).

   Creates a task for each item using the task-fn, then executes
   all tasks in parallel.

   Example:
   (map-parallel! pool
                 (fn [item] {:type :transform :payload {:data item}})
                 [1 2 3 4 5])"
  [parallel-pool task-fn items]
  (let [task-specs (mapv task-fn items)]
    (execute-parallel! parallel-pool task-specs)))

(defn map-reduce!
  "Map-reduce pattern: map items to tasks, then reduce results.

   Example:
   (map-reduce! pool
                (fn [n] {:type :transform :payload {:data (* n 2)}})
                (fn [results] (reduce + (map :result results)))
                [1 2 3 4 5])"
  [parallel-pool task-fn reduce-fn items]
  (async/go
    (let [result (execute-parallel! parallel-pool (mapv task-fn items))]
      (if (= :success (:status result))
        {:status :success
         :result (reduce-fn (filter #(= :success (:status %)) (:results result)))}
        {:status :failure
         :error "Map phase failed"
         :partial-result result}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Pool Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-parallel-pool!
  "Start the parallel pool.
   Ensures the underlying worker pool is running."
  [parallel-pool]
  (when (= :stopped @(:status parallel-pool))
    (reset! (:status parallel-pool) :running)
    ;; Ensure worker pool is started
    (when-let [wp (:worker-pool parallel-pool)]
      (when (= :stopped @(:status wp))
        (pool/start-pool! wp))))
  parallel-pool)

(defn stop-parallel-pool!
  "Stop the parallel pool.
   Note: Does not stop the underlying worker pool."
  [parallel-pool]
  (reset! (:status parallel-pool) :stopped)
  parallel-pool)

;; ══════════════════════════════════════════════════════════════════════════════
;; Status & Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn parallel-pool-status
  "Get parallel pool status."
  [parallel-pool]
  {:id (:id parallel-pool)
   :status @(:status parallel-pool)
   :config (:config parallel-pool)
   :metrics @(:metrics parallel-pool)})

(defn parallel-pool-metrics
  "Get parallel pool performance metrics."
  [parallel-pool]
  (let [base @(:metrics parallel-pool)
        total (:total-tasks base 0)
        successful (:successful-tasks base 0)]
    (merge base
           {:success-rate (if (pos? total)
                           (double (/ successful total))
                           0.0)})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-parallel-pool
  "Create a new ParallelPool.

   Options:
     :worker-pool        - WorkerPool instance (required)
     :max-concurrent     - Maximum concurrent tasks (default: 10)
     :default-timeout    - Default timeout in ms (default: 60000)
     :fail-fast          - Stop on first failure (default: false)
     :min-success-ratio  - Minimum success ratio for :success status (default: 0.5)
     :metrics-collector  - Optional MetricsCollector instance"
  [{:keys [worker-pool max-concurrent default-timeout fail-fast
           min-success-ratio metrics-collector]
    :or {max-concurrent 10
         default-timeout 60000
         fail-fast false
         min-success-ratio 0.5}}]
  (when-not worker-pool
    (throw (ex-info "Worker pool is required" {})))
  (->ParallelPool
   (str (UUID/randomUUID))
   worker-pool
   (merge default-config
          {:max-concurrent max-concurrent
           :default-timeout default-timeout
           :fail-fast fail-fast
           :min-success-ratio min-success-ratio})
   (atom :stopped)
   (atom {:batches-executed 0
          :total-tasks 0
          :successful-tasks 0
          :failed-tasks 0
          :timed-out-tasks 0})
   metrics-collector))
