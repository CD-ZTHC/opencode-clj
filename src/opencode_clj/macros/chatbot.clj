(ns opencode-clj.macros.chatbot
  "Chatbot-specific macros for opencode-clj SDK"
  (:require [clojure.core.async :as async]
            [clojure.string :as str]))

;; Global chat handlers storage
(def ^:dynamic chat-handlers (atom {}))

(defmacro def-chatbot
  "Define a chatbot with configuration"
  [name & config]
  (let [config-map (apply hash-map config)]
    `(def ~name
       (merge
         {:client        {:base-url "http://127.0.0.1:9711"}
          :default-model {:providerID "anthropic" :modelID "claude-3"}
          :system-prompt "You are a helpful AI assistant"
          :tools         {:bash true :edit true :webfetch true}
          :max-tokens    4000
          :temperature   0.7}
         ~config-map))))

(defmacro chat-session
  "Create a chat session with event handlers"
  [bot & handlers]
  `(let [bot# ~bot
         session# {:id    (str "session-" (rand-int 10000))
                   :title (or (:title bot#) "Chat Session")}
         handlers# (apply merge ~handlers)]
     (println "Chat session created:" (:id session#))
     ;; Store handlers for later use
     (swap! chat-handlers merge @chat-handlers handlers#)
     session#))

(defmacro on-message
  "Define message handler for chat session"
  [message-type & body]
  `{:on-message {~message-type (fn [~'msg# ~'session#] ~@body)}})

(defmacro on-command
  "Define command handler for chat session"
  [command & body]
  `{:on-command {~command (fn [~'args# ~'session#] ~@body)}})

(defmacro message-pipeline
  "Define message processing pipeline"
  [bot & steps]
  `(let [~'bot# ~bot
         ~'pipeline# (atom [])]
     ;; Register pipeline steps
     (doseq [[~'step-name# ~'step-fn#] (partition 2 [~@steps])]
       (swap! ~'pipeline# conj {:name ~'step-name# :fn ~'step-fn#}))
     (fn [~'input#]
       (reduce (fn [~'acc# ~'step#]
                 ((:fn ~'step#) ~'acc#))
               ~'input#
               @~'pipeline#))))

(defmacro async-chat
  "Async chat processing"
  [bot & body]
  `(let [~'bot# ~bot
         ~'input-chan# (async/chan)
         ~'output-chan# (async/chan)]
     (async/go-loop []
       (when-let [~'input# (async/<! ~'input-chan#)]
         (async/go
           (let [~'response# (do ~@body)]
             (async/>! ~'output-chan# ~'response#)))
         (recur)))
     {:input ~'input-chan# :output ~'output-chan#}))

(defmacro multimodal-message
  "Create multimodal message"
  [& content]
  (let [content-map (apply hash-map content)]
    `(merge {:parts []} ~content-map)))

(defmacro conversation-state
  "Manage conversation state"
  [bot & config]
  (let [config-map (apply hash-map config)]
    `(let [~'bot# ~bot
           ~'state# (atom (merge {:mode "general" :context {} :history []} ~config-map))]
       (fn [~'operation# & ~'args#]
         (case ~'operation#
           :get @~'state#
           :reset! (reset! ~'state# {})
           :update! (apply swap! ~'state# merge ~'args#)
           :transition! (let [[~'from# ~'to# ~'transform-fn#] ~'args#]
                          (swap! ~'state# assoc :mode ~'to#)
                          (~'transform-fn# @~'state#)))))))


(comment
  (def-chatbot my-bot
               :title "My Demo Bot"
               :default-model {:providerID "zai-coding-plan" :modelID "glm-4.6"}
               :system-prompt "You are a helpful coding assistant"
               :temperature 0.8)

  )