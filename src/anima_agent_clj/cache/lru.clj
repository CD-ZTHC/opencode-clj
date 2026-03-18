(ns anima-agent-clj.cache.lru
  "LRU (Least Recently Used) cache implementation.

   Evicts the least recently accessed entries when cache is full.
   Best for caches where recently accessed items are likely to be
   accessed again.

   Usage:
   (def cache (create-lru-cache {:max-entries 1000}))
   (cache-set! cache \"key\" value)
   (cache-get cache \"key\")"
  (:require [anima-agent-clj.cache.core :as core])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; LRU Cache Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord LRUCache
           [entries ; atom<map> - key -> CacheEntry
            access-order ; atom<vector> - Keys ordered by access (LRU first)
            max-entries ; Maximum number of entries
            max-size-bytes ; Maximum size in bytes
            default-ttl ; Default TTL for entries (ms)
            stats] ; atom<map> - Statistics

  core/ICache

  (get-entry [this key]
    (when-let [entry (get @(:entries this) key)]
      (if (core/is-expired? entry)
        ;; Entry expired, remove it
        (do
          (core/delete-entry this key)
          nil)
        ;; Entry valid, update access info
        (do
          ;; Update access order
          (swap! (:access-order this)
                 (fn [order]
                   (vec (concat (remove #(= % key) order) [key]))))
          ;; Update entry access info
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
      ;; Evict if necessary
      (loop []
        (when (and (:max-entries this)
                   (>= (count @(:entries this)) (:max-entries this))
                   (not (contains? @(:entries this) key)))
          ;; Evict LRU entry
          (when-let [evict-key (first @(:access-order this))]
            (core/delete-entry this evict-key)
            (swap! (:stats this) update :evictions inc)
            (recur))))
      ;; Store entry
      (swap! (:entries this) assoc key entry)
      ;; Update access order
      (swap! (:access-order this)
             (fn [order]
               (vec (concat (remove #(= % key) order) [key]))))
      (swap! (:stats this) update :writes inc)
      entry))

  (delete-entry [this key]
    (if (contains? @(:entries this) key)
      (do
        (swap! (:entries this) dissoc key)
        (swap! (:access-order this)
               (fn [order] (vec (remove #(= % key) order))))
        true)
      false))

  (has-key? [this key]
    (if-let [entry (get @(:entries this) key)]
      (not (core/is-expired? entry))
      false))

  (clear-all [this]
    (reset! (:entries this) {})
    (reset! (:access-order this) []))

  (all-keys [this]
    (keys @(:entries this)))

  (entry-count [this]
    (count @(:entries this)))

  (stats [this]
    (let [entries @(:entries this)
          stats-data @(:stats this)]
      (merge
       stats-data
       {:entry-count (count entries)
        :size-bytes (reduce + 0 (map :size (vals entries)))
        :hit-rate (core/hit-rate stats-data)}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-lru-cache
  "Create an LRU cache.

   Options:
     :max-entries    - Maximum number of entries (default: 1000)
     :max-size-bytes - Maximum total size in bytes (default: 50MB)
     :default-ttl    - Default TTL in milliseconds (default: 5 minutes)"
  [{:keys [max-entries max-size-bytes default-ttl]
    :or {max-entries 1000
         max-size-bytes (* 50 1024 1024)
         default-ttl (* 5 60 1000)}}]
  (->LRUCache
   (atom {})
   (atom [])
   max-entries
   max-size-bytes
   default-ttl
   (atom {:hits 0 :misses 0 :evictions 0 :writes 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Convenience Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn cache-get
  "Get value from cache. Returns nil if not found or expired."
  [cache key]
  (when-let [entry (core/get-entry cache key)]
    (:value entry)))

(defn cache-set!
  "Set value in cache."
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
  "Get value from cache, or compute and cache if not found.

   Example:
   (cache-get-or-compute cache \"key\"
     (fn [] (expensive-computation)))"
  [cache key compute-fn]
  (if-let [value (cache-get cache key)]
    value
    (let [value (compute-fn)]
      (cache-set! cache key value)
      value)))

(defn cache-get-or-compute-async
  "Get value from cache, or compute asynchronously and cache if not found.
   Returns a channel that will receive the value."
  [cache key compute-fn]
  (clojure.core.async/go
    (if-let [value (cache-get cache key)]
      value
      (let [value (compute-fn)]
        (cache-set! cache key value)
        value))))
