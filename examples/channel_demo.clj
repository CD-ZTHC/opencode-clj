(ns channel-demo
  "Channel system usage examples.

   Demonstrates the new message bus architecture:
   - Bus for unified message routing
   - Channels for message send/receive only
   - Agent for business logic (OpenCode API)
   - Dispatch for outbound routing

   Architecture:
   User → Channel → Bus.inbound → Agent → Bus.outbound → Dispatch → Channel → User"
  (:require [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.cli :as cli]
            [anima-agent-clj.channel.rabbitmq :as rmq]
            [anima-agent-clj.channel.registry :as registry]
            [anima-agent-clj.channel.session :as session]
            [anima-agent-clj.channel.dispatch :as dispatch]
            [anima-agent-clj.bus :as bus]
            [anima-agent-clj.agent :as agent]
            [anima-agent-clj.core :as opencode]
            [clojure.core.async :as async]))

;; ════════════════════════════════════════════════════════════════════════════
;; Example 1: Full Architecture Demo (Bus + Agent + Dispatch)
;; ════════════════════════════════════════════════════════════════════════════

(defn demo-full-architecture
  "Demonstrates the full message bus architecture:
   CLI Channel → Bus.inbound → Agent → Bus.outbound → Dispatch → CLI Channel

   Requires opencode-server running at http://127.0.0.1:9711"
  []
  (println "\n=== Full Architecture Demo ===")
  (println "Flow: User → CLI → Bus → Agent → Bus → Dispatch → CLI → User\n")

  ;; 1. Create infrastructure
  (let [msg-bus (bus/create-bus)
        store (session/create-store)
        reg (registry/create-registry)
        stats (dispatch/create-dispatch-stats)

        ;; 2. Create CLI channel with bus
        cli-ch (cli/create-cli-channel {:session-store store
                                        :bus msg-bus
                                        :prompt "demo> "})

        ;; 3. Create agent
        msg-agent (agent/create-agent {:bus msg-bus
                                       :opencode-url "http://127.0.0.1:9711"
                                       :session-manager store})]

    ;; 4. Register channel
    (registry/register reg cli-ch)

    ;; 5. Start all components
    (println "Starting components...")
    (ch/start cli-ch)
    (agent/start-agent msg-agent)
    (let [dispatch-thread (dispatch/start-outbound-dispatch
                           (:outbound-chan msg-bus) reg stats)]

      (println "All components ready!")
      (println "  CLI channel: started")
      (println "  Agent: started")
      (println "  Dispatch: started")
      (println "\nType a message and it will flow through the full pipeline.")
      (println "Type 'exit' to quit.\n")

      ;; The input loop in CLI channel will handle user input
      ;; User types → CLI publishes to bus → Agent processes → Dispatch delivers

      ;; Wait for user to exit
      (while @(:running? cli-ch)
        (Thread/sleep 100))

      ;; 6. Cleanup
      (println "\nShutting down...")
      (agent/stop-agent msg-agent)
      (bus/close-bus msg-bus)
      (async/<!! dispatch-thread)
      (println "All components stopped."))))

;; ════════════════════════════════════════════════════════════════════════════
;; Example 2: Simple CLI Channel (echo mode)
;; ════════════════════════════════════════════════════════════════════════════

(defn demo-cli-simple
  "Simple CLI channel example without bus (echo mode)."
  []
  (println "\n=== Simple CLI Demo ===")
  (println "Creating CLI channel (echo mode, no bus)...")

  (let [store (session/create-store)
        cli-ch (cli/create-cli-channel {:session-store store
                                        :prompt "demo> "})]
    ;; Start channel
    (ch/start cli-ch)
    (println "CLI channel started. Health check:" (ch/health-check cli-ch))

    ;; Simulate sending a message
    (println "\nSending test message...")
    (let [result (ch/send-message cli-ch "user" "Hello from CLI!" {})]
      (println "Send result:" result))

    ;; Show stats
    (println "\nSession stats:" (session/get-stats store))

    ;; Cleanup
    (ch/stop cli-ch)
    (println "\nCLI channel stopped.")))

;; ════════════════════════════════════════════════════════════════════════════
;; Example 3: Multi-Channel Registry
;; ════════════════════════════════════════════════════════════════════════════

(defn demo-registry
  "Multi-channel registry example."
  []
  (println "\n=== Multi-Channel Registry Demo ===")

  (let [store (session/create-store)
        msg-bus (bus/create-bus)
        reg (registry/create-registry)

        ;; Create channels
        cli-ch (cli/create-cli-channel {:session-store store :bus msg-bus})
        rmq-ch (rmq/create-rabbitmq-channel {:session-store store :bus msg-bus})

        ;; Register channels
        _ (registry/register reg cli-ch)
        _ (registry/register reg rmq-ch "production")]

    (println "Registered channels:" (vec (registry/channel-names reg)))

    ;; Create sessions in different channels
    (let [cli-sess (session/create-session store "cli")
          rmq-sess (session/create-session store "rabbitmq" {:account-id "production"})]

      (println "\nCLI session:" (:id cli-sess) "routing-key:" (:routing-key cli-sess))
      (println "RabbitMQ session:" (:id rmq-sess) "routing-key:" (:routing-key rmq-sess))

      ;; Session count by channel
      (println "\nSessions by channel:")
      (println "  CLI:" (session/session-count-by-channel store "cli"))
      (println "  RabbitMQ:" (session/session-count-by-channel store "rabbitmq"))

      ;; Global stats
      (println "\nGlobal stats:" (session/get-stats store))

      ;; Cleanup
      (bus/close-bus msg-bus)
      (println "\nAll channels stopped."))))

;; ════════════════════════════════════════════════════════════════════════════
;; Example 4: Session Lookup
;; ════════════════════════════════════════════════════════════════════════════

(defn demo-session-lookup
  "Session find-or-create example."
  []
  (println "\n=== Session Lookup Demo ===")

  (let [store (session/create-store)

        ;; Create sessions with different routing keys
        sess1 (session/create-session store "rabbitmq"
                                      {:routing-key "anima.session.user-123"})
        sess2 (session/create-session store "rabbitmq"
                                      {:routing-key "anima.session.user-456"})]

    (println "Created sessions:")
    (println "  Session 1:" (:id sess1) "->" (:routing-key sess1))
    (println "  Session 2:" (:id sess2) "->" (:routing-key sess2))

    ;; Find existing session by routing key
    (println "\nLooking up sessions...")
    (let [found1 (session/find-or-create-session store "rabbitmq"
                                                 {:routing-key "anima.session.user-123"})
          found2 (session/find-or-create-session store "rabbitmq"
                                                 {:routing-key "anima.session.user-456"})
          ;; This one will create a new session
          found3 (session/find-or-create-session store "web" {})]

      (println "  Found session 1:" (:id found1) "(match:" (= (:id sess1) (:id found1)) ")")
      (println "  Found session 2:" (:id found2) "(match:" (= (:id sess2) (:id found2)) ")")
      (println "  Created new web session:" (:id found3))
      (println "\nTotal sessions:" (session/session-count store)))))

;; ════════════════════════════════════════════════════════════════════════════
;; Example 5: Bus Message Flow
;; ════════════════════════════════════════════════════════════════════════════

(defn demo-bus-flow
  "Demonstrates message flow through the bus."
  []
  (println "\n=== Bus Message Flow Demo ===")

  (let [msg-bus (bus/create-bus {:inbound-buf 10 :outbound-buf 10})]

    ;; Publish inbound
    (println "Publishing inbound message...")
    (let [inbound (bus/make-inbound {:channel "cli"
                                     :sender-id "user"
                                     :content "Hello, AI!"})]
      (bus/publish-inbound! msg-bus inbound)
      (println "  Published:" (:content inbound))

      ;; Consume inbound
      (let [received (bus/consume-inbound! msg-bus)]
        (println "  Received:" (:content received))

        ;; Simulate agent: create outbound
        (let [outbound (bus/make-outbound {:channel (:channel received)
                                           :content (str "Echo: " (:content received))
                                           :reply-target (:sender-id received)})]
          (bus/publish-outbound! msg-bus outbound)

          ;; Consume outbound
          (let [response (bus/consume-outbound! msg-bus)]
            (println "  Response:" (:content response))
            (println "  Reply to:" (:reply-target response))))))

    (bus/close-bus msg-bus)
    (println "Bus closed.")))

;; ════════════════════════════════════════════════════════════════════════════
;; Run demos
;; ════════════════════════════════════════════════════════════════════════════

(defn run-all-demos
  "Run all channel demos (except full architecture which requires opencode-server)."
  []
  (demo-bus-flow)
  (demo-cli-simple)
  (demo-session-lookup)
  (demo-registry)
  (println "\n--- Skipping full architecture demo (requires opencode-server) ---")
  (println "Run (demo-full-architecture) manually if opencode-server is available.")
  (println "\n=== All demos complete ==="))

(defn -main
  "Main entry point for running demos."
  []
  (run-all-demos))

(comment
  ;; Run demos in REPL
  (run-all-demos)

  ;; Individual demos
  (demo-bus-flow)
  (demo-cli-simple)
  (demo-session-lookup)
  (demo-registry)
  (demo-full-architecture)  ; requires opencode-server
  )
