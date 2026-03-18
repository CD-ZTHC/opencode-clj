(ns anima-agent-clj.pipeline.sink
  "Data sinks for the Pipeline system.

   Provides various sink implementations:
   - Channel Sink: Write to core.async channel
   - File Sink: Write to file
   - Collection Sink: Collect into in-memory collection
   - Batch Sink: Buffer before writing
   - Multi Sink: Write to multiple sinks
   - Callback Sink: Call function on each item

   Usage:
   ;; Channel sink
   (def ch-sink (channel-sink output-ch))

   ;; File sink
   (def file-sink (file-sink \"output.txt\" {:append? true}))

   ;; Batch sink
   (def batch-sink (batch-sink (file-sink \"data.txt\") {:batch-size 100}))"
  (:require [clojure.core.async :as async]
            [anima-agent-clj.pipeline.core :as core])
  (:import [java.util UUID Date]
           [java.io BufferedWriter FileWriter]
           [java.nio.file Files Paths]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Sink
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ChannelSink [id output-ch status config stats]
  core/ISink
  (write [this data context]
    (when (= :open @(:status this))
      (async/put! (:output-ch this) data)
      (swap! (:stats this) update :items-written inc))
    data)
  (flush-sink [this] this)
  (close-sink [this]
    (when (= :open @(:status this))
      (reset! (:status this) :closed)
      (async/close! (:output-ch this)))
    this))

(defn channel-sink
  "Create a sink that writes to a core.async channel."
  ([]
   (channel-sink {}))
  ([opts-or-ch]
   (if (instance? clojure.core.async.impl.protocols.Channel opts-or-ch)
     (->ChannelSink (str (UUID/randomUUID)) opts-or-ch (atom :open) {} (atom {:items-written 0}))
     (let [{:keys [buffer-size] :or {buffer-size 1000}} opts-or-ch]
       (->ChannelSink
        (str (UUID/randomUUID))
        (async/chan buffer-size)
        (atom :open)
        {:buffer-size buffer-size}
        (atom {:items-written 0}))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; File Sink
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord FileSink [id file-path writer status config stats]
  core/ISink
  (write [this data context]
    (when (= :open @(:status this))
      (try
        (let [w @(:writer this)
              line (str data)]
          (.write w line)
          (.newLine w)
          (when (:flush-every? (:config this))
            (.flush w))
          (swap! (:stats this) update :lines-written inc)
          data)
        (catch Exception e
          (swap! (:stats this) update :errors inc)
          (throw e)))))
  (flush-sink [this]
    (when-let [w @(:writer this)]
      (.flush w))
    this)
  (close-sink [this]
    (when (= :open @(:status this))
      (reset! (:status this) :closed)
      (when-let [w @(:writer this)]
        (.flush w)
        (.close w)))
    this)
  java.lang.AutoCloseable
  (close [this]
    (core/close-sink this)))

(defn file-sink
  "Create a sink that writes to a file."
  ([file-path]
   (file-sink file-path {}))
  ([file-path {:keys [append? flush-every? create-dirs?]
               :or {append? false flush-every? false create-dirs? true}}]
   (try
     (let [path (Paths/get file-path (into-array String []))]
       (when create-dirs?
         (let [parent (.getParent path)]
           (when parent
             (Files/createDirectories parent (into-array java.nio.file.attribute.FileAttribute [])))))
       (let [writer (BufferedWriter. (FileWriter. file-path append?))]
         (->FileSink
          (str (UUID/randomUUID))
          file-path
          (atom writer)
          (atom :open)
          {:append? append? :flush-every? flush-every? :create-dirs? create-dirs?}
          (atom {:lines-written 0 :errors 0 :start-time (Date.)}))))
     (catch Exception e
       (throw (ex-info (str "Failed to create file sink: " (.getMessage e))
                       {:file-path file-path} e))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Collection Sink
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord CollectionSink [id collection status config stats]
  core/ISink
  (write [this data context]
    (when (= :open @(:status this))
      (swap! (:collection this) conj data)
      (swap! (:stats this) update :items-collected inc)
      data))
  (flush-sink [this] this)
  (close-sink [this]
    (reset! (:status this) :closed)
    this))

(defn collection-sink
  "Create a sink that collects items into an in-memory vector."
  ([]
   (collection-sink {}))
  ([{:keys [initial-capacity] :or {initial-capacity 1000}}]
   (->CollectionSink
    (str (UUID/randomUUID))
    (atom [])
    (atom :open)
    {:initial-capacity initial-capacity}
    (atom {:items-collected 0}))))

(defn get-collection
  "Get all items collected by a CollectionSink."
  [sink]
  @(:collection sink))

(defn clear-collection!
  "Clear all collected items from a CollectionSink."
  [sink]
  (reset! (:collection sink) [])
  (swap! (:stats sink) assoc :items-collected 0)
  sink)

;; ══════════════════════════════════════════════════════════════════════════════
;; Batch Sink
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord BatchSink [id underlying-sink batch-size current-batch status config stats]
  core/ISink
  (write [this data context]
    (when (= :open @(:status this))
      (swap! (:current-batch this) conj data)
      (when (>= (count @(:current-batch this)) (:batch-size this))
        (core/write (:underlying-sink this) @(:current-batch this) context)
        (swap! (:stats this) update :batches-written inc)
        (swap! (:stats this) update :items-written + (count @(:current-batch this)))
        (reset! (:current-batch this) []))
      data))
  (flush-sink [this]
    (when (seq @(:current-batch this))
      (core/write (:underlying-sink this) @(:current-batch this) nil)
      (swap! (:stats this) update :batches-written inc)
      (swap! (:stats this) update :items-written + (count @(:current-batch this)))
      (reset! (:current-batch this) []))
    (core/flush-sink (:underlying-sink this))
    this)
  (close-sink [this]
    (when (= :open @(:status this))
      (core/flush-sink this)
      (reset! (:status this) :closed)
      (core/close-sink (:underlying-sink this)))
    this)
  java.lang.AutoCloseable
  (close [this]
    (core/close-sink this)))

(defn batch-sink
  "Create a sink that batches writes to an underlying sink."
  ([underlying-sink]
   (batch-sink underlying-sink {}))
  ([underlying-sink {:keys [batch-size] :or {batch-size 100}}]
   (->BatchSink
    (str (UUID/randomUUID))
    underlying-sink
    batch-size
    (atom [])
    (atom :open)
    {:batch-size batch-size}
    (atom {:batches-written 0 :items-written 0}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Multi Sink
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord MultiSink [id sinks status config stats]
  core/ISink
  (write [this data context]
    (when (= :open @(:status this))
      (doseq [sink (:sinks this)]
        (try
          (core/write sink data context)
          (catch Exception e
            (swap! (:stats this) update :errors inc)
            (when-not (:ignore-errors? (:config this))
              (throw e)))))
      (swap! (:stats this) update :items-written inc)
      data))
  (flush-sink [this]
    (doseq [sink (:sinks this)]
      (core/flush-sink sink))
    this)
  (close-sink [this]
    (when (= :open @(:status this))
      (reset! (:status this) :closed)
      (doseq [sink (:sinks this)]
        (try
          (core/close-sink sink)
          (catch Exception e
            (swap! (:stats this) update :close-errors inc)))))
    this))

(defn multi-sink
  "Create a sink that writes to multiple sinks."
  ([sinks]
   (multi-sink sinks {}))
  ([sinks {:keys [ignore-errors?] :or {ignore-errors? false}}]
   (->MultiSink
    (str (UUID/randomUUID))
    (vec sinks)
    (atom :open)
    {:ignore-errors? ignore-errors?}
    (atom {:items-written 0 :errors 0 :close-errors 0}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Callback Sink
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord CallbackSink [id callback status config stats]
  core/ISink
  (write [this data context]
    (when (= :open @(:status this))
      (try
        ((:callback this) data)
        (swap! (:stats this) update :items-processed inc)
        data
        (catch Exception e
          (swap! (:stats this) update :errors inc)
          (when-not (:ignore-errors? (:config this))
            (throw e))))))
  (flush-sink [this] this)
  (close-sink [this]
    (reset! (:status this) :closed)
    this))

(defn callback-sink
  "Create a sink that calls a function for each item."
  ([callback]
   (callback-sink callback {}))
  ([callback {:keys [ignore-errors?] :or {ignore-errors? false}}]
   (->CallbackSink
    (str (UUID/randomUUID))
    callback
    (atom :open)
    {:ignore-errors? ignore-errors?}
    (atom {:items-processed 0 :errors 0}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Null Sink
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord NullSink [id stats]
  core/ISink
  (write [this data context]
    (swap! (:stats this) update :items-discarded inc)
    data)
  (flush-sink [this] this)
  (close-sink [this] this))

(defn null-sink
  "Create a sink that discards all data."
  []
  (->NullSink (str (UUID/randomUUID)) (atom {:items-discarded 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Utility Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn sink-stats
  "Get statistics from a sink."
  [sink]
  @(:stats sink))

(defn sink-status
  "Get current status of a sink."
  [sink]
  @(:status sink))
