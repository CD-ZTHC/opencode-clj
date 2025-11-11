(ns opencode-clj.messages
  "Messaging functions for opencode-server"
  (:require [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]
            [opencode-clj.macros.core :refer [def-session-fn]]))

(def-session-fn list-messages
  "List messages for a session"
  [client session-id & [params]]
  (-> (http/get-request client (str "/session/" (http/session-id session-id) "/message") params)
      utils/handle-response))

(def-session-fn get-message
  "Get a specific message from a session"
  [client session-id message-id & [params]]
  (-> (http/get-request client (str "/session/" (http/session-id session-id) "/message/" message-id) params)
      utils/handle-response))

(defn send-prompt-internal
  "Create and send a new message to a session"
  ([client session-id message]
   (send-prompt-internal client (http/session-id session-id) message nil))
  ([client session-id message agent]
   ;; Normalize message to ensure parts have required :type field
   (let [normalized-message (cond
                              (string? message)
                              {:parts [{:type "text" :text message}]}
                              (and (map? message) (:text message) (not (:parts message)))
                              {:parts [{:type "text" :text (:text message)}]}
                              (and (map? message) (:parts message))
                              message
                              :else
                              (throw (ex-info "Invalid message format. Expected string, {:text \"...\"}, or {:parts [...]}"
                                              {:message message})))]
     (-> (http/post-request client (str "/session/" (http/session-id session-id) "/message") (merge normalized-message (if agent {:agent agent} {})))
         utils/handle-response))))

(def send-prompt
  (with-meta
    (opencode-clj.macros.core/make-session-aware send-prompt-internal "send-prompt")
    {:doc "Create and send a new message to a session"
     :arglists '([client session-id message]
                 [client session-id message agent]
                 [client & args])}))

(def-session-fn execute-command
  "Send a new command to a session"
  [client session-id {:keys [arguments command agent model message-id]}]
  (utils/validate-required {:arguments arguments :command command}
                           [:arguments :command])
  (let [body (cond-> {:arguments arguments :command command}
               agent (assoc :agent agent)
               model (assoc :model model)
               message-id (assoc :messageID message-id))]
    (-> (http/post-request client (str "/session/" (http/session-id session-id) "/command") body)
        utils/handle-response)))

(def-session-fn run-shell-command
  "Run a shell command"
  [client session-id {:keys [agent command]}]
  (utils/validate-required {:agent agent :command command}
                           [:agent :command])
  (-> (http/post-request client (str "/session/" (http/session-id session-id) "/shell")
                         {:agent agent :command command})
      utils/handle-response))

(def-session-fn revert-message
  "Revert a message"
  [client session-id {:keys [message-id part-id]}]
  (utils/validate-required {:message-id message-id}
                           [:message-id])
  (let [body (cond-> {:messageID message-id}
               part-id (assoc :partID part-id))]
    (-> (http/post-request client (str "/session/" (http/session-id session-id) "/revert") body)
        utils/handle-response)))

(def-session-fn unrevert-messages
  "Restore all reverted messages"
  [client session-id & [params]]
  (-> (http/post-request client (str "/session/" (http/session-id session-id) "/unrevert") params)
      utils/handle-response))

(def-session-fn respond-to-permission
  "Respond to a permission request"
  [client session-id permission-id response]
  (when-not (contains? #{"once" "always" "reject"} response)
    (throw (ex-info "Invalid response. Must be 'once', 'always', or 'reject'"
                    {:response response})))
  (-> (http/post-request client (str "/session/" (http/session-id session-id) "/permissions/" permission-id)
                         {:response response})
      utils/handle-response))
