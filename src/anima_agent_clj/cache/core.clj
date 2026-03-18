(ns anima-agent-clj.cache.core
  "Core definitions for the Result Cache System.

   The cache provides intelligent caching for:
   - API responses (OpenCode API calls)
   - Computed results (task outputs)
   - Session data (conversation state)

   Features:
   - Multiple eviction policies (LRU, TTL, LFU)
   - Configurable size limits
   - Statistics and hit-rate tracking
   - Thread-safe operations

   Usage:
   (def cache (create-cache {:max-entries 1000 :ttl 300000}))
   (cache-set! cache \"key\" value)
   (cache-get cache \"key\")
   (cache-get-or-compute cache \"key\" compute-fn)"
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Cache Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord CacheEntry
           [key              ; Cache key
            value            ; Cached value
            created-at       ; Creation timestamp
            accessed-at      ; Last access timestamp
            access-count     ; Number of accesses
            ttl              ; Time-to-live in milliseconds (nil = no expiry)
            size             ; Approximate size in bytes
            metadata])       ; Additional metadata

(defrecord CacheStats
           [hits             ; Cache hits
            misses           ; Cache misses
            evictions        ; Evictions count
            size-bytes       ; Current size in bytes
            entry-count])    ; Current entry count

;; ══════════════════════════════════════════════════════════════════════════════
;; Cache Protocol
;; ══════════════════════════════════════════════════════════════════════════════

(defprotocol ICache
  "Protocol for cache implementations."

  (get-entry [this key]
    "Get cache entry by key. Returns nil if not found or expired.")

  (set-entry [this key value opts]
    "Set cache entry with optional TTL and metadata.")

  (delete-entry [this key]
    "Delete cache entry. Returns true if deleted.")

  (has-key? [this key]
    "Check if key exists and is not expired.")

  (clear-all [this]
    "Clear all cache entries.")

  (all-keys [this]
    "Get all cache keys.")

  (entry-count [this]
    "Get current cache size (entry count).")

  (stats [this]
    "Get cache statistics."))

(defprotocol IEvictionPolicy
  "Protocol for cache eviction policies."

  (select-for-eviction [this]
    "Select key for eviction based on policy.")

  (on-access [this key]
    "Called when key is accessed.")

  (on-set [this key]
    "Called when key is set.")

  (on-delete [this key]
    "Called when key is deleted."))

;; ══════════════════════════════════════════════════════════════════════════════
;; Helper Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn make-cache-entry
  "Create a new cache entry."
  ([key value]
   (make-cache-entry key value {}))
  ([key value {:keys [ttl metadata size]
               :or {metadata {}
                    size (count (str value))}}]
   (let [now (Date.)]
     (->CacheEntry
      key
      value
      now
      now
      0
      ttl
      size
      metadata))))

(defn is-expired?
  "Check if cache entry has expired."
  [entry]
  (when-let [ttl (:ttl entry)]
    (let [now (System/currentTimeMillis)
          expiry (+ (.getTime (:created-at entry)) ttl)]
      (> now expiry))))

(defn estimate-size
  "Estimate size of value in bytes."
  [value]
  (cond
    (nil? value) 0
    (string? value) (.length value)
    (number? value) 8
    (keyword? value) (count (str value))
    (map? value) (reduce + 0 (map (fn [[k v]]
                                    (+ (estimate-size k) (estimate-size v)))
                                  value))
    (coll? value) (reduce + 0 (map estimate-size value))
    :else (count (str value))))

(defn hit-rate
  "Calculate cache hit rate."
  [stats]
  (let [hits (:hits stats)
        misses (:misses stats)
        total (+ hits misses)]
    (if (pos? total)
      (double (/ hits total))
      0.0)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Key Generation
;; ══════════════════════════════════════════════════════════════════════════════

(defn make-api-cache-key
  "Generate cache key for API calls."
  [session-id content]
  (str "api:" session-id ":" (hash content)))

(defn make-task-cache-key
  "Generate cache key for task results."
  [task-type payload]
  (str "task:" task-type ":" (hash payload)))

(defn make-session-cache-key
  "Generate cache key for session data."
  [session-id data-type]
  (str "session:" session-id ":" data-type))
