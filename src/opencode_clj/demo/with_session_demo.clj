(ns opencode-clj.demo.with-session-demo
  "Demo showing how to use with-session macro"
  (:require [opencode-clj.core :as opencode]
            [opencode-clj.macros.core :refer [with-session]]))

;; Create a client instance
(def client (opencode/client "http://127.0.0.1:9711"))

(defn demo-basic-usage
  "Demonstrate basic with-session usage"
  []
  (println "=== Basic with-session Demo ===")

  ;; Traditional way (requires explicit session management)
  (println "\n1. Traditional way (explicit session management):")
  (let [session-result (opencode/create-session client)
        session-id (get-in session-result [:id])]
    (when session-id
      (println "   Created session:" session-id)
      (let [messages-result (opencode/list-messages client session-id)]
        (println "   List messages result:" (:success messages-result)))
      (opencode/delete-session client session-id)
      (println "   Deleted session")))

  ;; New way with with-session (automatic session management)
  (println "\n2. New way with with-session (automatic session management):")
  (with-session client
                (println "   Inside with-session block")
                (let [messages-result (opencode/list-messages client)]
                  (println "   List messages result:" (:success messages-result))
                  (let [session-result (opencode/get-session client)]
                    (println "   Get session result:" (:success session-result))))))

(defn demo-multiple-operations
  "Demonstrate multiple operations within same session"
  []
  (println "\n=== Multiple Operations in Same Session ===")
  (with-session client
                (println "Performing multiple operations in the same session:")

                ;; List messages
                (let [messages-result (opencode/list-messages client)]
                  (println "  - List messages:" (:success messages-result)))

                ;; Get session info
                (let [session-result (opencode/get-session client)]
                  (println "  - Get session:" (:success session-result)))

                ;; Get session todo
                (let [todo-result (opencode/get-session-todo client)]
                  (println "  - Get session todo:" (:success todo-result)))

                ;; Get session diff
                (let [diff-result (opencode/get-session-diff client)]
                  (println "  - Get session diff:" (:success diff-result)))))

(defn demo-error-handling
  "Demonstrate error handling when session is not available"
  []
  (println "\n=== Error Handling Demo ===")

  ;; Calling session-aware function outside with-session
  (println "1. Calling session-aware function outside with-session:")
  (try
    (opencode/list-messages client)
    (println "   ❌ Should not reach here")
    (catch Exception e
      (println "   ✅ Correctly throws error:" (.getMessage e))))

  ;; Mixed usage - non-session functions work anywhere
  (println "\n2. Non-session functions work anywhere:")
  (let [sessions-result (opencode/list-sessions client)]
    (println "   List sessions:" (:success sessions-result)))

  (let [providers-result (opencode/list-providers client)]
    (println "   List providers:" (:success providers-result))))

(defn demo-explicit-vs-dynamic
  "Demonstrate explicit session-id vs dynamic session usage"
  []
  (println "\n=== Explicit vs Dynamic Session Usage ===")

  ;; Create a session explicitly
  (let [session-result (opencode/create-session client {:title "Demo Session"})
        session-id (get-in session-result [:data :id])]

    (when session-id
      (println "Created session with title 'Demo Session':" session-id)

      ;; Use explicit session-id
      (println "\n1. Using explicit session-id:")
      (let [messages-result (opencode/list-messages client session-id)]
        (println "   List messages with explicit session-id:" (:success messages-result)))

      ;; Use with-session (dynamic session takes precedence)
      (println "\n2. Using with-session (dynamic session):")
      (with-session client
                    (let [messages-result (opencode/list-messages client)]
                      (println "   List messages with dynamic session:" (:success messages-result))))

      ;; Clean up
      (opencode/delete-session client session-id)
      (println "\nDeleted session:" session-id))))

(defn run-all-demos
  "Run all demonstration functions"
  []
  (println "OpenCode with-session Demo")
  (println "===========================")

  (demo-basic-usage)
  (demo-multiple-operations)
  (demo-error-handling)
  (demo-explicit-vs-dynamic)

  (println "\n🎉 All demos completed!"))

;; Comment block for REPL usage
(comment
  ;; Run all demos
  (run-all-demos)

  ;; Run individual demos
  (demo-basic-usage)
  (demo-multiple-operations)
  (demo-error-handling)
  (demo-explicit-vs-dynamic)

  ;; Test specific functionality
  (with-session
    client
    (opencode/send-prompt client "Hello from demo!")
    (opencode/send-prompt client "code `fib generator` with python !")
    (opencode/list-messages client))

  ;; Check function metadata
  (meta #'opencode/list-messages)
  (meta #'opencode/get-session)

  ;; Manual session management for comparison
  (let [session (opencode/create-session client)]
    (try
      (opencode/list-messages client (:data session))
      (finally
        (opencode/delete-session client (:data session))))))

(defn -main [& _args]
  (run-all-demos))
