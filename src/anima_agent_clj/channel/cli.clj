(ns anima-agent-clj.channel.cli
  "CLI channel - reads from stdin, writes to stdout.

   Simplest channel implementation for local interactive testing.
   Supports:
   - Interactive REPL-style chat
   - Command history
   - Quit commands (exit, quit, :q, /quit, /exit)

   Messages are published to the Bus for processing by the Agent.
   Flow: User Input → CLI Channel → Bus.inbound → Agent → Bus.outbound → Dispatch → CLI Channel → stdout"
  (:require [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.session :as session]
            [anima-agent-clj.bus :as bus]
            [clojure.core.async :as async]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

;; Forward declaration
(declare run-input-loop)

;; ══════════════════════════════════════════════════════════════════════════════
;; CLI Channel Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord CliChannel
           [running? ; atom<boolean> - is channel running?
            session-store ; SessionStore for managing sessions
            default-session ; atom<Session> - Default session for this CLI
            bus ; Bus instance for message routing
            prompt ; Prompt string
            reader ; BufferedReader for stdin
            ]
  ch/Channel
  (start [this]
    (when-not @(:running? this)
      (reset! (:running? this) true)
      (when (and (:session-store this) (nil? @(:default-session this)))
        (let [sess (session/create-session (:session-store this) "cli")]
          (reset! (:default-session this) sess)))
      (async/thread
        (run-input-loop this))
      (println (str "\n" (:prompt this "anima> ") "Ready. Type 'help' for commands, 'exit' to quit."))
      (println (str (:prompt this "anima> ")))
      (flush))
    this)

  (stop [this]
    (reset! (:running? this) false)
    this)

  (send-message [this target message opts]
    (try
      (println message)
      (when (= (:stage opts :final) :final)
        (print (:prompt this "anima> "))
        (flush))
      {:success true}
      (catch Exception e
        {:success false :error (.getMessage e)})))

  (channel-name [_this] "cli")

  (health-check [this]
    (and @(:running? this)
         (or (nil? (:reader this))
             (.ready ^BufferedReader (:reader this)))))

  ch/StreamingChannel
  (send-chunk [_this _target chunk]
    (print chunk)
    (flush)
    {:success true})

  (start-typing [_this _recipient]
    {:success true})

  (stop-typing [_this _recipient]
    {:success true}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Input Loop
;; ══════════════════════════════════════════════════════════════════════════════

(def quit-commands
  "Commands that terminate the CLI."
  #{"exit" "quit" ":q" "/quit" "/exit" "bye"})

(defn is-quit-command?
  "Check if input is a quit command."
  [line]
  (contains? quit-commands (str/trim (str/lower-case line))))

(defn- is-empty?
  "Check if input is empty or whitespace only."
  [line]
  (str/blank? (str/trim line)))

(defn- print-help
  "Print help message."
  []
  (println "
Available commands:
  help              - Show this help
  status            - Show session status
  history           - Show conversation history
  clear             - Clear conversation history
  exit/quit/:q      - Exit the CLI

Just type your message to chat with the AI."))

(defn- print-status
  "Print session status."
  [this]
  (if-let [sess (:default-session this)]
    (do
      (println "\nSession Status:")
      (println "  ID:" (:id sess))
      (println "  Channel:" (:channel sess))
      (println "  Routing Key:" (:routing-key sess))
      (println "  History Length:" (count (get-in sess [:context :history] []))))
    (println "No active session")))

(defn- print-history
  "Print conversation history."
  [this]
  (if-let [sess (:default-session this)]
    (let [history (get-in sess [:context :history] [])]
      (if (str/blank? (str/join (map :content history)))
        (println "No conversation history")
        (do
          (println "\nConversation History:")
          (doseq [msg history]
            (println (str "  [" (:role msg "unknown") "]")
                     (str "  " (:content msg)))))))
    (println "No active session")))

(defn- clear-history
  "Clear conversation history."
  [this]
  (when-let [sess @(:default-session this)]
    (session/set-session-context (:session-store this) (:id sess) {:history []})
    (println "Conversation history cleared.")))

(defn- handle-special-command
  "Handle special CLI commands. Returns true if handled."
  [this line]
  (let [trimmed (str/trim (str/lower-case line))]
    (cond
      (= "help" trimmed)
      (do (print-help) true)

      (= "status" trimmed)
      (do (print-status this) true)

      (= "history" trimmed)
      (do (print-history this) true)

      (= "clear" trimmed)
      (do (clear-history this) true)

      :else false)))

(defn- run-input-loop
  "Run the main input loop.

   Flow:
   1. Read user input from stdin
   2. Publish message to Bus inbound channel
   3. Response comes back via dispatch → send-message"
  [this]
  (let [reader (or (:reader this)
                   (BufferedReader. (InputStreamReader. System/in)))]
    (loop []
      (if @(:running? this)
        (let [line (try
                     (.readLine reader)
                     (catch Exception e
                       (println "Error reading input:" (.getMessage e))
                       nil))]
          (if (nil? line)
            :eof
            (do
              (cond
                ;; Check for quit
                (is-quit-command? line)
                (do
                  (println "Goodbye!")
                  (reset! (:running? this) false)
                  (when-let [sess @(:default-session this)]
                    (session/close-session (:session-store this) (:id sess))))

                ;; Skip empty lines
                (is-empty? line)
                (do
                  (print (:prompt this "anima> "))
                  (flush))

                ;; Handle special commands
                (handle-special-command this line)
                (do
                  (print (:prompt this "anima> "))
                  (flush))

                ;; Regular message — publish to Bus
                :else
                (do
                  ;; Update session history
                  (when-let [sess @(:default-session this)]
                    (session/add-to-history (:session-store this) (:id sess)
                                            {:role "user" :content line})
                    (session/touch-session (:session-store this) (:id sess)))

                  ;; Publish to bus inbound
                  (if-let [msg-bus (:bus this)]
                    (let [inbound-msg (bus/make-inbound
                                       {:channel "cli"
                                        :sender-id "user"
                                        :chat-id (when-let [sess @(:default-session this)]
                                                   (:id sess))
                                        :content line
                                        :session-key (when-let [sess @(:default-session this)]
                                                       (:routing-key sess))
                                        :metadata {:source :stdin}})]
                      (bus/publish-inbound! msg-bus inbound-msg))

                    ;; No bus — just echo (testing mode)
                    (do
                      (println "[echo]" line)
                      (print (:prompt this "anima> "))
                      (flush)))))

              (recur))))
        :done))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-cli-channel
  "Create a new CLI channel.

   Options:
     :session-store        - SessionStore instance (will create if not provided)
     :bus                  - Bus instance for message routing
     :prompt               - Prompt string (default: 'anima> ')"
  [{:keys [session-store bus prompt]
    :or {prompt "anima> "}}]
  (let [store (or session-store (session/create-store))]
    (map->CliChannel
     {:running? (atom false)
      :session-store store
      :default-session (atom nil)
      :bus bus
      :prompt prompt
      :reader nil})))

(defn create-cli-channel-with-session
  "Create a CLI channel with an existing session."
  [session {:keys [bus prompt]
            :or {prompt "anima> "}}]
  (map->CliChannel
   {:running? (atom false)
    :session-store nil
    :default-session (atom session)
    :bus bus
    :prompt prompt
    :reader nil}))
