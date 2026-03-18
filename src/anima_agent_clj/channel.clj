(ns anima-agent-clj.channel
  "Channel protocol and core types for messaging platform integrations.

   Design based on nullclaw's vtable-based polymorphism pattern,
   adapted for Clojure using protocols.

   Core concepts:
   - Channel: A messaging platform (CLI, RabbitMQ, etc.)
   - ChannelMessage: A message with session-id for routing
   - Session: A conversation context identified by session-id
   - OpenCode Integration: Message -> Channel -> OpenCode API -> Channel -> Response

   Routing patterns:
   - opencode.session.{session-id}  -> Direct session routing
   - opencode.user.{user-id}        -> User-level routing
   - opencode.broadcast             -> Broadcast to all"
  (:require [clojure.string :as str]
            [anima-agent-clj.core :as opencode]
            [anima-agent-clj.client :as http])
  (:import [java.util UUID Date]))

;; ════════════════════════════════════════════════════════════════════════════
;; ChannelMessage
;; ════════════════════════════════════════════════════════════════════════════

(defrecord ChannelMessage
           [id              ; Message unique ID
            session-id      ; Session ID (core routing key)
            sender          ; Sender identifier
            content         ; Message content
            channel         ; Source channel name
            timestamp       ; Unix timestamp (ms)

            ;; Optional fields
            reply-target    ; Reply target (routing-key or sender)
            message-id      ; Platform message ID
            first-name      ; Sender display name
            is-group        ; Is group chat?
            account-id      ; Multi-account identifier

            ;; Metadata
            metadata])      ; Additional metadata {:routing-key :headers ...}

(defn create-message
  "Create a new ChannelMessage with defaults."
  [{:keys [session-id sender content channel reply-target message-id
           first-name is-group account-id metadata]
    :or {session-id (str (UUID/randomUUID))
         sender "unknown"
         channel "unknown"}}]
  (->ChannelMessage
   (str (UUID/randomUUID))
   session-id
   sender
   content
   channel
   (.getTime (Date.))
   reply-target
   message-id
   first-name
   (boolean is-group)
   account-id
   metadata))

;; ════════════════════════════════════════════════════════════════════════════
;; Channel Protocol
;; ════════════════════════════════════════════════════════════════════════════

(defprotocol Channel
  "Messaging channel interface. All channels must implement this protocol.

   Flow: User Message → Channel → Bus → Agent → Bus → Dispatch → Channel → Response

   Key methods:
   - send-message: Send response back to user via this channel"
  (start [this]
    "Start the channel (connect, begin listening). Returns this or error map.")
  (stop [this]
    "Stop the channel (disconnect, clean up). Returns this.")
  (send-message [this target message opts]
    "Send a message to target (response back to user).
     target: routing-key, user-id, or channel identifier
     message: string content
     opts: {:media [...] :stage :chunk/:final :session-id ...}
     Returns {:success true} or {:success false :error \"...\"}")
  (channel-name [this]
    "Return the channel name (e.g. 'cli', 'rabbitmq').")
  (health-check [this]
    "Return true if channel is operational."))

;; ════════════════════════════════════════════════════════════════════════════
;; StreamingChannel Protocol (Optional)
;; ════════════════════════════════════════════════════════════════════════════

(defprotocol StreamingChannel
  "Optional streaming support for channels."
  (send-chunk [this target chunk]
    "Send a streaming chunk.")
  (start-typing [this recipient]
    "Start typing indicator.")
  (stop-typing [this recipient]
    "Stop typing indicator."))

;; ════════════════════════════════════════════════════════════════════════════
;; Routing Key Utilities
;; ════════════════════════════════════════════════════════════════════════════

(def routing-key-patterns
  "Routing key format patterns."
  {:session  #"^anima\.session\.(.+)$"
   :user      #"^anima\.user\.(.+)$"
   :channel   #"^anima\.channel\.(.+)$"
   :broadcast #"^anima\.broadcast$"})

(defn extract-session-id
  "Extract session-id from routing-key.
   Returns nil if not a session routing-key."
  [routing-key]
  (when (string? routing-key)
    (let [match (re-matches (:session routing-key-patterns) routing-key)]
      (when match
        (second match)))))

(defn extract-user-id
  "Extract user-id from routing-key.
   Returns nil if not a user routing-key."
  [routing-key]
  (when (string? routing-key)
    (let [match (re-matches (:user routing-key-patterns) routing-key)]
      (when match
        (second match)))))

(defn make-session-routing-key
  "Create a session routing-key from session-id."
  [session-id]
  (str "anima.session." session-id))

(defn make-user-routing-key
  "Create a user routing-key from user-id."
  [user-id]
  (str "anima.user." user-id))

(defn make-channel-routing-key
  "Create a channel routing-key from channel-name."
  [channel-name]
  (str "anima.channel." channel-name))

(def broadcast-routing-key
  "Routing key for broadcast messages."
  "anima.broadcast")

;; ════════════════════════════════════════════════════════════════════════════
;; Default implementations
;; ════════════════════════════════════════════════════════════════════════════

(extend-protocol StreamingChannel
  Object
  (send-chunk [this target chunk]
    ;; Default: fall back to send-message
    (send-message this target chunk {:stage :chunk}))
  (start-typing [_ _]
    ;; Default: no-op
    {:success true})
  (stop-typing [_ _]
    ;; Default: no-op
    {:success true}))

;; ════════════════════════════════════════════════════════════════════════════
;; Helper functions
;; ════════════════════════════════════════════════════════════════════════════

(defn success?
  "Check if result is successful."
  [result]
  (true? (:success result)))

(defn error?
  "Check if result is an error."
  [result]
  (false? (:success result)))

(defn ok
  "Create a success result."
  ([] {:success true})
  ([data] (merge {:success true} data)))

(defn err
  "Create an error result."
  ([message] {:success false :error message})
  ([message data] (merge {:success false :error message} data)))


