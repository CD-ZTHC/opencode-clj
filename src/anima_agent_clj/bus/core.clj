(ns anima-agent-clj.bus.core
  "Core definitions for the Agent Cluster Message Bus.
   Inspired by ARM AXI interface."
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Message Definition
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord Message
           [id              ; UUID - Unique identifier for this message
            trace-id        ; UUID - Link tracing ID for multi-step tasks
            source          ; ComponentRef - Source component/agent
            target          ; ComponentRef (Optional) - Target component/agent
            type            ; MessageType - :request, :response, :event, :command, :heartbeat
            priority        ; Integer (0-9) - 0 is highest (System Control)
            payload         ; Any - The actual data
            metadata        ; Map - Extra info (headers, routing keys, timestamps)
            timestamp       ; Date - When the message was created
            ttl])           ; Duration - Time to live

;; ══════════════════════════════════════════════════════════════════════════════
;; Protocols
;; ══════════════════════════════════════════════════════════════════════════════

(defprotocol IMessageBus
  (publish! [this msg] "Publish a message to the bus.")
  (subscribe! [this topic handler] "Subscribe to a topic/type on the bus.")
  (close! [this] "Close the bus and release resources."))

;; ══════════════════════════════════════════════════════════════════════════════
;; Helpers
;; ══════════════════════════════════════════════════════════════════════════════

(defn make-message
  "Construct a new Message with defaults."
  [{:keys [source type payload] :as opts}]
  (let [source (or source (:source opts))
        type (or type (:type opts))
        payload (or payload (:payload opts))]
    (map->Message
     (merge
      {:id (str (UUID/randomUUID))
       :trace-id (or (:trace-id opts) (str (UUID/randomUUID)))
       :priority (or (:priority opts) 5)
       :metadata {}
       :timestamp (Date.)
       :ttl 30000} ; Default 30s TTL
      opts
      {:source source :type type :payload payload}))))
