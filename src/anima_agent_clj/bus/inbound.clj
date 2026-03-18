(ns anima-agent-clj.bus.inbound
  "Inbound bus for receiving messages from external sources.
   Implements the IMessageBus protocol from bus.core."
  (:require [clojure.core.async :as async]
            [anima-agent-clj.bus.core :as core]))

(defrecord InboundBus [chan]
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
  "Create a new inbound bus with a dropping buffer.
   Messages are dropped when buffer is full to prevent backpressure."
  []
  (->InboundBus (async/chan (async/dropping-buffer 1000))))
