(ns anima-agent-clj.context.core
  "Core definitions for the Context Management System.

   The Context Management System provides hierarchical context storage
   with L1/L2/L3 tier architecture:

   L1 - Memory Storage: Hot data, fastest access, volatile
   L2 - File Storage: Warm data, persistent, moderate access
   L3 - External Storage: Cold data, archival, slowest access

   Context Types:
   - SessionContext: Conversation state and history
   - TaskContext: Task execution state and results
   - AgentContext: Agent internal state and memory

   Usage:
   (def manager (context-manager/create-manager {:enable-l2 true}))
   (context-manager/set-context! manager \"session:123\" {:history [...]})
   (context-manager/get-context manager \"session:123\")"
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Context Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ContextEntry
           [key              ; Context key (hierarchical, e.g., \"session:123:history\")
            value            ; Context value
            metadata         ; {:type :session :created-at ... :ttl 300000}
            tier             ; :l1, :l2, :l3 - Current storage tier
            created-at       ; Creation timestamp
            updated-at       ; Last update timestamp
            accessed-at      ; Last access timestamp
            access-count     ; Number of times accessed
            ttl              ; Time-to-live in milliseconds (nil = no expiry)
            size])           ; Approximate size in bytes

(defrecord ContextSnapshot
           [id               ; Snapshot ID
            key              ; Original context key
            value            ; Snapshot value
            timestamp        ; When snapshot was taken
            metadata])       ; Additional info

;; ══════════════════════════════════════════════════════════════════════════════
;; Storage Protocol
;; ══════════════════════════════════════════════════════════════════════════════

(defprotocol IContextStorage
  "Protocol for context storage backends."

  (get-entry [this key]
    "Get a ContextEntry by key. Returns nil if not found.")

  (set-entry [this key value opts]
    "Set a context entry. Options: {:ttl ms :metadata {...}}")

  (delete-entry [this key]
    "Delete a context entry. Returns true if deleted.")

  (has-key? [this key]
    "Check if key exists.")

  (keys-pattern [this pattern]
    "Get all keys matching pattern (glob-style).")

  (clear-all [this]
    "Clear all entries in this storage.")

  (entry-count [this]
    "Get total number of entries.")

  (size-bytes [this]
    "Get approximate total size in bytes."))

(defprotocol ITieredStorage
  "Protocol for tiered storage promotion/demotion."

  (promote [this key]
    "Promote entry to higher tier (L3 -> L2 -> L1).")

  (demote [this key]
    "Demote entry to lower tier (L1 -> L2 -> L3).")

  (get-tier [this key]
    "Get current tier for a key.")

  (should-promote? [this entry]
    "Check if entry should be promoted based on access patterns.")

  (should-demote? [this entry]
    "Check if entry should be demoted based on access patterns."))

;; ══════════════════════════════════════════════════════════════════════════════
;; Context Manager Protocol
;; ══════════════════════════════════════════════════════════════════════════════

(defprotocol IContextManager
  "Protocol for context management."

  (get-context [this key]
    "Get context value by key.")

  (get-context-info [this key]
    "Get full ContextEntry with metadata.")

  (set-context! [this key value]
    "Set context value.")

  (set-context-with-opts! [this key value opts]
    "Set context with options (ttl, metadata, etc.).")

  (delete-context! [this key]
    "Delete context.")

  (touch-context! [this key]
    "Update access timestamp.")

  (get-or-create [this key default-fn]
    "Get context or create with default-fn if not exists.")

  (snapshot [this key]
    "Create a snapshot of current context.")

  (restore [this snapshot-id]
    "Restore context from snapshot.")

  (list-keys [this pattern]
    "List all keys matching pattern.")

  (get-stats [this]
    "Get context manager statistics."))

;; ══════════════════════════════════════════════════════════════════════════════
;; Helper Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn make-context-entry
  "Create a new ContextEntry."
  ([key value]
   (make-context-entry key value {}))
  ([key value {:keys [ttl metadata tier size]
               :or {tier :l1
                    metadata {}
                    size (count (str value))}}]
   (let [now (Date.)]
     (->ContextEntry
      key
      value
      metadata
      tier
      now
      now
      now
      0
      ttl
      size))))

(defn key-prefix
  "Extract the prefix from a hierarchical key."
  [key]
  (when-let [idx (clojure.string/index-of key ":")]
    (subs key 0 idx)))

(defn key-parts
  "Split hierarchical key into parts."
  [key]
  (clojure.string/split key #":"))

(defn matches-pattern?
  "Check if key matches a glob pattern (* and ? supported)."
  [key pattern]
  (let [regex (-> pattern
                  (clojure.string/replace "." "\\.")
                  (clojure.string/replace "*" ".*")
                  (clojure.string/replace "?" "."))
        pattern-re (re-pattern (str "^" regex "$"))]
    (boolean (re-matches pattern-re key))))

(defn context-type
  "Determine context type from key."
  [key]
  (let [prefix (key-prefix key)]
    (case prefix
      "session" :session
      "task" :task
      "agent" :agent
      "cache" :cache
      :unknown)))

(defn is-expired?
  "Check if a context entry has expired."
  [entry]
  (when-let [ttl (:ttl entry)]
    (let [now (System/currentTimeMillis)
          expiry (+ (.getTime (:updated-at entry)) ttl)]
      (> now expiry))))

(defn estimate-size
  "Estimate size of a value in bytes."
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
