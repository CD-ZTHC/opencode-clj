(ns anima-agent-clj.context.manager
  "Context Manager implementation for the Agent Cluster Architecture.

   Provides unified access to tiered context storage with:
   - Automatic tier promotion/demotion
   - Context lifecycle management
   - Snapshot and restore capabilities
   - Statistics and monitoring

   Usage:
   (def manager (create-manager {:enable-l2 true}))
   (set-context! manager \"session:123\" {:history []})
   (get-context manager \"session:123\")

   Context Key Naming Convention:
   - session:{session-id}          - Session context
   - session:{session-id}:history  - Session history
   - task:{task-id}                - Task context
   - task:{task-id}:result         - Task result
   - agent:{agent-id}              - Agent context
   - agent:{agent-id}:memory       - Agent memory"
  (:require [anima-agent-clj.context.core :as core]
            [anima-agent-clj.context.storage :as storage]
            [clojure.core.async :as async])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Context Manager Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ContextManager
           [id              ; Manager ID
            tiered-storage  ; TieredStorage instance
            snapshots       ; atom<map> - snapshot-id -> ContextSnapshot
            config          ; Configuration
            stats           ; atom<map> - Statistics
            gc-loop]        ; atom - Background GC loop

  core/IContextManager

  (get-context [this key]
    (when-let [entry (core/get-entry (:l1 (:tiered-storage this)) key)]
      (:value entry)))

  (get-context-info [this key]
    ;; Try L1 first, then L2, then L3
    (or
     (core/get-entry (:l1 (:tiered-storage this)) key)
     (when-let [l2 (:l2 (:tiered-storage this))]
       (when-let [entry (core/get-entry l2 key)]
         entry))
     (when-let [l3 (:l3 (:tiered-storage this))]
       (when-let [entry (core/get-entry l3 key)]
         entry))))

  (set-context! [this key value]
    (core/set-context-with-opts! this key value {}))

  (set-context-with-opts! [this key value opts]
    ;; Always write to L1
    (let [entry (core/set-entry (:l1 (:tiered-storage this)) key value opts)]
      (swap! (:stats this) update :writes inc)
      entry))

  (delete-context! [this key]
    (let [deleted (or
                   (core/delete-entry (:l1 (:tiered-storage this)) key)
                   (when-let [l2 (:l2 (:tiered-storage this))]
                     (core/delete-entry l2 key))
                   (when-let [l3 (:l3 (:tiered-storage this))]
                     (core/delete-entry l3 key)))]
      (when deleted
        (swap! (:stats this) update :deletes inc))
      deleted))

  (touch-context! [this key]
    (when-let [entry (core/get-context-info this key)]
      (swap! (:stats this) update :touches inc)
      entry))

  (get-or-create [this key default-fn]
    (if-let [entry (core/get-context-info this key)]
      (:value entry)
      (let [value (default-fn)]
        (core/set-context! this key value)
        value)))

  (snapshot [this key]
    (when-let [entry (core/get-context-info this key)]
      (let [snapshot-id (str (UUID/randomUUID))
            snapshot (core/->ContextSnapshot
                      snapshot-id
                      key
                      (:value entry)
                      (Date.)
                      {:source-tier (:tier entry)})]
        (swap! (:snapshots this) assoc snapshot-id snapshot)
        snapshot)))

  (restore [this snapshot-id]
    (when-let [snapshot (get @(:snapshots this) snapshot-id)]
      (core/set-context! this (:key snapshot) (:value snapshot))
      snapshot))

  (list-keys [this pattern]
    (concat
     (core/keys-pattern (:l1 (:tiered-storage this)) pattern)
     (when-let [l2 (:l2 (:tiered-storage this))]
       (core/keys-pattern l2 pattern))
     (when-let [l3 (:l3 (:tiered-storage this))]
       (core/keys-pattern l3 pattern))))

  (get-stats [this]
    (merge
     @(:stats this)
     {:l1 {:entries (core/entry-count (:l1 (:tiered-storage this)))
           :size-bytes (core/size-bytes (:l1 (:tiered-storage this)))}
      :l2 (when-let [l2 (:l2 (:tiered-storage this))]
            {:entries (core/entry-count l2)
             :size-bytes (core/size-bytes l2)})
      :l3 (when-let [l3 (:l3 (:tiered-storage this))]
            {:entries (core/entry-count l3)
             :size-bytes (core/size-bytes l3)})
      :snapshots (count @(:snapshots this))})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Garbage Collection
;; ══════════════════════════════════════════════════════════════════════════════

(defn- gc-expired-entries
  "Remove expired entries from all tiers."
  [manager]
  (let [ts (:tiered-storage manager)
        now (System/currentTimeMillis)]
    ;; Check L1 entries
    (doseq [[key entry] @(get-in ts [:l1 :entries])]
      (when (core/is-expired? entry)
        (core/delete-entry (:l1 ts) key)))
    ;; L2 entries are checked on access
    ))

(defn- gc-old-snapshots
  "Remove old snapshots."
  [manager max-age-ms]
  (let [now (System/currentTimeMillis)]
    (swap! (:snapshots manager)
           (fn [snaps]
             (into {} (filter (fn [[_ snap]]
                                (< (- now (.getTime (:timestamp snap)))
                                   max-age-ms))
                              snaps))))))

(defn- start-gc-loop
  "Start background garbage collection loop."
  [manager interval-ms]
  (async/go-loop []
    (async/<! (async/timeout interval-ms))
    (when-let [loop-atom (:gc-loop manager)]
      (when @loop-atom
        (try
          (gc-expired-entries manager)
          (gc-old-snapshots manager (* 24 60 60 1000)) ; 24 hours
          (catch Exception e
            (println "Context GC error:" (.getMessage e))))
        (recur)))))

(defn- stop-gc-loop
  "Stop the garbage collection loop."
  [manager]
  (when-let [loop-atom (:gc-loop manager)]
    (reset! loop-atom nil)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Context Operations
;; ══════════════════════════════════════════════════════════════════════════════

(defn update-context!
  "Update context value by applying a function."
  [manager key update-fn]
  (let [current (core/get-context manager key)
        updated (update-fn current)]
    (core/set-context! manager key updated)))

(defn merge-context!
  "Merge data into existing context."
  [manager key data]
  (update-context! manager key #(merge % data)))

(defn get-session-context
  "Get context for a session."
  [manager session-id]
  (core/get-context manager (str "session:" session-id)))

(defn set-session-context!
  "Set context for a session."
  [manager session-id context]
  (core/set-context! manager (str "session:" session-id) context))

(defn get-session-history
  "Get history for a session."
  [manager session-id]
  (or (core/get-context manager (str "session:" session-id ":history")) []))

(defn add-to-session-history!
  "Add an entry to session history."
  [manager session-id entry]
  (let [history-key (str "session:" session-id ":history")]
    (update-context! manager history-key
                     (fn [history]
                       (conj (or history []) entry)))))

(defn get-task-context
  "Get context for a task."
  [manager task-id]
  (core/get-context manager (str "task:" task-id)))

(defn set-task-context!
  "Set context for a task."
  [manager task-id context]
  (core/set-context! manager (str "task:" task-id) context))

(defn get-task-result
  "Get result for a task."
  [manager task-id]
  (core/get-context manager (str "task:" task-id ":result")))

(defn set-task-result!
  "Set result for a task."
  [manager task-id result]
  (core/set-context! manager (str "task:" task-id ":result") result))

(defn get-agent-memory
  "Get memory for an agent."
  [manager agent-id]
  (core/get-context manager (str "agent:" agent-id ":memory")))

(defn set-agent-memory!
  "Set memory for an agent."
  [manager agent-id memory]
  (core/set-context! manager (str "agent:" agent-id ":memory") memory))

(defn clear-session-context!
  "Clear all context for a session."
  [manager session-id]
  (let [pattern (str "session:" session-id "*")]
    (doseq [key (core/list-keys manager pattern)]
      (core/delete-context! manager key))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor & Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-manager
  "Create a new ContextManager.

   Options:
     :enable-l2              - Enable L2 file storage (default: true)
     :enable-l3              - Enable L3 external storage (default: false)
     :enable-gc              - Enable garbage collection (default: true)
     :gc-interval-ms         - GC interval in milliseconds (default: 60000)
     :l1-max-entries         - L1 max entries (default: 10000)
     :l1-max-size-bytes      - L1 max size (default: 100MB)
     :l2-base-path           - L2 base path (default: \".opencode/context\")
     :l3-config              - L3 configuration map
     :promotion-threshold    - Access count for promotion (default: 5)
     :demotion-threshold-ms  - Time before demotion (default: 30 minutes)"
  [{:keys [enable-l2 enable-l3 enable-gc gc-interval-ms
           l1-max-entries l1-max-size-bytes
           l2-base-path l3-config
           promotion-threshold demotion-threshold-ms]
    :or {enable-l2 true
         enable-l3 false
         enable-gc true
         gc-interval-ms 60000
         l1-max-entries 10000
         l1-max-size-bytes (* 100 1024 1024)
         l2-base-path ".opencode/context"
         promotion-threshold 5
         demotion-threshold-ms (* 30 60 1000)}}]
  (let [tiered-storage (storage/create-tiered-storage
                         {:enable-l2 enable-l2
                          :enable-l3 enable-l3
                          :l1-max-entries l1-max-entries
                          :l1-max-size-bytes l1-max-size-bytes
                          :l2-base-path l2-base-path
                          :l3-config l3-config
                          :promotion-threshold promotion-threshold
                          :demotion-threshold-ms demotion-threshold-ms})
        manager (->ContextManager
                 (str (UUID/randomUUID))
                 tiered-storage
                 (atom {})
                 {:enable-gc enable-gc
                  :gc-interval-ms gc-interval-ms}
                 (atom {:reads 0 :writes 0 :deletes 0 :touches 0
                        :promotions 0 :demotions 0 :gc-runs 0})
                 (atom true))]
    ;; Start GC loop if enabled
    (when enable-gc
      (start-gc-loop manager gc-interval-ms))
    manager))

(defn close-manager
  "Close the context manager and release resources."
  [manager]
  (stop-gc-loop manager)
  ;; Clear L1 storage
  (core/clear-all (:l1 (:tiered-storage manager)))
  manager)

(defn manager-status
  "Get manager status."
  [manager]
  (merge
   {:id (:id manager)
    :status (if @(:gc-loop manager) :running :stopped)}
   (core/get-stats manager)))
