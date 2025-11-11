(ns opencode-clj.macros.core
  "Core macros for opencode-clj SDK"
  (:require [opencode-clj.client :as client]))

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
  [client & body]
  `(let [session# (opencode-clj.core/create-session ~client)]
     (try
       (binding [client/*session* session#]
         ~@body)
       (finally
         (opencode-clj.sessions/delete-session-internal ~client session#)))))

(defn make-session-aware
  "Create a session-aware version of a function"
  [original-fn fn-name]
  (fn [client & args]
    (if-let [dynamic-session client/*session*]
      ;; Has dynamic session: automatically insert session parameter
      (apply original-fn client dynamic-session args)
      ;; No dynamic session: check if first arg is session-id
      (if (and (seq args) (string? (first args)))
        (apply original-fn client args)
        (throw (ex-info (str "No session available for " fn-name
                             ". Use within with-session block or provide session-id.")
                        {:function fn-name}))))))

(defmacro def-session-fn
  "Define a function that works with both explicit session-id and dynamic session"
  [name docstring arg-list & body]
  (let [internal-name (symbol (str name "-internal"))]
    `(do
       ;; Internal function with original implementation
       (defn ~internal-name ~docstring ~arg-list ~@body)

       ;; Public function with session-aware behavior
       (def ~name
         (with-meta
           (make-session-aware ~internal-name ~(str name))
           {:doc ~docstring
            :arglists '(~arg-list [client & args])})))))
