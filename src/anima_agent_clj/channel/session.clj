(ns anima-agent-clj.channel.session
  "Session management for multi-conversation support.

   Sessions represent independent conversation contexts, each with:
   - Unique session-id for routing
   - Context for conversation state
   - Optional routing-key for message routing

   Routing key patterns:
   - opencode.session.{session-id}  -> Direct to specific session
   - opencode.user.{user-id}        -> To user's active session
   - opencode.broadcast             -> To all active sessions"
  (:require [anima-agent-clj.channel :as ch]
            [clojure.string :as str])
  (:import [java.util UUID Date]))

;; ════════════════════════════════════════════════════════════════════════════
;; Session Record
;; ════════════════════════════════════════════════════════════════════════════

(defrecord Session
           [id              ; Unique session ID
            channel         ; Channel name (cli, rabbitmq, etc.)
            routing-key     ; RabbitMQ routing key for this session
            context         ; Conversation context {:history [...] :state {...}}
            metadata        ; Additional metadata
            created-at      ; Creation timestamp
            last-active])   ; Last activity timestamp

(defn create-session-record
  "Create a new Session record."
  ([channel]
   (create-session-record channel {}))
  ([channel {:keys [id routing-key context metadata]
             :or {context {}
                  metadata {}}}]
   (let [session-id (or id (str (UUID/randomUUID)))
         now (.getTime (Date.))]
     (->Session
      session-id
      channel
      (or routing-key (ch/make-session-routing-key session-id))
      context
      metadata
      now
      now))))

;; ════════════════════════════════════════════════════════════════════════════
;; Session Store
;; ════════════════════════════════════════════════════════════════════════════

(defrecord SessionStore
           [sessions        ; {session-id -> Session}
            by-routing-key  ; {routing-key -> session-id}
            by-channel])    ; {channel-name -> {account-id -> #{session-id ...}}}

(defn create-store
  "Create an empty SessionStore."
  []
  (->SessionStore
   (atom {})
   (atom {})
   (atom {})))

;; ════════════════════════════════════════════════════════════════════════════
;; Session CRUD Operations
;; ════════════════════════════════════════════════════════════════════════════

(defn create-session
  "Create and register a new session.
   Returns the new Session."
  ([store channel]
   (create-session store channel {}))
  ([store channel {:keys [account-id] :as opts}]
   (let [session (create-session-record channel opts)
         session-id (:id session)
         routing-key (:routing-key session)
         account-id (or account-id "default")]
     ;; Add to sessions index
     (swap! (:sessions store) assoc session-id session)
     ;; Add to routing-key index
     (swap! (:by-routing-key store) assoc routing-key session-id)
     ;; Add to channel index
     (swap! (:by-channel store)
            update-in [channel account-id]
            (fnil conj #{}) session-id)
     session)))

(defn get-session
  "Get session by ID."
  [store session-id]
  (get @(:sessions store) session-id))

(defn get-session-by-routing-key
  "Get session by RabbitMQ routing-key."
  [store routing-key]
  (when-let [session-id (get @(:by-routing-key store) routing-key)]
    (get-session store session-id)))

(defn get-sessions-by-channel
  "Get all sessions for a channel and optional account."
  ([store channel]
   (get-sessions-by-channel store channel "default"))
  ([store channel account-id]
   (when-let [session-ids (get-in @(:by-channel store) [channel account-id])]
     (map #(get-session store %) session-ids))))

(defn get-all-sessions
  "Get all active sessions."
  [store]
  (vals @(:sessions store)))

(defn session-exists?
  "Check if session exists."
  [store session-id]
  (contains? @(:sessions store) session-id))

;; ════════════════════════════════════════════════════════════════════════════
;; Session Context Operations
;; ════════════════════════════════════════════════════════════════════════════

(defn update-session-context
  "Update session context (merges with existing)."
  [store session-id context-update]
  (when-let [session (get-session store session-id)]
    (let [updated-session (-> session
                              (update :context merge context-update)
                              (assoc :last-active (.getTime (Date.))))]
      (swap! (:sessions store) assoc session-id updated-session)
      updated-session)))

(defn set-session-context
  "Replace session context entirely."
  [store session-id new-context]
  (when-let [session (get-session store session-id)]
    (let [updated-session (-> session
                              (assoc :context new-context)
                              (assoc :last-active (.getTime (Date.))))]
      (swap! (:sessions store) assoc session-id updated-session)
      updated-session)))

(defn add-to-history
  "Add a message to session history."
  [store session-id {:keys [role content timestamp] :as message}]
  (when-let [session (get-session store session-id)]
    (let [history-entry (merge {:role role
                                :content content
                                :timestamp (or timestamp (.getTime (Date.)))}
                               (dissoc message :role :content :timestamp))
          updated-session (-> session
                              (update-in [:context :history]
                                         (fnil conj []) history-entry)
                              (assoc :last-active (.getTime (Date.))))]
      (swap! (:sessions store) assoc session-id updated-session)
      updated-session)))

(defn get-history
  "Get session message history."
  [store session-id]
  (when-let [session (get-session store session-id)]
    (get-in session [:context :history] [])))

;; ════════════════════════════════════════════════════════════════════════════
;; Session Lifecycle
;; ════════════════════════════════════════════════════════════════════════════

(defn touch-session
  "Update last-active timestamp."
  [store session-id]
  (when-let [session (get-session store session-id)]
    (let [updated-session (assoc session :last-active (.getTime (Date.)))]
      (swap! (:sessions store) assoc session-id updated-session)
      updated-session)))

(defn close-session
  "Close and remove a session."
  [store session-id]
  (when-let [session (get-session store session-id)]
    (let [channel (:channel session)
          routing-key (:routing-key session)]
      ;; Remove from sessions index
      (swap! (:sessions store) dissoc session-id)
      ;; Remove from routing-key index
      (swap! (:by-routing-key store) dissoc routing-key)
      ;; Remove from channel index
      (swap! (:by-channel store)
             update-in [channel]
             (fn [accounts]
               (->> accounts
                    (map (fn [[aid session-ids]]
                           [aid (disj session-ids session-id)]))
                    (filter (fn [[_ ids]] (not-empty ids)))
                    (into {}))))
      session)))

(defn close-all-sessions
  "Close all sessions. Returns count of closed sessions."
  [store]
  (let [count (count @(:sessions store))]
    (reset! (:sessions store) {})
    (reset! (:by-routing-key store) {})
    (reset! (:by-channel store) {})
    count))

;; ════════════════════════════════════════════════════════════════════════════
;; Session Stats
;; ════════════════════════════════════════════════════════════════════════════

(defn session-count
  "Get total session count."
  [store]
  (count @(:sessions store)))

(defn session-count-by-channel
  "Get session count for a specific channel."
  ([store channel]
   (->> (get-in @(:by-channel store) [channel])
        vals
        (map count)
        (reduce + 0)))
  ([store channel account-id]
   (count (get-in @(:by-channel store) [channel account-id] #{}))))

(defn get-stats
  "Get session statistics."
  [store]
  (let [sessions (vals @(:sessions store))
        now (.getTime (Date.))
        one-hour-ago (- now 3600000)]
    {:total (count sessions)
     :by-channel (->> sessions
                      (group-by :channel)
                      (map (fn [[ch ss]] [ch (count ss)]))
                      (into {}))
     :active-last-hour (count (filter #(> (:last-active %) one-hour-ago) sessions))}))

;; ════════════════════════════════════════════════════════════════════════════
;; Session Lookup Helpers
;; ════════════════════════════════════════════════════════════════════════════

(defn find-or-create-session
  "Find an existing session by ID or routing-key, or create a new one.

   Lookup order:
   1. By session-id (if provided)
   2. By routing-key (if provided)
   3. Create new session

   Returns the Session."
  [store channel-name {:keys [session-id routing-key account-id]}]
  (or
   ;; 1. Try by session-id
   (when session-id
     (get-session store session-id))

   ;; 2. Try by routing-key
   (when routing-key
     (get-session-by-routing-key store routing-key))

   ;; 3. Extract session-id from routing-key pattern
   (when routing-key
     (when-let [sid (ch/extract-session-id routing-key)]
       (get-session store sid)))

   ;; 4. Create new session
   (create-session store channel-name
                   {:routing-key routing-key
                    :account-id account-id})))

