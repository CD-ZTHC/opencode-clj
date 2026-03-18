(ns basic-usage
  "Basic usage examples for anima-agent-clj"
  (:require [anima-agent-clj.core :as opencode]))

;; ============================================================================
;; Client Creation
;; ============================================================================

;; Method 1: Direct creation
(def client (opencode/client "http://127.0.0.1:9711"))

;; Method 2: Direct map
(def my-client {:base-url "http://127.0.0.1:9711"})

;; ============================================================================
;; Session Management
;; ============================================================================

(defn demo-sessions []
  ;; Create session
  (let [session (opencode/create-session client {:title "Demo Session"})]
    (println "Created session:" (:id session))

    ;; List sessions
    (println "All sessions:" (opencode/list-sessions client))

    ;; Update session
    (opencode/update-session client (:id session) {:title "Updated Title"})

    ;; Delete session
    (opencode/delete-session client (:id session))))

;; ============================================================================
;; Messaging
;; ============================================================================

(defn demo-messaging []
  (let [session (opencode/create-session client {:title "Chat Demo"})]
    ;; Send prompt
    (let [response (opencode/send-prompt client
                                         (:id session)
                                         {:text "Hello, can you help me with Clojure?"}
                                         "user-chat-assistant")]
      (println "Response:" response))

    ;; Get message history
    (let [messages (opencode/list-messages client (:id session))]
      (println "Messages:" (count messages)))

    ;; Cleanup
    (opencode/delete-session client (:id session))))

;; ============================================================================
;; File Operations
;; ============================================================================

(defn demo-files []
  ;; List files
  (println "Files:" (opencode/list-files client {:path "."}))

  ;; Read file
  (println "Content:" (opencode/read-file client {:path "README.md"}))

  ;; Find text
  (println "Matches:" (opencode/find-text client "defn")))

;; ============================================================================
;; Configuration
;; ============================================================================

(defn demo-config []
  ;; Get config
  (println "Config:" (opencode/get-config client))

  ;; List providers
  (println "Providers:" (opencode/list-providers client))

  ;; List agents
  (println "Agents:" (opencode/list-agents client)))

;; ============================================================================
;; Run Demos
;; ============================================================================

(defn run-all []
  (println "=== Basic Usage Demos ===")
  (demo-sessions)
  (demo-messaging)
  (demo-files)
  (demo-config)
  (println "=== Done ==="))

(comment
  (run-all))
