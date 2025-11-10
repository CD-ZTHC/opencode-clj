(ns opencode-clj.config
  "Configuration management for opencode-server"
  (:require [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]))

(defn get-config
  "Get configuration info"
  [client & [params]]
  (-> (http/get-request client "/config" params)
      utils/handle-response))

(defn update-config
  "Update configuration"
  [client config]
  (-> (http/patch-request client "/config" config)
      utils/handle-response))

(defn list-providers
  "List all providers"
  [client & [params]]
  (-> (http/get-request client "/config/providers" params)
      utils/handle-response))

(defn list-commands
  "List all commands"
  [client & [params]]
  (-> (http/get-request client "/command" params)
      utils/handle-response))

(defn list-agents
  "List all agents"
  [client & [params]]
  (-> (http/get-request client "/agent" params)
      utils/handle-response))

(defn get-tool-ids
  "List all tool IDs (including built-in and dynamically registered)"
  [client & [params]]
  (-> (http/get-request client "/experimental/tool/ids" params)
      utils/handle-response))

(defn list-tools
  "List tools with JSON schema parameters for a provider/model"
  [client {:keys [provider model]}]
  (utils/validate-required {:provider provider :model model}
                           [:provider :model])
  (-> (http/get-request client "/experimental/tool" {:provider provider :model model})
      utils/handle-response))

(defn get-path
  "Get the current path"
  [client & [params]]
  (-> (http/get-request client "/path" params)
      utils/handle-response))

(defn write-log
  "Write a log entry to the server logs"
  [client {:keys [service level message extra]}]
  (utils/validate-required {:service service :level level :message message}
                           [:service :level :message])
  (when-not (contains? #{"debug" "info" "error" "warn"} level)
    (throw (ex-info "Invalid level. Must be 'debug', 'info', 'error', or 'warn'"
                    {:level level})))
  (let [body (cond-> {:service service :level level :message message}
               extra (assoc :extra extra))]
    (-> (http/post-request client "/log" body)
        utils/handle-response)))

(defn get-mcp-status
  "Get MCP server status"
  [client & [params]]
  (-> (http/get-request client "/mcp" params)
      utils/handle-response))

(defn get-lsp-status
  "Get LSP server status"
  [client & [params]]
  (-> (http/get-request client "/lsp" params)
      utils/handle-response))

(defn get-formatter-status
  "Get formatter status"
  [client & [params]]
  (-> (http/get-request client "/formatter" params)
      utils/handle-response))

(defn set-auth
  "Set authentication credentials"
  [client auth-id auth]
  (-> (http/put-request client (str "/auth/" auth-id) auth)
      utils/handle-response))