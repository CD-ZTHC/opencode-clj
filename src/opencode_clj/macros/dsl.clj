(ns opencode-clj.macros.dsl
  "DSL macros for opencode-clj SDK")

(defmacro defsession
  "Define a session workflow with chainable operations"
  [name & operations]
  `(defn ~name [client#]
     (let [session# (opencode-clj.core/create-session client# {:title ~(str name)})]
       (do
         ~@(map (fn [op#]
                  (cond
                    (and (seq? op#) (= (first op#) 'create)) `(opencode-clj.core/update-session client# (:id session#) ~(second op#))
                    (and (seq? op#) (= (first op#) 'prompt)) `(opencode-clj.core/send-prompt client# (:id session#) ~(second op#))
                    (and (seq? op#) (= (first op#) 'list-messages)) `(opencode-clj.core/list-messages client# (:id session#))
                    (and (seq? op#) (= (first op#) 'get-diff)) `(opencode-clj.core/get-session-diff client# (:id session#))
                    :else (throw (ex-info (str "Unknown operation: " op#) {:op op#}))))
                operations)
         session#))))

(defmacro with-project
  "Execute operations in project context"
  [directory & body]
  `(let [client# (assoc ~'client :directory ~directory)]
     ~@body))

(defmacro with-config
  "Temporarily modify client configuration"
  [config & body]
  `(let [original-config# (:http-opts ~'client)
         modified-client# (assoc ~'client :http-opts (merge original-config# ~config))]
     ~@body))

(defmacro def-workflow
  "Define complex workflow"
  [name & body]
  `(defn ~name [client#]
     (let [result# (do ~@body)]
       result#)))

(defmacro pipeline
  "Create data processing pipeline"
  [& steps]
  `(fn [input#]
     (-> input#
         ~@(map (fn [step#]
                  (cond
                    (and (seq? step#) (= (first step#) 'read-file)) `(opencode-clj.core/read-file client ~(second step#))
                    (and (seq? step#) (= (first step#) 'parse-json)) `(cheshire.core/parse-string (:content %))
                    (and (seq? step#) (= (first step#) 'transform)) `(second step#)
                    (and (seq? step#) (= (first step#) 'write-file)) `(opencode-clj.core/write-file client ~(second step#))
                    :else step#))
                steps))))

(defmacro stream-messages
  "Stream messages from session"
  [client session-id & body]
  `(opencode-clj.macros.core/with-session ~session-id ~client
     (let [messages# (opencode-clj.core/list-messages ~client ~session-id)]
       (doseq [msg# messages#]
         ~@body))))

(defmacro watch-files
  "Watch for file changes"
  [client path & body]
  `(let [client# ~client
         path# ~path
         watcher# (future
                    (loop []
                      (let [status# (opencode-clj.core/get-file-status client#)]
                        (doseq [file# status#]
                          ~@body)
                        (Thread/sleep 5000)
                        (recur))))]
     watcher#))

(defmacro should-respond
  "Test API response in a declarative way"
  [& assertions]
  `(let [responses# (atom [])]
     ;; Execute assertions and collect responses
     ~@(for [assertion# assertions]
         (cond
           (and (seq? assertion#) (= (first assertion#) 'success?))
           `(let [resp# (second assertion#)]
              (swap! responses# conj resp#)
              (:success resp#))
           (and (seq? assertion#) (= (first assertion#) 'has-keys))
           `(let [resp# (second assertion#)
                  keys# (nth assertion# 2)]
              (swap! responses# conj resp#)
              (every? #(contains? resp# %) keys#))
           :else assertion#))
     @responses#))
