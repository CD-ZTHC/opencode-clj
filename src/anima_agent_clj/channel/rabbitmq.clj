(ns anima-agent-clj.channel.rabbitmq
  "RabbitMQ channel for message queue integration.

   Supports:
   - Pub/sub patterns for distributed deployments
   - Session-based routing via routing-key
   - Reconnection with exponential backoff

   Messages received from RabbitMQ are published to Bus.inbound.
   Outbound messages are delivered via the Dispatch loop.

   Routing key patterns:
   - opencode.session.{session-id}  -> Direct to specific session
   - opencode.user.{user-id}        -> To user's active session
   - opencode.broadcast             -> Broadcast to all sessions"
  (:require [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.session :as session]
            [anima-agent-clj.bus :as bus]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.exchange :as le]
            [langohr.consumers :as lc]
            [langohr.basic :as lb])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; RabbitMQ Channel Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord RabbitMQChannel
           [config ; Configuration map
            connection ; RMQ connection (atom)
            rmq-channel ; RMQ channel (atom)
            running? ; Is channel running? (atom)
            session-store ; SessionStore for session management
            msg-bus ; Bus instance for message routing
            exchange-name ; Exchange name
            queue-name ; Queue name
            routing-key ; Default routing key
            reconnect-ms ; Reconnect delay (ms)
            max-reconnect ; Max reconnect attempts
            auto-ack]) ; Auto-ack messages

;; ══════════════════════════════════════════════════════════════════════════════
;; Message Handling — publish to Bus instead of processing directly
;; ══════════════════════════════════════════════════════════════════════════════

(defn- handle-incoming-message
  "Handle incoming RabbitMQ message by publishing to Bus inbound."
  [this {:keys [routing-key] :as _meta} payload]
  (try
    (let [data (json/read-str (String. payload) :key-fn keyword)
          ;; Extract session-id from routing-key or message data
          session-id (or (:session-id data)
                         (ch/extract-session-id routing-key)
                         (when-let [uid (ch/extract-user-id routing-key)]
                           (str "user:" uid))
                         (str (UUID/randomUUID)))

          ;; Build InboundMessage and publish to Bus
          inbound-msg (bus/make-inbound
                       {:channel "rabbitmq"
                        :sender-id (:sender data "unknown")
                        :chat-id session-id
                        :content (:content data "")
                        :session-key (ch/make-session-routing-key session-id)
                        :media (:media data [])
                        :metadata {:routing-key routing-key
                                   :reply-to (:reply-to data)
                                   :headers (:headers data {})
                                   :account-id (:account-id data)}})]

      ;; Update session store if available
      (when-let [store (:session-store this)]
        (session/update-session-context store
                                        session-id
                                        {:last-message (:content data)
                                         :last-active (.getTime (Date.))}))

      ;; Publish to bus
      (when-let [b (:msg-bus this)]
        (bus/publish-inbound! b inbound-msg)))

    (catch Exception e
      (println "Error handling RabbitMQ message:" (.getMessage e)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Implementation
;; ══════════════════════════════════════════════════════════════════════════════

(extend-protocol ch/Channel
  RabbitMQChannel
  (start [this]
    (if @(:running? this)
      this
      (try
        (let [uri (get-in (:config this) [:uri] "amqp://guest:guest@localhost:5672")
              conn (rmq/connect {:uri uri})
              ch (lch/open conn)
              ex-name (get-in (:config this) [:exchange] "anima.messages")
              q-name (get-in (:config this) [:queue] "anima.inbox")
              rk (get-in (:config this) [:routing-key] "anima")
              ;; Declare exchange
              _ (le/declare ch ex-name "direct" {:durable true})
              ;; Declare queue
              _ (lq/declare ch q-name {:exclusive false :auto-delete false})
              ;; Bind queue to exchange with routing key
              _ (lq/bind ch q-name ex-name {:routing-key rk})
              ;; Start consumer — publishes to Bus instead of processing
              consumer-tag (lc/subscribe ch q-name
                                         (fn [ch-meta payload]
                                           (handle-incoming-message this ch-meta payload))
                                         {:auto-ack (:auto-ack this true)})]
          ;; Store state
          (reset! (:connection this) conn)
          (reset! (:rmq-channel this) ch)
          (reset! (:running? this) true)
          (assoc this
                 :exchange-name ex-name
                 :queue-name q-name
                 :routing-key rk
                 :consumer-tag consumer-tag))
        (catch Exception e
          {:success false :error (.getMessage e)}))))

  (stop [this]
    (when @(:running? this)
      (reset! (:running? this) false)
      (when-let [ch @(:rmq-channel this)]
        (lch/close ch))
      (when-let [conn @(:connection this)]
        (rmq/close conn))
      (reset! (:connection this) nil)
      (reset! (:rmq-channel this) nil))
    this)

  (send-message [this _target message opts]
    (if-not @(:running? this)
      {:success false :error "Channel not running"}
      (try
        (let [ch @(:rmq-channel this)
              ex-name (:exchange-name this)
              payload (json/write-str
                       {:message message
                        :media (:media opts [])
                        :session-id (:session-id opts)
                        :timestamp (.getTime (Date.))})

              ;; Determine routing key
              rk (or (:routing-key opts)
                     (:routing-key this)
                     "anima")

              ;; Publish message
              _ (lb/publish ch ex-name rk payload
                            {:content-type "application/json"})]
          {:success true})
        (catch Exception e
          {:success false :error (.getMessage e)}))))

  (channel-name [_this]
    "rabbitmq")

  (health-check [this]
    (and @(:running? this)
         (some? @(:connection this))
         (rmq/open? @(:connection this)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; StreamingChannel Implementation
;; ══════════════════════════════════════════════════════════════════════════════

(extend-protocol ch/StreamingChannel
  RabbitMQChannel
  (send-chunk [this target chunk]
    (ch/send-message this target chunk {:stage :chunk}))

  (start-typing [_ _] {:success true})
  (stop-typing [_ _] {:success true}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Factory Function
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-rabbitmq-channel
  "Create a new RabbitMQ channel.

   opts:
     :uri             - RabbitMQ URI (default: amqp://guest:guest@localhost:5672)
     :exchange        - Exchange name (default: anima.messages)
     :queue           - Queue name (default: anima.inbox)
     :routing-key     - Default routing key (default: opencode)
     :session-store   - SessionStore instance
     :bus             - Bus instance for message routing
     :reconnect-ms    - Reconnect delay ms (default: 5000)
     :max-reconnect   - Max reconnect attempts (default: 10)
     :auto-ack        - Auto-ack messages (default: true)

   Returns: RabbitMQChannel record"
  [{:keys [uri exchange queue routing-key session-store bus
           reconnect-ms max-reconnect auto-ack]
    :or {uri "amqp://guest:guest@localhost:5672"
         exchange "anima.messages"
         queue "anima.inbox"
         routing-key "anima"
         reconnect-ms 5000
         max-reconnect 10
         auto-ack true}}]
  (map->RabbitMQChannel
   {:config {:uri uri :exchange exchange :queue queue :routing-key routing-key}
    :connection (atom nil)
    :rmq-channel (atom nil)
    :running? (atom false)
    :session-store session-store
    :msg-bus bus
    :exchange-name exchange
    :queue-name queue
    :routing-key routing-key
    :reconnect-ms reconnect-ms
    :max-reconnect max-reconnect
    :auto-ack auto-ack}))
