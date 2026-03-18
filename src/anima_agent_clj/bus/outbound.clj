(ns anima-agent-clj.bus.outbound
  "Outbound bus for sending messages to external destinations.
   Implements the IMessageBus protocol from bus.core."
  (:require [clojure.core.async :as async]
            [anima-agent-clj.bus.core :as core]))

(defrecord OutboundBus [chan]
  core/IMessageBus
  (publish! [_ msg]
    (async/put! chan msg))
  (subscribe! [_ _ handler]
    (async/go-loop []
      (when-let [msg (async/<! chan)]
        (handler msg)
        (recur))))
  (close! [_]
    (async/close! chan)))

(defn create-bus
  "Create a new outbound bus with a sliding buffer.
   Old messages are dropped when buffer is full."
  []
  (->OutboundBus (async/chan (async/sliding-buffer 1000))))
