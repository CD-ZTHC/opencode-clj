(ns opencode-clj.macros.async
  "Async processing macros for opencode-clj SDK")

(defmacro async-operation
  "Wrap operation in future"
  [operation]
  `(future
     (try
       ~operation
       (catch Exception ~'e#
         {:error ~'e#}))))

(defmacro parallel-operations
  "Execute multiple operations in parallel"
  [client & operations]
  `(let [~'client# ~client
         ~'futures# (mapv (fn [~'op#]
                            (async-operation (~'op# ~'client#)))
                          [~@operations])]
     (mapv deref ~'futures#)))

(defmacro event-stream
  "Create simple event stream using agents"
  [client event-type & handler]
  `(let [~'client# ~client
         ~'event-agent# (agent [])
         ~'handler-fn# (second ~handler)]
     (send ~'event-agent#
           (fn [~'events# ~'event#]
             (~'handler-fn# ~'event#)
             (conj ~'events# ~'event#)))
     {:agent ~'event-agent# :type ~event-type}))

(defmacro timeout-operation
  "Add timeout to operation"
  [timeout-ms operation]
  `(let [~'result# (future ~operation)]
     (try
       (deref ~'result# ~timeout-ms {:timeout true})
       (catch java.util.concurrent.TimeoutException _
         {:timeout true}))))

(defmacro retry-operation
  "Retry operation with exponential backoff"
  [max-retries operation]
  `(loop [~'attempt# 0]
     (let [~'result# (try
                       ~operation
                       (catch Exception ~'e#
                         {:error ~'e#}))]
       (if (and (:error ~'result#) (< ~'attempt# ~max-retries))
         (do
           (^[long] Thread/sleep (* 1000 (Math/pow 2 ~'attempt#)))
           (recur (inc ~'attempt#)))
         ~'result#))))

(defmacro cache-async
  "Simple cache with TTL using atoms"
  []
  `(let [~'cache# (atom {})]
     {:get (fn [~'key#]
             (get @~'cache# ~'key#))
      :put (fn [~'key# ~'value#]
             (swap! ~'cache# assoc ~'key# ~'value#))
      :clear (fn []
               (reset! ~'cache# {}))}))

(defmacro batch-process
  "Process items in batches"
  [batch-size items & body]
  `(let [~'items# ~items
         ~'batch-size# ~batch-size]
     (mapcat (fn [~'batch#]
               (do ~@body))
             (partition-all ~'batch-size# ~'items#))))

(defmacro rate-limiter
  "Rate limit operations"
  [operations-per-second operation]
  `(let [~'rate# ~operations-per-second
         ~'interval# (/ 1000.0 ~'rate#)
         ~'last-execution# (atom 0)]
     (fn [& ~'args#]
       (let [~'now# (System/currentTimeMillis)
             ~'time-since# (- ~'now# @~'last-execution#)]
         (if (< ~'time-since# ~'interval#)
           (do
             (^[long] Thread/sleep (- ~'interval# ~'time-since#))
             (reset! ~'last-execution# (System/currentTimeMillis)))
           (reset! ~'last-execution# ~'now#))
         (apply ~operation ~'args#)))))
