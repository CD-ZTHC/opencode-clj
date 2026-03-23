(ns anima-agent-clj.pipeline.core
  "Data Pipeline for the Agent Cluster Architecture.

   The Pipeline provides a DMA (Direct Memory Access) controller-like
   abstraction for efficient data flow and transformation.

   Architecture:
   ┌─────────────────────────────────────────────────────────────┐
   │                        Pipeline                              │
   │  Source → Transform → Filter → Aggregate → Sink            │
   │     ↓         ↓         ↓         ↓        ↓               │
   │  Channel  Processor  Router   Combiner  Channel            │
   └─────────────────────────────────────────────────────────────┘

   Pipeline Stages:
   1. Source - Data ingestion (channels, files, streams)
   2. Transform - Data transformation functions
   3. Filter - Conditional filtering
   4. Aggregate - Combine multiple inputs
   5. Sink - Output destination

   Usage:
   (def pipeline
     (-> (create-pipeline)
         (add-source (file-source \"input.txt\"))
         (add-transform (map-transform inc))
         (add-sink (file-sink \"output.txt\"))))

   (start-pipeline! pipeline)
   (wait-for-completion pipeline)
   (stop-pipeline! pipeline)"
  (:require [clojure.core.async :as async]
            [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]
           [java.util.concurrent Executors ExecutorService]))

;; Forward declarations
(declare process-data-through-stages)

;; ══════════════════════════════════════════════════════════════════════════════
;; Pipeline Protocols
;; ══════════════════════════════════════════════════════════════════════════════

(defprotocol ISource
  "Protocol for data sources."
  (start-source [this context]
    "Start the source. Returns a channel that emits data.")
  (stop-source [this]
    "Stop the source.")
  (source-status [this]
    "Get source status."))

(defprotocol ITransform
  "Protocol for data transformers."
  (transform [this data context]
    "Transform data. Returns transformed data or nil to filter out."))

(defprotocol IFilter
  "Protocol for data filters."
  (passes? [this data context]
    "Return true if data passes the filter."))

(defprotocol IAggregate
  "Protocol for data aggregators."
  (add-data [this data context]
    "Add data to aggregation. Returns current aggregation state.")
  (get-result [this]
    "Get aggregation result.")
  (reset-aggregate [this]
    "Reset aggregation state."))

(defprotocol ISink
  "Protocol for data sinks."
  (write [this data context]
    "Write data to sink.")
  (flush-sink [this]
    "Flush any buffered data.")
  (close-sink [this]
    "Close the sink."))

(defprotocol IPipeline
  "Protocol for pipelines."
  (connect [this stage]
    "Add a stage to the pipeline.")
  (process [this data]
    "Process a single data item through the pipeline.")
  (process-batch [this data-coll]
    "Process a batch of data items.")
  (start-pipeline [this]
    "Start the pipeline processing.")
  (stop-pipeline [this]
    "Stop the pipeline processing.")
  (pipeline-status [this]
    "Get pipeline status."))

;; ══════════════════════════════════════════════════════════════════════════════
;; Context Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord PipelineContext
           [pipeline-id ; String - Pipeline identifier
            execution-id ; String - Current execution ID
            stage ; Keyword - Current stage
            metadata ; Map - Additional context data
            start-time ; Date - Execution start time
            stats]) ; atom<map> - Execution statistics

(defn make-context
  "Create a pipeline context."
  [pipeline-id]
  (->PipelineContext
   pipeline-id
   (str (UUID/randomUUID))
   nil
   {}
   (Date.)
   (atom {:items-processed 0
          :items-filtered 0
          :errors 0
          :start-time (System/currentTimeMillis)})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Stage Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord Stage
           [id ; String - Stage identifier
            type ; Keyword - :source, :transform, :filter, :aggregate, :sink
            processor ; The actual processor (ISource, ITransform, etc.)
            config ; Map - Stage configuration
            next-stage ; atom - Reference to next stage
            status ; atom<keyword> - :idle, :running, :error, :stopped
            stats]) ; atom<map> - Stage statistics

(defn make-stage
  "Create a pipeline stage."
  [type processor config]
  (->Stage
   (str (UUID/randomUUID))
   type
   processor
   config
   (atom nil)
   (atom :idle)
   (atom {:items-in 0
          :items-out 0
          :errors 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Pipeline Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord Pipeline
           [id ; String - Unique identifier
            source ; Source stage
            transforms ; atom<vector> - Transform stages
            filters ; atom<vector> - Filter stages
            aggregate ; atom - Aggregate stage (optional)
            sink ; Sink stage
            config ; Map - Pipeline configuration
            status ; atom<keyword> - :idle, :running, :error, :stopped
            context ; atom - Current execution context
            input-ch ; Channel - Input channel
            output-ch ; Channel - Output channel
            error-ch ; Channel - Error channel
            executor ; ExecutorService - Thread pool
            metrics ; MetricsCollector - Optional metrics collector
            processing-loop ; atom - Main processing loop
            stats] ; atom<map> - Pipeline statistics

  IPipeline
  (connect [this stage]
    (case (:type stage)
      :source
      (do
        (when-let [s @(:source this)]
          (throw (ex-info "Source already set" {})))
        (reset! (:source this) stage)
        this)

      (:transform :map :mapcat :reduce)
      (do
        (swap! (:transforms this) conj stage)
        this)

      (:filter :predicate)
      (do
        (swap! (:filters this) conj stage)
        this)

      :aggregate
      (do
        (reset! (:aggregate this) stage)
        this)

      :sink
      (do
        (reset! (:sink this) stage)
        this)

      (throw (ex-info (str "Unknown stage type: " (:type stage)) {}))))

  (process [this data]
    (let [ctx (make-context (:id this))
          result-ch (async/promise-chan)]
      (async/go
        (try
          (let [result (process-data-through-stages this data ctx)]
            (async/>! result-ch {:status :success :result result}))
          (catch Exception e
            (async/>! result-ch {:status :error :error (.getMessage e)}))))
      result-ch))

  (process-batch [this data-coll]
    (let [ctx (make-context (:id this))
          results-ch (async/chan (count data-coll))]
      (async/go
        (doseq [data data-coll]
          (try
            (let [result (process-data-through-stages this data ctx)]
              (async/>! results-ch {:status :success :result result}))
            (catch Exception e
              (async/>! results-ch {:status :error :error (.getMessage e)}))))
        (async/close! results-ch))
      results-ch))

  (start-pipeline [this]
    (when (= :idle @(:status this))
      (reset! (:status this) :running)
      (reset! (:context this) (make-context (:id this)))
      (when-let [s @(:source this)]
        (start-source (:processor s) @(:context this)))
      (when-let [s @(:sink this)]
        (close-sink (:processor s))) ; Reset sink
      this))

  (stop-pipeline [this]
    (when (= :running @(:status this))
      (reset! (:status this) :stopping)
      (when-let [s @(:source this)]
        (stop-source (:processor s)))
      (when-let [s @(:sink this)]
        (flush-sink (:processor s))
        (close-sink (:processor s)))
      (when-let [pl @(:processing-loop this)]
        (async/close! pl))
      (when-let [^ExecutorService exec (:executor this)]
        (.shutdown exec))
      (reset! (:status this) :stopped))
    this)

  (pipeline-status [this]
    {:id (:id this)
     :status @(:status this)
     :source (when-let [s @(:source this)] {:id (:id s) :status @(:status s)})
     :transform-count (count @(:transforms this))
     :filter-count (count @(:filters this))
     :has-aggregate (some? @(:aggregate this))
     :sink (when-let [s @(:sink this)] {:id (:id s) :status @(:status s)})
     :stats @(:stats this)}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Data Processing
;; ══════════════════════════════════════════════════════════════════════════════

(declare process-through-transforms process-through-filters)

(defn- process-data-through-stages
  "Process data through all pipeline stages."
  [pipeline data context]
  (let [mc (:metrics pipeline)]
    ;; Track processing
    (swap! (:stats pipeline) update :items-processed inc)
    (when mc (metrics/counter-inc! mc "pipeline_items_processed"))

    (let [;; Apply filters first
          filtered-data (process-through-filters pipeline data context)

          ;; If filtered out, return nil
          _ (when (nil? filtered-data)
              (swap! (:stats pipeline) update :items-filtered inc)
              (when mc (metrics/counter-inc! mc "pipeline_items_filtered")))

          ;; Apply transforms
          transformed-data (when filtered-data
                             (process-through-transforms pipeline filtered-data context))

          ;; Aggregate if present
          aggregated-data (when (and transformed-data @(:aggregate pipeline))
                            (add-data (:processor @(:aggregate pipeline))
                                      transformed-data
                                      context)
                            nil) ; Aggregation doesn't return per-item

          ;; Write to sink
          _ (when (and transformed-data (not @(:aggregate pipeline)) @(:sink pipeline))
              (write (:processor @(:sink pipeline)) transformed-data context))]

      (or aggregated-data transformed-data))))

(defn- process-through-filters
  "Pass data through all filter stages."
  [pipeline data context]
  (reduce
   (fn [data filter-stage]
     (if (nil? data)
       nil ; Already filtered out
       (if (passes? (:processor filter-stage) data context)
         data
         nil)))
   data
   @(:filters pipeline)))

(defn- process-through-transforms
  "Pass data through all transform stages."
  [pipeline data context]
  (reduce
   (fn [data transform-stage]
     (if (nil? data)
       nil
       (transform (:processor transform-stage) data context)))
   data
   @(:transforms pipeline)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Helper Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn add-source
  "Add a source stage to the pipeline."
  [pipeline source]
  (connect pipeline source))

(defn add-transform
  "Add a transform stage to the pipeline."
  [pipeline transform]
  (connect pipeline transform))

(defn add-filter
  "Add a filter stage to the pipeline."
  [pipeline filter]
  (connect pipeline filter))

(defn add-aggregate
  "Add an aggregate stage to the pipeline."
  [pipeline aggregate]
  (connect pipeline aggregate))

(defn add-sink
  "Add a sink stage to the pipeline."
  [pipeline sink]
  (connect pipeline sink))

(defn get-aggregation-result
  "Get the result from aggregation stage."
  [pipeline]
  (when-let [agg @(:aggregate pipeline)]
    (get-result (:processor agg))))

(defn reset-aggregation
  "Reset the aggregation state."
  [pipeline]
  (when-let [agg @(:aggregate pipeline)]
    (reset-aggregate (:processor agg))))

(defn wait-for-completion
  "Wait for pipeline to process all data from source."
  [pipeline timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (= :stopped @(:status pipeline)) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 100) (recur))))))

(defn pipeline-metrics
  "Get pipeline metrics."
  [pipeline]
  (merge
   @(:stats pipeline)
   {:status @(:status pipeline)
    :duration-ms (let [stats @(:stats pipeline)
                       start (:start-time stats)]
                   (when start
                     (- (System/currentTimeMillis) start)))}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(def default-config
  {:buffer-size 1000
   :parallelism 4
   :error-handler nil
   :batch-size 100})

(defn create-pipeline
  "Create a new Pipeline.

   Options:
     :id - Unique identifier (auto-generated if not provided)
     :buffer-size - Channel buffer size (default: 1000)
     :parallelism - Number of parallel workers (default: 4)
     :batch-size - Batch size for batch processing (default: 100)
     :metrics - MetricsCollector instance

   Example:
   (create-pipeline {:buffer-size 5000 :parallelism 8})"
  [{:keys [id buffer-size parallelism batch-size metrics]
    :or {buffer-size 1000
         parallelism 4
         batch-size 100}
    :as opts}]
  (->Pipeline
   (or id (str (UUID/randomUUID)))
   (atom nil)
   (atom [])
   (atom [])
   (atom nil)
   (atom nil)
   (merge default-config
          {:buffer-size buffer-size
           :parallelism parallelism
           :batch-size batch-size}
          (select-keys opts [:buffer-size :parallelism :batch-size]))
   (atom :idle)
   (atom nil)
   (async/chan buffer-size)
   (async/chan buffer-size)
   (async/chan buffer-size)
   (Executors/newFixedThreadPool parallelism)
   metrics
   (atom nil)
   (atom {:items-processed 0
          :items-filtered 0
          :errors 0
          :start-time nil})))

(defn create-simple-pipeline
  "Create a simple pipeline with just source, transform, and sink.

   Example:
   (create-simple-pipeline
     {:source (channel-source input-ch)
      :transform (map-transform inc)
      :sink (channel-sink output-ch)})"
  [{:keys [source transform sink]}]
  (-> (create-pipeline {})
      (add-source source)
      (add-transform transform)
      (add-sink sink)))
