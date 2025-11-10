(ns opencode-clj.messages
  "Messaging functions for opencode-server"
  (:require [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]))

(defn list-messages
  "List messages for a session"
  [client session-id & [params]]
  (-> (http/get-request client (str "/session/" session-id "/message") params)
      utils/handle-response))

(defn get-message
  "Get a specific message from a session"
  [client session-id message-id & [params]]
  (-> (http/get-request client (str "/session/" session-id "/message/" message-id) params)
      utils/handle-response))

(defn send-prompt
  "Create and send a new message to a session"
  [client session-id message & [agent]]
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
    (-> (http/post-request client (str "/session/" session-id "/message") (merge normalized-message (if agent {:agent agent} {})))
        utils/handle-response)))

(defn execute-command
  "Send a new command to a session"
  [client session-id {:keys [arguments command agent model message-id]}]
  (utils/validate-required {:arguments arguments :command command}
                           [:arguments :command])
  (let [body (cond-> {:arguments arguments :command command}
               agent (assoc :agent agent)
               model (assoc :model model)
               message-id (assoc :messageID message-id))]
    (-> (http/post-request client (str "/session/" session-id "/command") body)
        utils/handle-response)))

(defn run-shell-command
  "Run a shell command"
  [client session-id {:keys [agent command]}]
  (utils/validate-required {:agent agent :command command}
                           [:agent :command])
  (-> (http/post-request client (str "/session/" session-id "/shell")
                         {:agent agent :command command})
      utils/handle-response))

(defn revert-message
  "Revert a message"
  [client session-id {:keys [message-id part-id]}]
  (utils/validate-required {:message-id message-id}
                           [:message-id])
  (let [body (cond-> {:messageID message-id}
               part-id (assoc :partID part-id))]
    (-> (http/post-request client (str "/session/" session-id "/revert") body)
        utils/handle-response)))

(defn unrevert-messages
  "Restore all reverted messages"
  [client session-id & [params]]
  (-> (http/post-request client (str "/session/" session-id "/unrevert") params)
      utils/handle-response))

(defn respond-to-permission
  "Respond to a permission request"
  [client session-id permission-id response]
  (when-not (contains? #{"once" "always" "reject"} response)
    (throw (ex-info "Invalid response. Must be 'once', 'always', or 'reject'"
                    {:response response})))
  (-> (http/post-request client (str "/session/" session-id "/permissions/" permission-id)
                         {:response response})
      utils/handle-response))
