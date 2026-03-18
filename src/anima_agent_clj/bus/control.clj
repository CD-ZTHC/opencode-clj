(ns anima-agent-clj.bus.control
  "Control bus for system-level signals (scaling, config, etc.)"
  (:require [clojure.core.async :as async]
            [anima-agent-clj.bus.core :as core]))

(defrecord ControlBus [chan]
  core/IMessageBus
  (publish! [_ msg] (async/>!! chan msg))
  (subscribe! [_ _ handler]
    (async/go-loop []
      (when-let [msg (async/<! chan)]
        (handler msg)
        (recur))))
  (close! [_] (async/close! chan)))

(defn create-bus [] (->ControlBus (async/chan (async/sliding-buffer 10))))
