(ns anima-agent-clj.bus
  "Unified message bus for routing messages between channels and agent.
   Now integrated with the Agent Cluster architecture."
  (:require [clojure.core.async :as async]
            [anima-agent-clj.bus.core :as cluster-core]
            [anima-agent-clj.bus.inbound :as inbound]
            [anima-agent-clj.bus.outbound :as outbound]
            [anima-agent-clj.bus.internal :as internal]
            [anima-agent-clj.bus.control :as control])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Legacy Types & Constructors (Keeping for compatibility)
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord InboundMessage
           [id channel sender-id chat-id content session-key media metadata])

(defrecord OutboundMessage
           [id channel account-id chat-id content media stage reply-target])

(defn make-inbound
  "Legacy constructor for InboundMessage."
  [{:keys [channel sender-id chat-id content session-key media metadata]
    :or {sender-id "unknown" media [] metadata {}}}]
  (->InboundMessage (str (UUID/randomUUID)) channel sender-id chat-id content session-key media metadata))

(defn make-outbound
  "Legacy constructor for OutboundMessage."
  [{:keys [channel account-id chat-id content media stage reply-target]
    :or {account-id "default" media [] stage :final}}]
  (->OutboundMessage (str (UUID/randomUUID)) channel account-id chat-id content media stage reply-target))

;; ══════════════════════════════════════════════════════════════════════════════
;; Cluster Integration
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord Bus
           [inbound     ; InboundBus
            outbound    ; OutboundBus
            internal    ; InternalBus
            control     ; ControlBus
            ;; Legacy compatibility fields
            inbound-chan  ; Direct access to inbound channel
            outbound-chan ; Direct access to outbound channel
            ])

(defn create-bus
  "Create a new cluster-aware message bus.
   Accepts optional options for backward compatibility."
  ([] (create-bus {}))
  ([_opts]
   (let [in (inbound/create-bus)
         out (outbound/create-bus)]
     (->Bus
      in
      out
      (internal/create-bus)
      (control/create-bus)
      (:chan in)
      (:chan out)))))

(defn close-bus
  "Close all bus channels."
  [bus]
  (cluster-core/close! (:inbound bus))
  (cluster-core/close! (:outbound bus))
  (cluster-core/close! (:internal bus))
  (cluster-core/close! (:control bus)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Bus Operations (Unified)
;; ══════════════════════════════════════════════════════════════════════════════

(defn publish-inbound! [bus msg] (cluster-core/publish! (:inbound bus) msg))
(defn publish-outbound! [bus msg] (cluster-core/publish! (:outbound bus) msg))
(defn publish-internal! [bus msg] (cluster-core/publish! (:internal bus) msg))
(defn publish-control! [bus msg] (cluster-core/publish! (:control bus) msg))

(defn publish-inbound-async! [bus msg]
  (async/go (publish-inbound! bus msg)))

(defn publish-outbound-async! [bus msg]
  (async/go (publish-outbound! bus msg)))

(defn consume-inbound! [bus]
  (async/<!! (:chan (:inbound bus))))

(defn consume-outbound! [bus]
  (async/<!! (:chan (:outbound bus))))