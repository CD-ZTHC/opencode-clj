(ns opencode-clj.macros.core
  "Core macros for opencode-clj SDK")

(defmacro defopencode
  "Define a configured opencode client with auto-imported functions"
  [name url & options]
  `(do
     (def ~name (opencode-clj.core/client ~url ~@options))
     (require '[opencode-clj.core :as opencode])
     (require '[opencode-clj.projects :as projects])
     (require '[opencode-clj.sessions :as sessions])
     (require '[opencode-clj.messages :as messages])
     (require '[opencode-clj.files :as files])
     (require '[opencode-clj.config :as config])))

(defmacro with-session
  "Execute operations within a session context"
  [session-id client & body]
  `(let [~'session-id ~session-id
         ~'client ~client]
     ~@body))

(defmacro where
  "Build query parameters in a readable way"
  [& params]
  (into {} (map vec (partition 2 params))))

(defmacro select
  "Select specific fields for API response"
  [& fields]
  (vec fields))

(defmacro ->success
  "Handle successful API response"
  [response & body]
  `(let [~'result# ~response]
     (if (:success ~'result#)
       (do ~@body)
       ~'result#)))

(defmacro ->error
  "Handle error API response"
  [response & body]
  `(let [~'result# ~response]
     (if (not (:success ~'result#))
       (do ~@body)
       ~'result#)))

(defmacro try-api
  "Unified API call error handling"
  [& body]
  `(try
     ~@body
     (catch clojure.lang.ExceptionInfo e#
       (let [data# (ex-data e#)]
         (case (:error data#)
           :not-found (throw (ex-info "Not found" {:error :not-found}))
           :bad-request (throw (ex-info "Bad request" {:error :bad-request}))
           :server-error (throw (ex-info "Server error" {:error :server-error}))
           (throw e#))))))

(defmacro batch
  "Execute multiple operations in batch"
  [client & operations]
  `(mapv (fn [~'op#] (~'op# ~client)) [~@operations]))

(defmacro parallel
  "Execute multiple operations in parallel"
  [client & operations]
  `(let [~'ops# [~@operations]]
     (mapv deref
           (doall (for [~'op# ~'ops#]
                    (future (~'op# ~client)))))))
