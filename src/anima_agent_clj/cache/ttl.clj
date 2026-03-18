(ns anima-agent-clj.cache.ttl
  "TTL (Time-To-Live) cache implementation.

   Entries automatically expire after their TTL duration.
   Best for caching data that becomes stale after a certain time,
   such as API responses or session data.

   Features:
   - Automatic expiration based on TTL
   - Configurable default TTL
   - Per-entry TTL override
   - Background cleanup (optional)

   Usage:
   (def cache (create-ttl-cache {:default-ttl 60000}))
   (cache-set! cache \"key\" value {:ttl 30000})
   (cache-get cache \"key\")"
  (:require [anima-agent-clj.cache.core :as core]
            [clojure.core.async :as async])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; TTL Cache Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord TTLCache
           [entries         ; atom<map> - key -> CacheEntry
            default-ttl     ; Default TTL in milliseconds
            max-entries     ; Maximum number of entries
            cleanup-interval ; Cleanup interval in milliseconds
            cleanup-loop    ; atom - Background cleanup loop
            stats]          ; atom<map> - Statistics

  core/ICache

  (get-entry [this key]
    (when-let [entry (get @(:entries this) key)]
      (if (core/is-expired? entry)
        ;; Entry expired, remove it
        (do
          (core/delete-entry this key)
          (swap! (:stats this) update :expirations inc)
          nil)
        ;; Entry valid, update access info
        (do
          (let [updated-entry (-> entry
                                  (assoc :accessed-at (Date.))
                                  (update :access-count inc))]
            (swap! (:entries this) assoc key updated-entry)
            (swap! (:stats this) update :hits inc)
            updated-entry)))))

  (set-entry [this key value opts]
    (let [size (core/estimate-size value)
          ttl (or (:ttl opts) (:default-ttl this))
          entry (core/make-cache-entry key value
                                       (merge opts {:ttl ttl :size size}))]
      ;; Check capacity and evict expired entries first
      (when (and (:max-entries this)
                 (>= (count @(:entries this)) (:max-entries this))
                 (not (contains? @(:entries this) key)))
        ;; Try to find and remove expired entries
        (let [expired-keys (->> @(:entries this)
                                (filter (fn [[_ e]] (core/is-expired? e)))
                                (map first)
                                (take 10))]
          (if (seq expired-keys)
            (doseq [k expired-keys]
              (core/delete-entry this k))
            ;; No expired entries, remove oldest
            (when-let [oldest-key (->> @(:entries this)
                                       (sort-by (fn [[_ e]] (.getTime (:created-at e))))
                                       first
                                       first)]
              (core/delete-entry this oldest-key)
              (swap! (:stats this) update :evictions inc)))))
      ;; Store entry
      (swap! (:entries this) assoc key entry)
      (swap! (:stats this) update :writes inc)
      entry))

  (delete-entry [this key]
    (if (contains? @(:entries this) key)
      (do
        (swap! (:entries this) dissoc key)
        true)
      false))

  (has-key? [this key]
    (if-let [entry (get @(:entries this) key)]
      (not (core/is-expired? entry))
      false))

  (clear-all [this]
    (reset! (:entries this) {}))

  (all-keys [this]
    ;; Return only non-expired keys
    (->> @(:entries this)
         (filter (fn [[_ e]] (not (core/is-expired? e))))
         (map first)))

  (entry-count [this]
    (count (core/all-keys this)))

  (stats [this]
    (let [entries @(:entries this)
          stats-data @(:stats this)]
      (merge
       stats-data
       {:entry-count (count entries)
        :size-bytes (reduce + 0 (map :size (vals entries)))
        :hit-rate (core/hit-rate stats-data)}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Background Cleanup
;; ══════════════════════════════════════════════════════════════════════════════

(defn- cleanup-expired
  "Remove all expired entries."
  [cache]
  (let [expired-keys (->> @(:entries cache)
                          (filter (fn [[_ e]] (core/is-expired? e)))
                          (map first))]
    (doseq [k expired-keys]
      (core/delete-entry cache k)
      (swap! (:stats cache) update :expirations inc))
    (count expired-keys)))

(defn- start-cleanup-loop
  "Start background cleanup loop."
  [cache interval-ms]
  (async/go-loop []
    (async/<! (async/timeout interval-ms))
    (when-let [loop-atom (:cleanup-loop cache)]
      (when @loop-atom
        (try
          (cleanup-expired cache)
          (catch Exception e
            (println "TTL Cache cleanup error:" (.getMessage e))))
        (recur)))))

(defn- stop-cleanup-loop
  "Stop the cleanup loop."
  [cache]
  (when-let [loop-atom (:cleanup-loop cache)]
    (reset! loop-atom nil)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-ttl-cache
  "Create a TTL cache.

   Options:
     :default-ttl      - Default TTL in milliseconds (default: 5 minutes)
     :max-entries      - Maximum number of entries (default: 5000)
     :cleanup-interval - Cleanup interval in milliseconds (default: 60 seconds)
     :enable-cleanup   - Enable background cleanup (default: true)"
  [{:keys [default-ttl max-entries cleanup-interval enable-cleanup]
    :or {default-ttl (* 5 60 1000)
         max-entries 5000
         cleanup-interval (* 60 1000)
         enable-cleanup true}}]
  (let [cache (->TTLCache
               (atom {})
               default-ttl
               max-entries
               cleanup-interval
               (atom true)
               (atom {:hits 0 :misses 0 :evictions 0 :expirations 0 :writes 0}))]
    (when enable-cleanup
      (start-cleanup-loop cache cleanup-interval))
    cache))

(defn close-ttl-cache
  "Close the TTL cache and stop background cleanup."
  [cache]
  (stop-cleanup-loop cache)
  cache)

;; ══════════════════════════════════════════════════════════════════════════════
;; Convenience Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn cache-get
  "Get value from TTL cache."
  [cache key]
  (when-let [entry (core/get-entry cache key)]
    (:value entry)))

(defn cache-set!
  "Set value in TTL cache with optional TTL override."
  ([cache key value]
   (cache-set! cache key value {}))
  ([cache key value opts]
   (core/set-entry cache key value opts)
   value))

(defn cache-delete!
  "Delete value from cache."
  [cache key]
  (core/delete-entry cache key))

(defn cache-get-or-compute
  "Get value from cache, or compute and cache if not found."
  [cache key compute-fn]
  (if-let [value (cache-get cache key)]
    value
    (let [value (compute-fn)]
      (cache-set! cache key value)
      value)))

(defn cache-get-or-compute-with-ttl
  "Get value from cache, or compute and cache with specific TTL."
  [cache key ttl-ms compute-fn]
  (if-let [value (cache-get cache key)]
    value
    (let [value (compute-fn)]
      (cache-set! cache key value {:ttl ttl-ms})
      value)))

(defn cache-touch!
  "Refresh entry TTL by updating accessed time."
  [cache key]
  (when-let [entry (get @(:entries cache) key)]
    (let [updated (assoc entry :accessed-at (Date.))]
      (swap! (:entries cache) assoc key updated)
      updated)))

(defn cache-remaining-ttl
  "Get remaining TTL for a key in milliseconds."
  [cache key]
  (when-let [entry (get @(:entries cache) key)]
    (when-let [ttl (:ttl entry)]
      (let [elapsed (- (System/currentTimeMillis)
                       (.getTime (:created-at entry)))]
        (max 0 (- ttl elapsed))))))
