(ns anima-agent-clj.dispatcher.balancer
  "Load Balancer strategies for distributing tasks across agents.

   Implements multiple load balancing algorithms:
   - Round-Robin: Simple rotation through targets
   - Weighted Round-Robin: Rotation weighted by target capacity
   - Least Connections: Select target with fewest active connections
   - Consistent Hashing: Route same session to same target

   Usage:
   (def balancer (create-balancer {:strategy :least-connections}))
   (select-target balancer targets {:session-id \"123\"})
   (update-load balancer target-id :inc)

   Architecture:
   ┌─────────────────────────────────────────────────────────────┐
   │                        Balancer                              │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │              Strategy Selector                        │   │
   │  │  :round-robin | :weighted | :least-conn | :hashing   │   │
   │  └─────────────────────────────────────────────────────┘   │
   │                          ↓                                  │
   │  ┌─────────────────────────────────────────────────────┐   │
   │  │              Load Tracker                            │   │
   │  │  target-id -> {:active 0 :total 0 :errors 0}        │   │
   │  └─────────────────────────────────────────────────────┘   │
   └─────────────────────────────────────────────────────────────┘"
  (:require [anima-agent-clj.dispatcher.router :as router])
  (:import [java.util UUID]
           [java.util.concurrent.atomic AtomicInteger]))

;; Forward declaration for hash ring builder (defined later)
(declare build-hash-ring)

;; ══════════════════════════════════════════════════════════════════════════════
;; Target Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord Target
  [id              ; String - Unique identifier
   weight          ; Integer - Weight for weighted strategies
   capacity        ; Integer - Max concurrent connections
   metadata        ; Map - Additional info (host, port, etc.)
   status          ; atom<keyword> - :available, :busy, :offline
   load            ; atom<map> - {:active 0 :total 0 :errors 0}
   ])

(defn make-target
  "Create a Target record."
  [{:keys [id weight capacity metadata]
    :or {weight 1
         capacity 10
         metadata {}}}]
  (->Target
   (or id (str (UUID/randomUUID)))
   weight
   capacity
   metadata
   (atom :available)
   (atom {:active 0 :total 0 :errors 0 :last-error nil})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Balancer Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord Balancer
  [id              ; String - Unique identifier
   strategy        ; Keyword - :round-robin, :weighted, :least-connections, :hashing
   targets         ; atom<map> - id -> Target
   target-ids      ; atom<vector> - Ordered list for round-robin
   last-idx        ; atom<int> - Last index for round-robin
   hash-ring       ; atom<vector> - Consistent hash ring
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   ])

(def default-config
  {:strategy :round-robin
   :health-check-interval-ms 30000
   :retry-count 3
   :retry-delay-ms 1000})

;; ══════════════════════════════════════════════════════════════════════════════
;; Target Management
;; ══════════════════════════════════════════════════════════════════════════════

(defn add-target!
  "Add a target to the balancer."
  [balancer target]
  (let [id (:id target)]
    (swap! (:targets balancer) assoc id target)
    (swap! (:target-ids balancer) conj id)
    (when (= :hashing (:strategy balancer))
      ;; Rebuild hash ring
      (swap! (:hash-ring balancer)
             (fn [_]
               (build-hash-ring (vals @(:targets balancer))))))
    target))

(defn remove-target!
  "Remove a target from the balancer."
  [balancer target-id]
  (when-let [target (get @(:targets balancer) target-id)]
    (swap! (:targets balancer) dissoc target-id)
    (swap! (:target-ids balancer)
           (fn [ids] (filterv #(not= % target-id) ids)))
    (when (= :hashing (:strategy balancer))
      (swap! (:hash-ring balancer)
             (fn [_]
               (build-hash-ring (vals @(:targets balancer))))))
    target))

(defn get-target
  "Get a target by ID."
  [balancer target-id]
  (get @(:targets balancer) target-id))

(defn list-targets
  "List all targets."
  [balancer]
  (vals @(:targets balancer)))

(defn list-available-targets
  "List all available targets."
  [balancer]
  (filter #(= :available @(:status %))
          (vals @(:targets balancer))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Load Tracking
;; ══════════════════════════════════════════════════════════════════════════════

(defn update-load!
  "Update target load."
  [balancer target-id op]
  (when-let [target (get-target balancer target-id)]
    (case op
      :inc (swap! (:load target)
                  (fn [load]
                    (-> load
                        (update :active inc)
                        (update :total inc))))
      :dec (swap! (:load target)
                  (fn [load]
                    (-> load
                        (update :active (fn [n] (max 0 (dec n)))))))
      :error (swap! (:load target)
                    (fn [load]
                      (-> load
                          (update :active (fn [n] (max 0 (dec n))))
                          (update :errors inc)
                          (assoc :last-error (java.util.Date.)))))
      :reset (swap! (:load target)
                    (fn [load]
                      (assoc load :active 0))))
    @(:load target)))

(defn get-load
  "Get current load for a target."
  [balancer target-id]
  (when-let [target (get-target balancer target-id)]
    @(:load target)))

(defn get-active-count
  "Get active connection count for a target."
  [balancer target-id]
  (when-let [target (get-target balancer target-id)]
    (:active @(:load target) 0)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Selection Strategies
;; ══════════════════════════════════════════════════════════════════════════════

(defn- select-round-robin
  "Round-robin selection."
  [balancer targets]
  (when (seq targets)
    (let [idx (swap! (:last-idx balancer)
                     (fn [i] (mod (inc i) (count targets))))]
      (nth targets idx))))

(defn- select-weighted-round-robin
  "Weighted round-robin selection.
   Targets with higher weight get more selections."
  [balancer targets]
  (when (seq targets)
    (let [;; Expand targets by weight
          weighted (mapcat (fn [t]
                             (repeat (:weight t 1) t))
                           targets)
          idx (swap! (:last-idx balancer)
                     (fn [i] (mod (inc i) (count weighted))))]
      (nth weighted idx))))

(defn- select-least-connections
  "Select target with least active connections."
  [balancer targets]
  (when (seq targets)
    (apply min-key
           (fn [t] (:active @(:load t) 0))
           targets)))

(defn- build-hash-ring
  "Build consistent hash ring for targets.
   Each target gets multiple virtual nodes for better distribution."
  [targets]
  (let [virtual-nodes 150  ; Number of virtual nodes per target
        ring (mapcat
              (fn [target]
                (map (fn [i]
                       {:hash (hash (str (:id target) ":" i))
                        :target target})
                     (range virtual-nodes)))
              targets)]
    (sort-by :hash ring)))

(defn- select-consistent-hashing
  "Select target using consistent hashing based on session-id."
  [balancer targets session-id]
  (when (seq targets)
    (let [ring @(:hash-ring balancer)
          key-hash (hash (str session-id))]
      (if (empty? ring)
        (first targets)
        (let [;; Find first node with hash >= key-hash
              node (or (first (filter #(>= (:hash %) key-hash) ring))
                       (first ring))]
          (:target node))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Main Selection API
;; ══════════════════════════════════════════════════════════════════════════════

(defn select-target
  "Select a target using the configured strategy.

   Options:
     :session-id - For consistent hashing strategy
     :exclude - Set of target IDs to exclude

   Returns the selected Target or nil if no targets available."
  ([balancer]
   (select-target balancer {}))
  ([balancer {:keys [session-id exclude] :as opts}]
   (let [available (list-available-targets balancer)
         filtered (if exclude
                    (filter #(not (contains? exclude (:id %))) available)
                    available)]
     (when (seq filtered)
       (case (:strategy balancer)
         :round-robin
         (select-round-robin balancer filtered)

         :weighted
         (select-weighted-round-robin balancer filtered)

         :least-connections
         (select-least-connections balancer filtered)

         :hashing
         (select-consistent-hashing balancer filtered session-id)

         ;; Default: round-robin
         (select-round-robin balancer filtered))))))

(defn select-target-with-retry
  "Select a target with retry on failure.

   Returns {:target <Target> :attempts <int>} or {:error ...}"
  ([balancer]
   (select-target-with-retry balancer {}))
  ([balancer opts]
   (let [retry-count (get-in balancer [:config :retry-count] 3)
         exclude-set (atom #{})]
     (loop [attempt 0]
       (if-let [target (select-target balancer
                                      (merge opts {:exclude @exclude-set}))]
         {:target target
          :attempts (inc attempt)}
         (if (< attempt retry-count)
           (do
             (Thread/sleep (get-in balancer [:config :retry-delay-ms] 1000))
             (recur (inc attempt)))
           {:error "No available targets"
            :attempts attempt}))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Health Status
;; ══════════════════════════════════════════════════════════════════════════════

(defn set-target-status!
  "Set target status manually."
  [balancer target-id status]
  (when-let [target (get-target balancer target-id)]
    (reset! (:status target) status)))

(defn mark-target-offline!
  "Mark a target as offline."
  [balancer target-id]
  (set-target-status! balancer target-id :offline))

(defn mark-target-available!
  "Mark a target as available."
  [balancer target-id]
  (set-target-status! balancer target-id :available))

(defn mark-target-busy!
  "Mark a target as busy."
  [balancer target-id]
  (set-target-status! balancer target-id :busy))

;; ══════════════════════════════════════════════════════════════════════════════
;; Statistics
;; ══════════════════════════════════════════════════════════════════════════════

(defn balancer-status
  "Get balancer status."
  [balancer]
  {:id (:id balancer)
   :strategy (:strategy balancer)
   :target-count (count @(:targets balancer))
   :available-count (count (list-available-targets balancer))
   :targets (into {}
                  (for [[id t] @(:targets balancer)]
                    [id {:status @(:status t)
                         :load @(:load t)
                         :weight (:weight t)}]))
   :stats @(:stats balancer)})

(defn balancer-metrics
  "Get balancer metrics for monitoring."
  [balancer]
  (let [targets (vals @(:targets balancer))]
    {:total-targets (count targets)
     :available-targets (count (filter #(= :available @(:status %)) targets))
     :offline-targets (count (filter #(= :offline @(:status %)) targets))
     :total-active (reduce + 0 (map #(:active @(:load %) 0) targets))
     :total-errors (reduce + 0 (map #(:errors @(:load %) 0) targets))}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-balancer
  "Create a new Balancer instance.

   Options:
     :id - Unique identifier (auto-generated if not provided)
     :strategy - Balancing strategy (default: :round-robin)
       :round-robin - Simple rotation
       :weighted - Weight-based rotation
       :least-connections - Fewest active connections
       :hashing - Consistent hashing by session-id
     :retry-count - Number of retries (default: 3)
     :retry-delay-ms - Delay between retries (default: 1000)

   Example:
   (create-balancer {:strategy :least-connections})"
  [{:keys [id strategy retry-count retry-delay-ms]
    :or {strategy :round-robin
         retry-count 3
         retry-delay-ms 1000}
    :as opts}]
  (->Balancer
   (or id (str (UUID/randomUUID)))
   strategy
   (atom {})
   (atom [])
   (atom -1)  ; Start at -1 so first selection is index 0
   (atom [])
   (merge default-config
          {:strategy strategy
           :retry-count retry-count
           :retry-delay-ms retry-delay-ms})
   (atom {:selections 0
          :failures 0})))
