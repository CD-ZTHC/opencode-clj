(ns anima-agent-clj.core
  "Main entry point for anima-agent-clj SDK.

   Provides a unified API for interacting with opencode-server:

   Client:
     (def client (client \"http://127.0.0.1:9711\"))

   Sessions: list-sessions, create-session, get-session, update-session,
             delete-session, fork-session, share-session

   Messages: list-messages, get-message, send-prompt, execute-command,
             run-shell-command

   Files: list-files, read-file, find-text, find-files, find-symbols

   Config: get-config, update-config, list-providers, list-agents

   CLI:
     lein cli                    Start interactive CLI
     lein cli -- --url <url>     Specify OpenCode server URL"
  (:require [anima-agent-clj.projects :as projects]
            [anima-agent-clj.sessions :as sessions]
            [anima-agent-clj.messages :as messages]
            [anima-agent-clj.files :as files]
            [anima-agent-clj.config :as config]))

;; Client creation
(defn client
  "Create a new opencode-server client"
  [base-url & [options]]
  (let [opts (merge {:base-url base-url
                     :directory nil
                     :http-opts {}} options)]
    opts))

;; Project management
(def list-projects projects/list-projects)
(def current-project projects/current-project)

;; Session management
(def list-sessions sessions/list-sessions)
(def create-session sessions/create-session)
(def get-session sessions/get-session)
(def update-session sessions/update-session)
(def delete-session sessions/delete-session)
(def get-session-children sessions/get-session-children)
(def get-session-todo sessions/get-session-todo)
(def init-session sessions/init-session)
(def fork-session sessions/fork-session)
(def abort-session sessions/abort-session)
(def share-session sessions/share-session)
(def unshare-session sessions/unshare-session)
(def get-session-diff sessions/get-session-diff)
(def summarize-session sessions/summarize-session)

;; Messaging
(def list-messages messages/list-messages)
(def get-message messages/get-message)
(def send-prompt messages/send-prompt)
(def execute-command messages/execute-command)
(def run-shell-command messages/run-shell-command)
(def revert-message messages/revert-message)
(def unrevert-messages messages/unrevert-messages)
(def respond-to-permission messages/respond-to-permission)

;; File operations
(def list-files files/list-files)
(def read-file files/read-file)
(def get-file-status files/get-file-status)
(def find-text files/find-text)
(def find-files files/find-files)
(def find-symbols files/find-symbols)

;; Configuration
(def get-config config/get-config)
(def update-config config/update-config)
(def list-providers config/list-providers)
(def list-commands config/list-commands)
(def list-agents config/list-agents)
(def get-tool-ids config/get-tool-ids)
(def list-tools config/list-tools)
(def get-path config/get-path)
(def write-log config/write-log)
(def get-mcp-status config/get-mcp-status)
(def get-lsp-status config/get-lsp-status)
(def get-formatter-status config/get-formatter-status)
(def set-auth config/set-auth)

(defn -main
  "Entry point for the application.

   For interactive CLI, use:
     lein cli
     lein run -m anima-agent-clj.cli-main"
  [& _args]
  (println "OpenCode Clojure Client")
  (println)
  (println "To start interactive CLI:")
  (println "  lein cli")
  (println "  lein cli -- --url http://my-server:9711")
  (println)
  (println "REPL Usage:")
  (println "  (require '[anima-agent-clj.core :as opencode])")
  (println "  (def client (opencode/client \"http://127.0.0.1:9711\"))"))
