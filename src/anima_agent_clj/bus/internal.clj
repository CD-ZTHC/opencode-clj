(ns anima-agent-clj.bus.internal
  "Internal bus for component-to-component communication.
   Implements the IMessageBus protocol from bus.core."
  (:require [clojure.core.async :as async]
            [anima-agent-clj.bus.core :as core]))

(defrecord InternalBus [chan]
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
  "Create a new internal bus with a standard buffer.
   Balanced for internal component communication."
  []
  (->InternalBus (async/chan 100)))
