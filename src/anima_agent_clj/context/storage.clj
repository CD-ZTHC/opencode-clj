(ns anima-agent-clj.context.storage
  "Tiered storage implementation for Context Management System.

   Implements L1 (Memory), L2 (File), and L3 (External) storage tiers
   with automatic promotion/demotion based on access patterns.

   L1 - Memory Storage:
   - Fastest access (sub-microsecond)
   - Limited capacity (configurable)
   - LRU eviction policy

   L2 - File Storage:
   - Persistent storage
   - JSON/EDN serialization
   - Moderate access speed

   L3 - External Storage:
   - Remote or archival storage
   - Slowest access
   - Optional (plugin-based)"
  (:require [anima-agent-clj.context.core :as core]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as string])
  (:import [java.util UUID Date]
           [java.io File]
           [java.nio.file Files Paths]))

;; ══════════════════════════════════════════════════════════════════════════════
;; L1 - Memory Storage
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord MemoryStorage
           [entries ; atom<map> - key -> ContextEntry
            max-entries ; Maximum number of entries
            max-size-bytes ; Maximum total size in bytes
            lru-list ; atom<vector> - LRU ordering
            stats] ; atom<map> - Statistics

  core/IContextStorage

  (get-entry [this key]
    (when-let [entry (get @(:entries this) key)]
      (when-not (core/is-expired? entry)
        ;; Update access info
        (swap! (:entries this) update key
               (fn [e]
                 (-> e
                     (assoc :accessed-at (Date.))
                     (update :access-count inc))))
        ;; Update LRU
        (swap! (:lru-list this)
               (fn [lst] (vec (concat (remove #(= % key) lst) [key]))))
        entry)))

  (set-entry [this key value opts]
    (let [size (core/estimate-size value)
          entry (core/make-context-entry key value
                                         (merge opts {:tier :l1 :size size}))]
      ;; Evict if necessary
      (when (and (:max-entries this)
                 (>= (count @(:entries this)) (:max-entries this)))
        (let [evict-key (first @(:lru-list this))]
          (swap! (:entries this) dissoc evict-key)
          (swap! (:lru-list this) (fn [lst] (vec (rest lst))))))
      ;; Store entry
      (swap! (:entries this) assoc key entry)
      (swap! (:lru-list this)
             (fn [lst] (vec (concat (remove #(= % key) lst) [key]))))
      entry))

  (delete-entry [this key]
    (if (contains? @(:entries this) key)
      (do
        (swap! (:entries this) dissoc key)
        (swap! (:lru-list this) (fn [lst] (vec (remove #(= % key) lst))))
        true)
      false))

  (has-key? [this key]
    (contains? @(:entries this) key))

  (keys-pattern [this pattern]
    (filter #(core/matches-pattern? % pattern) (keys @(:entries this))))

  (clear-all [this]
    (reset! (:entries this) {})
    (reset! (:lru-list this) []))

  (entry-count [this]
    (count @(:entries this)))

  (size-bytes [this]
    (reduce + 0 (map :size (vals @(:entries this))))))

(defn create-memory-storage
  "Create an L1 memory storage instance.

   Options:
     :max-entries    - Maximum number of entries (default: 10000)
     :max-size-bytes - Maximum total size in bytes (default: 100MB)"
  [{:keys [max-entries max-size-bytes]
    :or {max-entries 10000
         max-size-bytes (* 100 1024 1024)}}]
  (->MemoryStorage
   (atom {})
   max-entries
   max-size-bytes
   (atom [])
   (atom {:hits 0 :misses 0 :evictions 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; L2 - File Storage
;; ══════════════════════════════════════════════════════════════════════════════

(defn- key-to-filename
  "Convert context key to safe filename."
  [key]
  (-> key
      (string/replace "/" "_SLASH_")
      (string/replace "\\" "_BSLASH_")
      (string/replace ":" "_COLON_")))

(defn- filename-to-key
  "Convert filename back to context key."
  [filename]
  (-> filename
      (string/replace "_SLASH_" "/")
      (string/replace "_BSLASH_" "\\")
      (string/replace "_COLON_" ":")))

(defn- ensure-directory
  "Ensure directory exists."
  [path]
  (let [dir (io/file path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    dir))

(defrecord FileStorage
           [base-path ; Base directory for storage
            format ; :edn or :json
            entries ; atom<map> - key -> ContextEntry (metadata cache)
            stats] ; atom<map> - Statistics

  core/IContextStorage

  (get-entry [this key]
    (try
      (let [filename (str (key-to-filename key) ".edn")
            file (io/file (:base-path this) filename)]
        (when (.exists file)
          (let [content (slurp file)
                entry (edn/read-string content)]
            (if (core/is-expired? entry)
              (do
                (.delete file)
                nil)
              (do
                ;; Update metadata cache
                (swap! (:entries this) assoc key entry)
                entry)))))
      (catch Exception e
        nil)))

  (set-entry [this key value opts]
    (try
      (let [size (core/estimate-size value)
            entry (core/make-context-entry key value
                                           (merge opts {:tier :l2 :size size}))
            filename (str (key-to-filename key) ".edn")
            file (io/file (:base-path this) filename)]
        (ensure-directory (:base-path this))
        (spit file (pr-str entry))
        ;; Update metadata cache
        (swap! (:entries this) assoc key entry)
        entry)
      (catch Exception e
        (println "FileStorage error:" (.getMessage e))
        nil)))

  (delete-entry [this key]
    (try
      (let [filename (str (key-to-filename key) ".edn")
            file (io/file (:base-path this) filename)]
        (if (.exists file)
          (do
            (.delete file)
            (swap! (:entries this) dissoc key)
            true)
          false))
      (catch Exception e
        false)))

  (has-key? [this key]
    (let [filename (str (key-to-filename key) ".edn")
          file (io/file (:base-path this) filename)]
      (.exists file)))

  (keys-pattern [this pattern]
    (let [dir (io/file (:base-path this))]
      (when (.exists dir)
        (->> (.listFiles dir)
             (filter #(.isFile %))
             (map #(.getName %))
             (filter #(string/ends-with? % ".edn"))
             (map #(subs % 0 (- (count %) 4)))
             (map filename-to-key)
             (filter #(core/matches-pattern? % pattern))))))

  (clear-all [this]
    (let [dir (io/file (:base-path this))]
      (when (.exists dir)
        (doseq [file (.listFiles dir)]
          (when (.isFile file)
            (.delete file)))))
    (reset! (:entries this) {}))

  (entry-count [this]
    (let [dir (io/file (:base-path this))]
      (if (.exists dir)
        (count (filter #(.isFile %) (.listFiles dir)))
        0)))

  (size-bytes [this]
    (let [dir (io/file (:base-path this))]
      (if (.exists dir)
        (reduce + 0 (map #(.length %) (filter #(.isFile %) (.listFiles dir))))
        0))))

(defn create-file-storage
  "Create an L2 file storage instance.

   Options:
     :base-path - Directory for storage (default: \".opencode/context\")
     :format    - Serialization format :edn or :json (default: :edn)"
  [{:keys [base-path format]
    :or {base-path ".opencode/context"
         format :edn}}]
  (ensure-directory base-path)
  (->FileStorage
   base-path
   format
   (atom {})
   (atom {:reads 0 :writes 0 :deletes 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; L3 - External Storage (Stub/Interface)
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ExternalStorage
           [config ; Storage configuration
            connector ; Optional connector function
            stats] ; atom<map> - Statistics

  core/IContextStorage

  (get-entry [this key]
    ;; Placeholder for external storage implementation
    ;; Can be extended with S3, Redis, Database backends
    (when-let [connector-fn (:connector this)]
      (try
        (connector-fn :get key)
        (catch Exception e
          nil))))

  (set-entry [this key value opts]
    (when-let [connector-fn (:connector this)]
      (try
        (let [entry (core/make-context-entry key value
                                             (merge opts {:tier :l3}))]
          (connector-fn :set key entry)
          entry)
        (catch Exception e
          nil))))

  (delete-entry [this key]
    (when-let [connector-fn (:connector this)]
      (try
        (connector-fn :delete key)
        true
        (catch Exception e
          false))))

  (has-key? [this key]
    (when-let [connector-fn (:connector this)]
      (try
        (connector-fn :exists key)
        (catch Exception e
          false))))

  (keys-pattern [this pattern]
    (when-let [connector-fn (:connector this)]
      (try
        (connector-fn :list pattern)
        (catch Exception e
          []))))

  (clear-all [this]
    (when-let [connector-fn (:connector this)]
      (try
        (connector-fn :clear)
        (catch Exception e
          nil))))

  (entry-count [this]
    (when-let [connector-fn (:connector this)]
      (try
        (connector-fn :count)
        (catch Exception e
          0))))

  (size-bytes [this]
    (when-let [connector-fn (:connector this)]
      (try
        (connector-fn :size)
        (catch Exception e
          0)))))

(defn create-external-storage
  "Create an L3 external storage instance.

   Options:
     :type      - Storage type (:s3, :redis, :database, :custom)
     :connector - Custom connector function for :custom type
     :config    - Storage-specific configuration"
  [{:keys [type connector config]
    :or {type :custom}}]
  (->ExternalStorage
   config
   connector
   (atom {:reads 0 :writes 0 :errors 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Tiered Storage Manager
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord TieredStorage
           [l1 ; L1 MemoryStorage
            l2 ; L2 FileStorage
            l3 ; L3 ExternalStorage (optional)
            config ; Configuration
            stats] ; atom<map> - Overall statistics

  core/ITieredStorage

  (promote [this key]
    ;; Promote from lower tier to higher
    (cond
      ;; L3 -> L2
      (and (:l3 this) (core/has-key? (:l3 this) key))
      (when-let [entry (core/get-entry (:l3 this) key)]
        (core/set-entry (:l2 this) key (:value entry) (dissoc entry :value :tier))
        (core/delete-entry (:l3 this) key)
        :l2)

      ;; L2 -> L1
      (core/has-key? (:l2 this) key)
      (when-let [entry (core/get-entry (:l2 this) key)]
        (core/set-entry (:l1 this) key (:value entry) (dissoc entry :value :tier))
        :l1)

      :else nil))

  (demote [this key]
    ;; Demote from higher tier to lower
    (cond
      ;; L1 -> L2
      (core/has-key? (:l1 this) key)
      (when-let [entry (core/get-entry (:l1 this) key)]
        (core/set-entry (:l2 this) key (:value entry) (dissoc entry :value :tier))
        (core/delete-entry (:l1 this) key)
        :l2)

      ;; L2 -> L3
      (and (:l3 this) (core/has-key? (:l2 this) key))
      (when-let [entry (core/get-entry (:l2 this) key)]
        (core/set-entry (:l3 this) key (:value entry) (dissoc entry :value :tier))
        (core/delete-entry (:l2 this) key)
        :l3)

      :else nil))

  (get-tier [this key]
    (cond
      (core/has-key? (:l1 this) key) :l1
      (core/has-key? (:l2 this) key) :l2
      (and (:l3 this) (core/has-key? (:l3 this) key)) :l3
      :else nil))

  (should-promote? [this entry]
    ;; Promote if frequently accessed
    (let [access-count (:access-count entry)
          threshold (get-in this [:config :promotion-threshold] 5)]
      (>= access-count threshold)))

  (should-demote? [this entry]
    ;; Demote if not accessed recently
    (let [last-access (.getTime (:accessed-at entry))
          now (System/currentTimeMillis)
          threshold-ms (get-in this [:config :demotion-threshold-ms]
                               (* 30 60 1000))] ; 30 minutes default
      (> (- now last-access) threshold-ms))))

(defn create-tiered-storage
  "Create a tiered storage manager.

   Options:
     :enable-l2              - Enable L2 file storage (default: true)
     :enable-l3              - Enable L3 external storage (default: false)
     :l1-max-entries         - L1 max entries (default: 10000)
     :l1-max-size-bytes      - L1 max size (default: 100MB)
     :l2-base-path           - L2 base path (default: \".opencode/context\")
     :l3-config              - L3 configuration map
     :promotion-threshold    - Access count for promotion (default: 5)
     :demotion-threshold-ms  - Time before demotion (default: 30 minutes)"
  [{:keys [enable-l2 enable-l3
           l1-max-entries l1-max-size-bytes
           l2-base-path l3-config
           promotion-threshold demotion-threshold-ms]
    :or {enable-l2 true
         enable-l3 false
         l1-max-entries 10000
         l1-max-size-bytes (* 100 1024 1024)
         l2-base-path ".opencode/context"
         promotion-threshold 5
         demotion-threshold-ms (* 30 60 1000)}}]
  (let [l1 (create-memory-storage {:max-entries l1-max-entries
                                   :max-size-bytes l1-max-size-bytes})
        l2 (when enable-l2
             (create-file-storage {:base-path l2-base-path}))
        l3 (when enable-l3
             (create-external-storage (or l3-config {})))]
    (->TieredStorage
     l1
     l2
     l3
     {:promotion-threshold promotion-threshold
      :demotion-threshold-ms demotion-threshold-ms}
     (atom {:promotions 0 :demotions 0}))))
