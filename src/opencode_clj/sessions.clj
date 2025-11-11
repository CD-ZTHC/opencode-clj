(ns opencode-clj.sessions
  "Session management functions for opencode-server"
  (:require [opencode-clj.client :as client]
            [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]
            [opencode-clj.macros.core :refer [def-session-fn]]))

(defn list-sessions
  "List all sessions"
  [client & [params]]
  (-> (http/get-request client "/session" params)
      utils/handle-response))

(defn create-session
  "Create a new session. Options can include :title and :parentID"
  [client & [options]]
  (-> (http/post-request client "/session" (or options {}))
      utils/handle-response))

(def-session-fn get-session
  "Get a specific session by ID"
  [client session-id & [params]]
  (-> (http/get-request client (str "/session/" (client/session-id session-id)) params)
      utils/handle-response))

(def-session-fn update-session
  "Update session properties (e.g., title)"
  [client session-id updates]
  (-> (http/patch-request client (str "/session/" (client/session-id session-id)) updates)
      utils/handle-response))

(def-session-fn delete-session
  "Delete a session and all its data"
  [client session-id & [params]]
  (-> (http/delete-request client (str "/session/" (client/session-id session-id)) params)
      utils/handle-response))

(def-session-fn get-session-children
  "Get a session's children"
  [client session-id & [params]]
  (-> (http/get-request client (str "/session/" (client/session-id session-id) "/children") params)
      utils/handle-response))

(def-session-fn get-session-todo
  "Get the todo list for a session"
  [client session-id & [params]]
  (-> (http/get-request client (str "/session/" (client/session-id session-id) "/todo") params)
      utils/handle-response))

(def-session-fn init-session
  "Analyze the app and create an AGENTS.md file"
  [client session-id {:keys [modelID providerID messageID]}]
  (utils/validate-required {:modelID modelID :providerID providerID :messageID messageID}
                           [:modelID :providerID :messageID])
  (-> (http/post-request client (str "/session/" (client/session-id session-id) "/init")
                         {:modelID modelID :providerID providerID :messageID messageID})
      utils/handle-response))

(def-session-fn fork-session
  "Fork an existing session at a specific message"
  [client session-id & [message-id]]
  (let [body (if message-id {:messageID message-id} {})]
    (-> (http/post-request client (str "/session/" (client/session-id session-id) "/fork") body)
        utils/handle-response)))

(def-session-fn abort-session
  "Abort a session"
  [client session-id & [params]]
  (-> (http/post-request client (str "/session/" (client/session-id session-id) "/abort") params)
      utils/handle-response))

(def-session-fn share-session
  "Share a session"
  [client session-id & [params]]
  (-> (http/post-request client (str "/session/" (client/session-id session-id) "/share") params)
      utils/handle-response))

(def-session-fn unshare-session
  "Unshare the session"
  [client session-id & [params]]
  (-> (http/delete-request client (str "/session/" (client/session-id session-id) "/share") params)
      utils/handle-response))

(def-session-fn get-session-diff
  "Get the diff for this session"
  [client session-id & [params]]
  (-> (http/get-request client (str "/session/" (client/session-id session-id) "/diff") params)
      utils/handle-response))

(def-session-fn summarize-session
  "Summarize the session"
  [client session-id {:keys [providerID modelID]}]
  (utils/validate-required {:providerID providerID :modelID modelID}
                           [:providerID :modelID])
  (-> (http/post-request client (str "/session/" (client/session-id session-id) "/summarize")
                         {:providerID providerID :modelID modelID})
      utils/handle-response))
