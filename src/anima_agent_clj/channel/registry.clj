(ns anima-agent-clj.channel.registry
  "Channel registry for managing multiple messaging channels.

   Supports multi-account channels where each channel can have
   multiple instances identified by account-id.

   Usage:
   (def reg (create-registry))
   (register reg my-channel \"default\")
   (find-channel reg \"cli\" \"default\")"
  (:require [anima-agent-clj.channel :as ch]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Registry
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ChannelRegistry
           [channels]) ; Atom<{channel-name {account-id -> channel}}>

(defn create-registry
  "Create an empty channel registry."
  []
  (->ChannelRegistry (atom {})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Registration
;; ══════════════════════════════════════════════════════════════════════════════

(defn register
  "Register a channel with optional account-id.
   account-id defaults to 'default' if not specified."
  ([registry channel]
   (register registry channel "default"))
  ([registry channel account-id]
   (let [name (ch/channel-name channel)]
     (swap! (:channels registry)
            assoc-in [name account-id] channel)
     registry)))

(defn unregister
  "Unregister a channel (all accounts)."
  [registry channel-name]
  (swap! (:channels registry) dissoc channel-name)
  registry)

(defn unregister-with-account
  "Unregister a channel for specific account-id."
  [registry channel-name account-id]
  (swap! (:channels registry)
         (fn [ch]
           (if (= account-id "default")
             (dissoc ch channel-name)
             (update ch channel-name dissoc account-id))))
  registry)

;; ══════════════════════════════════════════════════════════════════════════════
;; Lookup
;; ══════════════════════════════════════════════════════════════════════════════

(defn find-channel
  "Find a channel by name.
   Returns the default account channel, or first available."
  ([registry channel-name]
   (find-channel registry channel-name "default"))
  ([registry channel-name account-id]
   (when-let [accounts @(:channels registry)]
     (if-let [ch (get-in accounts [channel-name account-id])]
       ch
       (when-let [channel-accounts (get accounts channel-name)]
         (or (get channel-accounts "default")
             (first (vals channel-accounts))))))))

(defn all-channels
  "Get all registered channels as a flat sequence."
  [registry]
  (for [[_name accounts] @(:channels registry)
        [_account-id ch] accounts]
    ch))

(defn channel-names
  "Get names of all registered channels."
  [registry]
  (keys @(:channels registry)))

(defn channel-count
  "Get count of registered channel instances."
  [registry]
  (reduce + 0
          (for [[_ accounts] @(:channels registry)]
            (count accounts))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-all
  "Start all registered channels.
   Returns registry for chaining."
  [registry]
  (doseq [ch (all-channels registry)]
    (try
      (ch/start ch)
      (catch Exception e
        (println "Failed to start channel:" (.getMessage e)))))
  registry)

(defn stop-all
  "Stop all registered channels.
   Returns registry for chaining."
  [registry]
  (doseq [ch (all-channels registry)]
    (try
      (ch/stop ch)
      (catch Exception e
        (println "Failed to stop channel:" (.getMessage e)))))
  registry)

;; ══════════════════════════════════════════════════════════════════════════════
;; Health
;; ══════════════════════════════════════════════════════════════════════════════

(defn health-report
  "Get health status of all channels.
   Returns: {:healthy n :unhealthy n :total n :channels [{:name :status}]}"
  [registry]
  (let [channels (for [[name accounts] @(:channels registry)
                       [_account-id ch] accounts]
                   {:name name
                    :status (if (ch/health-check ch) "healthy" "unhealthy")})
        healthy (count (filter #(= "healthy" (:status %)) channels))
        total (count channels)]
    {:healthy healthy
     :unhealthy (- total healthy)
     :total total
     :all-healthy? (and (> total 0) (= healthy total))
     :channels (vec channels)}))
