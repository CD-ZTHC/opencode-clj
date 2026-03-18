(ns anima-agent-clj.agent.worker-agent
  "Lightweight Worker Agent for executing atomic tasks.

   WorkerAgent is a simple execution unit that:
   - Receives atomic tasks from CoreAgent or WorkerPool
   - Executes the task (e.g., API calls, computations)
   - Reports status and results back

   Worker States:
   - :idle      - Available for new tasks
   - :busy      - Currently executing a task
   - :error     - In error state (recoverable)
   - :stopped   - Worker has been stopped"
  (:require
   [clojure.core.async :as async]
   [anima-agent-clj.messages :as messages]
   [anima-agent-clj.sessions :as sessions])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Definition
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord Task
           [id              ; UUID - Unique task identifier
            trace-id        ; UUID - Link to parent task chain
            type            ; Keyword - :api-call, :computation, :transform, :query
            payload         ; Map - Task-specific data
            priority        ; Integer (0-9)
            timeout         ; ms - Task timeout
            created-at      ; Date
            metadata        ; Map - Additional context
            result-ch])     ; Channel - Where to send result

(defrecord TaskResult
           [task-id         ; UUID - Original task ID
            trace-id        ; UUID - Trace chain ID
            status          ; Keyword - :success, :failure, :timeout
            result          ; Any - Task result (on success)
            error           ; String/Exception - Error info (on failure)
            duration        ; ms - Execution time
            completed-at    ; Date
            worker-id])     ; UUID - Worker that processed this

;; ══════════════════════════════════════════════════════════════════════════════
;; Worker Agent Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord WorkerAgent
           [id              ; UUID - Unique worker identifier
            opencode-client ; Map - OpenCode API client config
            status          ; atom<keyword> - Current status
            current-task    ; atom<Task|nil> - Task being processed
            metrics         ; atom<map> - Performance metrics
            task-chan       ; Channel - Receives tasks
            control-chan    ; Channel - Control signals
            worker-loop     ; atom - Holds the go-loop
            timeout-ms])    ; Default timeout in ms

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Constructors
;; ══════════════════════════════════════════════════════════════════════════════

(defn make-task
  "Create a new Task with defaults."
  [{:keys [type payload trace-id priority timeout metadata]
    :or {priority 5
         timeout 60000
         metadata {}}}]
  (->Task
   (str (UUID/randomUUID))
   (or trace-id (str (UUID/randomUUID)))
   type
   payload
   priority
   timeout
   (Date.)
   metadata
   nil))

(defn make-task-result
  "Create a TaskResult."
  [{:keys [task-id trace-id status result error duration worker-id]}]
  (->TaskResult
   task-id
   trace-id
   status
   result
   error
   duration
   (Date.)
   worker-id))

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Execution
;; ══════════════════════════════════════════════════════════════════════════════

(defmulti execute-task
  "Execute a task based on its type.
   Dispatches on :type field of the task."
  (fn [_worker task] (:type task)))

(defmethod execute-task :default
  [_worker task]
  {:status :failure
   :error (str "Unknown task type: " (:type task))})

;; Execute an API call task.
;; Payload: {:opencode-session-id, :content}
(defmethod execute-task :api-call
  [worker task]
  (let [{:keys [opencode-client timeout-ms]} worker
        {:keys [opencode-session-id content]} (:payload task)
        timeout (or (:timeout task) timeout-ms)]
    (try
      (if (and opencode-session-id content)
        (let [result (messages/send-prompt opencode-client opencode-session-id content)]
          {:status :success
           :result result})
        {:status :failure
         :error "Missing required fields: opencode-session-id or content"})
      (catch Exception e
        {:status :failure
         :error (.getMessage e)}))))

;; Create a new OpenCode session.
;; Payload: {}
(defmethod execute-task :session-create
  [worker task]
  (let [{:keys [opencode-client]} worker]
    (try
      (let [result (sessions/create-session opencode-client)]
        (if-let [session-id (:id result)]
          {:status :success
           :result {:opencode-session-id session-id}}
          {:status :failure
           :error "Failed to create session: no ID returned"}))
      (catch Exception e
        {:status :failure
         :error (.getMessage e)}))))

;; Transform/parse data.
;; Payload: {:data, :transform-fn (keyword or fn)}
(defmethod execute-task :transform
  [_worker task]
  (let [{:keys [data transform-fn]} (:payload task)]
    (try
      (let [result (cond
                     (fn? transform-fn) (transform-fn data)
                     (keyword? transform-fn) ((resolve transform-fn) data)
                     :else data)]
        {:status :success
         :result result})
      (catch Exception e
        {:status :failure
         :error (.getMessage e)}))))

;; Query data from memory/context.
;; Payload: {:query, :context}
(defmethod execute-task :query
  [_worker task]
  (let [{:keys [query context]} (:payload task)]
    (try
      (let [result (get-in context query ::not-found)]
        (if (= result ::not-found)
          {:status :failure
           :error "Query path not found in context"}
          {:status :success
           :result result}))
      (catch Exception e
        {:status :failure
         :error (.getMessage e)}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Worker Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(declare stop-worker!)

(defn- process-task
  "Process a single task and send result to result-ch."
  [worker task]
  (let [start-time (System/currentTimeMillis)
        worker-id (:id worker)
        result-ch (:result-ch task)]
    (reset! (:current-task worker) task)
    (reset! (:status worker) :busy)
    (try
      (let [timeout-ms (or (:timeout task) (:timeout-ms worker))
            result-ch-internal (async/promise-chan)
            _ (async/go
                (let [execution-result (execute-task worker task)]
                  (async/>! result-ch-internal execution-result)))
            timeout-ch (async/timeout timeout-ms)
            [result port] (async/alts!! [result-ch-internal timeout-ch])
            duration (- (System/currentTimeMillis) start-time)
            task-result (cond
                          (= port timeout-ch)
                          (do
                            (swap! (:metrics worker) update :timeouts inc)
                            (make-task-result
                             {:task-id (:id task)
                              :trace-id (:trace-id task)
                              :status :timeout
                              :error (str "Task timed out after " timeout-ms "ms")
                              :duration duration
                              :worker-id worker-id}))

                          (= port result-ch-internal)
                          (do
                            (swap! (:metrics worker)
                                   (fn [m]
                                     (-> m
                                         (update :tasks-completed inc)
                                         (update :total-duration + duration))))
                            (make-task-result
                             {:task-id (:id task)
                              :trace-id (:trace-id task)
                              :status (:status result)
                              :result (:result result)
                              :error (:error result)
                              :duration duration
                              :worker-id worker-id})))]

        ;; Send result to task's result channel if present
        (when (and result-ch task-result)
          (async/put! result-ch task-result))
        task-result)

      (catch Exception e
        (swap! (:metrics worker) update :errors inc)
        (let [task-result (make-task-result
                           {:task-id (:id task)
                            :trace-id (:trace-id task)
                            :status :failure
                            :error (.getMessage e)
                            :duration (- (System/currentTimeMillis) start-time)
                            :worker-id worker-id})]
          (when result-ch
            (async/put! result-ch task-result))
          task-result))

      (finally
        (reset! (:current-task worker) nil)
        (reset! (:status worker) :idle)))))

(defn- worker-loop-fn
  "Main worker loop that processes tasks from task-chan."
  [worker]
  (async/go-loop []
    (when (= :idle @(:status worker))
      (let [[msg port] (async/alts! [(:task-chan worker) (:control-chan worker)])]
        (cond
          ;; Control signal received
          (= port (:control-chan worker))
          (when (= :stop msg)
            (reset! (:status worker) :stopped)
            :stopped)

          ;; Task received
          (and (= port (:task-chan worker)) (some? msg))
          (do
            (process-task worker msg)
            (recur))

          ;; Channel closed
          :else
          (do
            (reset! (:status worker) :stopped)
            :stopped))))))

(defn start-worker!
  "Start the worker - begins processing tasks.
   Returns the worker."
  [worker]
  (when (= :stopped @(:status worker))
    (reset! (:status worker) :idle))
  (when-not @(:worker-loop worker)
    (reset! (:worker-loop worker) (worker-loop-fn worker)))
  worker)

(defn stop-worker!
  "Stop the worker gracefully.
   Returns the worker."
  [worker]
  (when (#{:idle :busy :error} @(:status worker))
    (async/put! (:control-chan worker) :stop)
    (when-let [loop @(:worker-loop worker)]
      (async/<!! loop)  ; Wait for loop to finish
      (reset! (:worker-loop worker) nil)))
  worker)

(defn worker-status
  "Get current worker status."
  [worker]
  {:id (:id worker)
   :status @(:status worker)
   :current-task-id (some-> @(:current-task worker) :id)
   :metrics @(:metrics worker)})

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Submission
;; ══════════════════════════════════════════════════════════════════════════════

(defn submit-task!
  "Submit a task to the worker.
   Returns a channel that will receive the TaskResult."
  [worker task]
  (let [result-ch (async/promise-chan)
        ;; Attach result channel to task
        task-with-result (assoc task :result-ch result-ch)]
    (async/put! (:task-chan worker) task-with-result)
    result-ch))

(defn submit-task-async!
  "Submit a task and handle result with callback.
   Callback receives TaskResult."
  [worker task callback]
  (async/go
    (when-let [result (async/<! (submit-task! worker task))]
      (callback result))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-worker
  "Create a new WorkerAgent.

   Options:
     :opencode-client - OpenCode client map {:base-url \"...\"} (required)
     :timeout-ms      - Default task timeout (default: 60000)
     :buffer-size     - Task channel buffer size (default: 10)"
  [{:keys [opencode-client timeout-ms buffer-size]
    :or {timeout-ms 60000
         buffer-size 10}}]
  (let [worker (->WorkerAgent
                (str (UUID/randomUUID))
                opencode-client
                (atom :stopped)
                (atom nil)
                (atom {:tasks-completed 0
                       :timeouts 0
                       :errors 0
                       :total-duration 0})
                (async/chan buffer-size)
                (async/chan)
                (atom nil)
                timeout-ms)]
    worker))
