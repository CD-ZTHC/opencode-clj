(ns anima-agent-clj.cli-main
  "CLI entry point — starts the full message bus architecture.

   Usage:
     lein run -m anima-agent-clj.cli-main
     lein run -m anima-agent-clj.cli-main -- --url http://other:9711

   Architecture:
     User → CLI Channel → Bus.inbound → Agent → Bus.outbound → Dispatch → CLI Channel → User"
  (:require [anima-agent-clj.bus :as bus]
            [anima-agent-clj.agent :as agent]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.cli :as cli]
            [anima-agent-clj.channel.session :as session]
            [anima-agent-clj.channel.registry :as registry]
            [anima-agent-clj.channel.dispatch :as dispatch]
            [clojure.core.async :as async]))

(defn- parse-args
  "Parse command line arguments into a map."
  [args]
  (loop [args (seq args)
         opts {:url "http://127.0.0.1:9711"
               :prompt "anima> "}]
    (if-not args
      opts
      (case (first args)
        "--url" (recur (nnext args) (assoc opts :url (second args)))
        "--prompt" (recur (nnext args) (assoc opts :prompt (second args)))
        ("--help" "-h") (assoc opts :help true)
        (recur (next args) opts)))))

(defn start-cli
  "Start the full CLI architecture.

   Options:
     :url    - OpenCode server URL (default: http://127.0.0.1:9711)
     :prompt - CLI prompt string (default: 'anima> ')"
  [{:keys [url prompt]
    :or {url "http://127.0.0.1:9711"
         prompt "anima> "}}]
  (println "╔══════════════════════════════════════════╗")
  (println "║       Anima Agent CLI (anima-agent-clj)        ║")
  (println "╚══════════════════════════════════════════╝")
  (println)
  (println "  Server:" url)
  (println "  Commands: help, status, history, clear, exit")
  (println)

  (let [msg-bus (bus/create-bus)
        store (session/create-store)
        reg (registry/create-registry)
        stats (dispatch/create-dispatch-stats)

        ;; Create CLI channel with bus
        cli-ch (cli/create-cli-channel {:session-store store
                                        :bus msg-bus
                                        :prompt prompt})

        ;; Create agent
        msg-agent (agent/create-agent {:bus msg-bus
                                       :opencode-url url
                                       :session-manager store})]

    ;; Register channel
    (registry/register reg cli-ch)

    ;; Start all components
    (ch/start cli-ch)
    (agent/start-agent msg-agent)
    (let [dispatch-thread (dispatch/start-outbound-dispatch
                           (:outbound-chan msg-bus) reg stats)]

      ;; Wait for CLI to finish (user types exit)
      (while @(:running? cli-ch)
        (Thread/sleep 100))

      ;; Cleanup
      (agent/stop-agent msg-agent)
      (bus/close-bus msg-bus)
      (async/<!! dispatch-thread)
      (println "\nGoodbye!")
      (System/exit 0))))

(defn -main
  "CLI entry point.

   Usage:
     lein run -m anima-agent-clj.cli-main
     lein run -m anima-agent-clj.cli-main -- --url http://my-server:9711
     lein run -m anima-agent-clj.cli-main -- --prompt 'ai> '
     lein run -m anima-agent-clj.cli-main -- --help"
  [& args]
  (let [opts (parse-args args)]
    (if (:help opts)
      (do
        (println "OpenCode CLI - Interactive AI Assistant")
        (println)
        (println "Usage:")
        (println "  lein cli")
        (println "  lein cli -- --url <url>")
        (println "  lein cli -- --prompt <prompt>")
        (println "  lein cli -- --help")
        (println)
        (println "Options:")
        (println "  --url <url>        OpenCode server URL (default: http://127.0.0.1:9711)")
        (println "  --prompt <prompt>  CLI prompt string (default: 'anima> ')")
        (println "  --help, -h         Show this help"))
      (start-cli opts))))