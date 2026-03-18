(ns anima-agent-clj.pipeline.source
  "Data sources for the Pipeline system.

   Provides various source implementations:
   - Channel Source: Read from core.async channel
   - File Source: Read from file line by line
   - Collection Source: Read from in-memory collection
   - Iterator Source: Read from any iterator
   - Polling Source: Periodically poll a function

   Usage:
   ;; Channel source
   (def ch-source (channel-source input-ch))

   ;; File source
   (def file-src (file-source \"data.txt\" {:batch-size 100}))

   ;; Collection source
   (def coll-src (collection-source [1 2 3 4 5]))

   ;; Polling source
   (def poll-src (polling-source #(fetch-new-data) {:interval-ms 1000}))"
  (:require [clojure.core.async :as async]
            [anima-agent-clj.pipeline.core :as core])
  (:import [java.util UUID Date]
           [java.io BufferedReader FileReader]
           [java.nio.file Files Paths]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Source
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ChannelSource
  [id              ; String - Source identifier
   input-ch        ; Channel - Input channel to read from
   output-ch       ; atom<channel> - Output channel
   status          ; atom<keyword> - :idle, :running, :stopped
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   read-loop]      ; atom - Read loop go-block

  core/ISource
  (start-source [this context]
    (let [out-ch (async/chan (get-in this [:config :buffer-size] 1000))]
      (reset! (:output-ch this) out-ch)
      (reset! (:status this) :running)
      (reset! (:read-loop this)
              (async/go-loop []
                (if (= :running @(:status this))
                  (let [data (async/<! (:input-ch this))]
                    (if (some? data)
                      (do
                        (swap! (:stats this) update :items-read inc)
                        (async/>! out-ch data)
                        (recur))
                      (do
                        (async/close! out-ch)
                        (reset! (:status this) :stopped))))
                  (async/close! out-ch)))
              out-ch)))

  (stop-source [this]
    (reset! (:status this) :stopped)
    (when-let [loop @(:read-loop this)]
      (async/close! loop))
    this)

  (source-status [this]
    {:id (:id this)
     :status @(:status this)
     :stats @(:stats this)}))

(defn channel-source
  "Create a source that reads from a core.async channel.

   Options:
     :buffer-size - Output buffer size (default: 1000)

   Example:
   (channel-source input-ch {:buffer-size 5000})"
  ([input-ch]
   (channel-source input-ch {}))
  ([input-ch {:keys [buffer-size]
              :or {buffer-size 1000}}]
   (->ChannelSource
    (str (UUID/randomUUID))
    input-ch
    (atom nil)
    (atom :idle)
    {:buffer-size buffer-size}
    (atom {:items-read 0
           :start-time nil})
    (atom nil))))

;; ══════════════════════════════════════════════════════════════════════════════
;; File Source
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord FileSource
  [id              ; String - Source identifier
   file-path       ; String - Path to file
   output-ch       ; atom<channel> - Output channel
   status          ; atom<keyword> - :idle, :running, :stopped
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   read-loop       ; atom - Read loop
   reader]         ; atom<BufferedReader> - File reader

  core/ISource
  (start-source [this context]
    (let [out-ch (async/chan (get-in this [:config :buffer-size] 1000))
          batch-size (get-in this [:config :batch-size] 100)]
      (reset! (:output-ch this) out-ch)
      (reset! (:status this) :running)
      (try
        (let [rdr (BufferedReader. (FileReader. ^String (:file-path this)))]
          (reset! (:reader this) rdr)
          (reset! (:read-loop this)
                  (async/go-loop []
                    (if (= :running @(:status this))
                      (let [line (.readLine rdr)]
                        (if (some? line)
                          (do
                            (swap! (:stats this) update :lines-read inc)
                            (async/>! out-ch line)
                            (recur))
                          (do
                            (.close rdr)
                            (async/close! out-ch)
                            (reset! (:status this) :completed)
                            (swap! (:stats this) assoc :end-time (Date.)))))
                      (do
                        (.close rdr)
                        (async/close! out-ch)))))
          out-ch)
        (catch Exception e
          (reset! (:status this) :error)
          (swap! (:stats this) assoc :error (.getMessage e))
          (async/close! out-ch)
          nil))))

  (stop-source [this]
    (reset! (:status this) :stopped)
    (when-let [rdr @(:reader this)]
      (.close rdr))
    (when-let [loop @(:read-loop this)]
      (async/close! loop))
    this)

  (source-status [this]
    {:id (:id this)
     :file-path (:file-path this)
     :status @(:status this)
     :stats @(:stats this)}))

(defn file-source
  "Create a source that reads from a file line by line.

   Options:
     :buffer-size - Output buffer size (default: 1000)
     :batch-size - Lines to read per batch (default: 100)

   Example:
   (file-source \"data/input.txt\" {:buffer-size 5000})"
  ([file-path]
   (file-source file-path {}))
  ([file-path {:keys [buffer-size batch-size]
               :or {buffer-size 1000
                    batch-size 100}}]
   (->FileSource
    (str (UUID/randomUUID))
    file-path
    (atom nil)
    (atom :idle)
    {:buffer-size buffer-size
     :batch-size batch-size}
    (atom {:lines-read 0
           :start-time (Date.)
           :end-time nil
           :error nil})
    (atom nil)
    (atom nil))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Collection Source
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord CollectionSource
  [id              ; String - Source identifier
   collection      ; Collection - Data to emit
   output-ch       ; atom<channel> - Output channel
   status          ; atom<keyword> - :idle, :running, :stopped
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   read-loop]      ; atom - Read loop

  core/ISource
  (start-source [this context]
    (let [out-ch (async/chan (get-in this [:config :buffer-size] 1000))]
      (reset! (:output-ch this) out-ch)
      (reset! (:status this) :running)
      (reset! (:read-loop this)
              (async/go-loop [items (seq (:collection this))]
                (if (and (seq items) (= :running @(:status this)))
                  (do
                    (swap! (:stats this) update :items-emitted inc)
                    (async/>! out-ch (first items))
                    (recur (next items)))
                  (do
                    (async/close! out-ch)
                    (reset! (:status this) :completed)))))
      out-ch))

  (stop-source [this]
    (reset! (:status this) :stopped)
    (when-let [loop @(:read-loop this)]
      (async/close! loop))
    this)

  (source-status [this]
    {:id (:id this)
     :count (count (:collection this))
     :status @(:status this)
     :stats @(:stats this)}))

(defn collection-source
  "Create a source that emits items from a collection.

   Options:
     :buffer-size - Output buffer size (default: 1000)

   Example:
   (collection-source [1 2 3 4 5])"
  ([coll]
   (collection-source coll {}))
  ([coll {:keys [buffer-size]
          :or {buffer-size 1000}}]
   (->CollectionSource
    (str (UUID/randomUUID))
    coll
    (atom nil)
    (atom :idle)
    {:buffer-size buffer-size}
    (atom {:items-emitted 0
           :start-time (Date.)})
    (atom nil))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Polling Source
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord PollingSource
  [id              ; String - Source identifier
   poll-fn         ; Function - Called to get new data
   output-ch       ; atom<channel> - Output channel
   status          ; atom<keyword> - :idle, :running, :stopped
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   poll-loop]      ; atom - Polling loop

  core/ISource
  (start-source [this context]
    (let [out-ch (async/chan (get-in this [:config :buffer-size] 1000))
          interval-ms (get-in this [:config :interval-ms] 1000)]
      (reset! (:output-ch this) out-ch)
      (reset! (:status this) :running)
      (reset! (:poll-loop this)
              (async/go-loop []
                (if (= :running @(:status this))
                  (do
                    (async/<! (async/timeout interval-ms))
                    (try
                      (let [data ((:poll-fn this))]
                        (when (some? data)
                          (if (sequential? data)
                            ;; Emit each item
                            (doseq [item data]
                              (async/>! out-ch item))
                            ;; Emit single item
                            (async/>! out-ch data))
                          (swap! (:stats this) update :polls-done inc)))
                      (catch Exception e
                        (swap! (:stats this) update :errors inc)))
                    (recur))
                  (async/close! out-ch))))
      out-ch))

  (stop-source [this]
    (reset! (:status this) :stopped)
    (when-let [loop @(:poll-loop this)]
      (async/close! loop))
    this)

  (source-status [this]
    {:id (:id this)
     :status @(:status this)
     :interval-ms (get-in this [:config :interval-ms])
     :stats @(:stats this)}))

(defn polling-source
  "Create a source that periodically polls a function.

   The poll-fn should return:
   - nil to emit nothing
   - A single value to emit
   - A sequence to emit each item

   Options:
     :buffer-size - Output buffer size (default: 1000)
     :interval-ms - Polling interval in ms (default: 1000)

   Example:
   (polling-source #(fetch-new-messages)
                   {:interval-ms 5000})"
  ([poll-fn]
   (polling-source poll-fn {}))
  ([poll-fn {:keys [buffer-size interval-ms]
             :or {buffer-size 1000
                  interval-ms 1000}}]
   (->PollingSource
    (str (UUID/randomUUID))
    poll-fn
    (atom nil)
    (atom :idle)
    {:buffer-size buffer-size
     :interval-ms interval-ms}
    (atom {:polls-done 0
           :errors 0
           :start-time (Date.)})
    (atom nil))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Iterator Source
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord IteratorSource
  [id              ; String - Source identifier
   iterator        ; Iterator - Data iterator
   output-ch       ; atom<channel> - Output channel
   status          ; atom<keyword> - :idle, :running, :stopped
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   read-loop]      ; atom - Read loop

  core/ISource
  (start-source [this context]
    (let [out-ch (async/chan (get-in this [:config :buffer-size] 1000))]
      (reset! (:output-ch this) out-ch)
      (reset! (:status this) :running)
      (reset! (:read-loop this)
              (async/go-loop []
                (if (= :running @(:status this))
                  (if (.hasNext ^java.util.Iterator (:iterator this))
                    (do
                      (swap! (:stats this) update :items-emitted inc)
                      (async/>! out-ch (.next ^java.util.Iterator (:iterator this)))
                      (recur))
                    (do
                      (async/close! out-ch)
                      (reset! (:status this) :completed)))
                  (async/close! out-ch))))
      out-ch))

  (stop-source [this]
    (reset! (:status this) :stopped)
    (when-let [loop @(:read-loop this)]
      (async/close! loop))
    this)

  (source-status [this]
    {:id (:id this)
     :status @(:status this)
     :stats @(:stats this)}))

(defn iterator-source
  "Create a source from a Java Iterator.

   Options:
     :buffer-size - Output buffer size (default: 1000)

   Example:
   (iterator-source (.iterator (java.util.ArrayList. [1 2 3])))"
  ([iterator]
   (iterator-source iterator {}))
  ([iterator {:keys [buffer-size]
              :or {buffer-size 1000}}]
   (->IteratorSource
    (str (UUID/randomUUID))
    iterator
    (atom nil)
    (atom :idle)
    {:buffer-size buffer-size}
    (atom {:items-emitted 0
           :start-time (Date.)})
    (atom nil))))
