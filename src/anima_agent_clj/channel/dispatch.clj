(ns anima-agent-clj.channel.dispatch
  "Message dispatch - routes messages between channels and agent.

   Two dispatch loops:
   - Inbound dispatch:  Bus.inbound  → Agent (for processing)
   - Outbound dispatch: Bus.outbound → Channels (for delivery)

   Usage:
   (def bus (bus/create-bus))
   (def registry (registry/create-registry))
   (def agent (agent/create-agent {:bus bus}))
   (start-outbound-dispatch bus registry)
   ;; Agent consumes from inbound directly via start-agent"
  (:require [clojure.core.async :as async]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.registry :as registry]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dispatch Statistics
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord DispatchStats
           [dispatched ; Atom<int> - successfully dispatched messages
            errors ; Atom<int> - failed dispatches
            channel-not-found ; Atom<int> - channel lookup failures
            ])

(defn create-dispatch-stats
  "Create dispatch statistics counters."
  []
  (->DispatchStats
   (atom 0)
   (atom 0)
   (atom 0)))

(defn reset-stats
  "Reset all statistics to zero."
  [stats]
  (reset! (:dispatched stats) 0)
  (reset! (:errors stats) 0)
  (reset! (:channel-not-found stats) 0))

(defn get-stats
  "Get current statistics snapshot."
  [stats]
  {:dispatched @(:dispatched stats)
   :errors @(:errors stats)
   :channel-not-found @(:channel-not-found stats)})

;; ══════════════════════════════════════════════════════════════════════════════
;; Outbound Dispatch (Bus.outbound → Channels)
;; ══════════════════════════════════════════════════════════════════════════════

(defn dispatch-outbound-message
  "Dispatch a single OutboundMessage to the appropriate channel.
   Returns {:success true} or {:success false :error \"...\"}"
  [msg registry stats]
  (if-let [channel (registry/find-channel registry
                                          (:channel msg)
                                          (:account-id msg "default"))]
    (try
      (let [result (ch/send-message channel
                                    (:reply-target msg (:sender-id msg))
                                    (:content msg)
                                    {:media (:media msg [])
                                     :stage (:stage msg :final)})]
        (if (:success result)
          (do
            (swap! (:dispatched stats) inc)
            {:success true})
          (do
            (swap! (:errors stats) inc)
            result)))
      (catch Exception e
        (swap! (:errors stats) inc)
        (println "Dispatch error:" (.getMessage e))
        {:success false :error (.getMessage e)}))
    (do
      (swap! (:channel-not-found stats) inc)
      {:success false :error "Channel not found"})))

(defn run-outbound-dispatch-loop
  "Run the outbound dispatch loop.
   Consumes OutboundMessages from bus outbound channel and routes to channels.
   Blocks until bus channel is closed.

   Options:
   - outbound-ch : core.async channel with outbound messages
   - registry    : ChannelRegistry instance
   - stats       : DispatchStats record"
  [outbound-ch registry stats]
  (loop []
    (when-let [msg (async/<!! outbound-ch)]
      (dispatch-outbound-message msg registry stats)
      (recur))))

(defn start-outbound-dispatch
  "Start outbound dispatcher in a background thread.
   Returns the thread channel."
  [outbound-ch registry stats]
  (async/thread
    (run-outbound-dispatch-loop outbound-ch registry stats)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Combined Dispatch Starter
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-dispatch
  "Start the outbound dispatch loop.
   The inbound dispatch is handled by the Agent's start-agent function.

   Options:
   - bus      : Bus instance
   - registry : ChannelRegistry instance
   - stats    : DispatchStats record (optional, will create if not provided)

   Returns {:outbound-thread <thread-chan> :stats <stats>}"
  [{:keys [bus registry stats]}]
  (let [stats (or stats (create-dispatch-stats))
        outbound-thread (start-outbound-dispatch
                         (:outbound-chan bus)
                         registry
                         stats)]
    {:outbound-thread outbound-thread
     :stats stats}))
