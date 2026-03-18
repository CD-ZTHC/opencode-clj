(ns anima-agent-clj.metrics
  "Metrics collection and monitoring for the Agent Cluster.

   Collects metrics for:
   - Message processing (throughput, latency)
   - Cache performance (hit rate, evictions)
   - Agent activity (tasks, errors)
   - System resources (memory, threads)

   Supports export to:
   - Prometheus format
   - JSON format
   - Internal monitoring

   Usage:
   (def collector (create-collector {:prefix \"anima\"}))
   (counter-inc! collector :messages-received)
   (histogram-record! collector :message-latency 150)
   (get-metrics collector)
   (export-prometheus collector)"
  (:require [clojure.string :as string])
  (:import [java.util Date]
           [java.util.concurrent.atomic AtomicLong]
           [java.lang.management ManagementFactory
            MemoryMXBean ThreadMXBean]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Metric Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord Counter
           [name            ; Metric name
            description     ; Description
            value           ; AtomicLong
            labels])        ; Label map

(defrecord Gauge
           [name            ; Metric name
            description     ; Description
            value-fn        ; Function to get current value
            labels])        ; Label map

(defrecord Histogram
           [name            ; Metric name
            description     ; Description
            buckets         ; Bucket boundaries
            counts          ; atom<vector> - counts per bucket
            sum             ; atom<number> - sum of all values
            count           ; atom<number> - total count
            labels])        ; Label map

(defrecord Summary
           [name            ; Metric name
            description     ; Description
            values          ; atom<vector> - recent values (for quantiles)
            max-size        ; Max values to keep
            labels])        ; Label map

;; ══════════════════════════════════════════════════════════════════════════════
;; Metrics Collector
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord MetricsCollector
           [prefix          ; Metric name prefix
            counters        ; atom<map> - name -> Counter
            gauges          ; atom<map> - name -> Gauge
            histograms      ; atom<map> - name -> Histogram
            summaries       ; atom<map> - name -> Summary
            start-time      ; Collection start time
            config])        ; Configuration

;; ══════════════════════════════════════════════════════════════════════════════
;; Counter Operations
;; ══════════════════════════════════════════════════════════════════════════════

(defn register-counter
  "Register a new counter metric."
  ([collector name]
   (register-counter collector name ""))
  ([collector name description]
   (register-counter collector name description {}))
  ([collector name description labels]
   (let [counter (->Counter
                  (str (:prefix collector) "_" name)
                  description
                  (AtomicLong. 0)
                  labels)]
     (swap! (:counters collector) assoc name counter)
     counter)))

(defn counter-inc!
  "Increment counter by 1."
  [collector name]
  (when-let [counter (get @(:counters collector) name)]
    (.incrementAndGet ^AtomicLong (:value counter))))

(defn counter-add!
  "Add value to counter."
  [collector name value]
  (when-let [counter (get @(:counters collector) name)]
    (.addAndGet ^AtomicLong (:value counter) value)))

(defn counter-get
  "Get counter value."
  [collector name]
  (when-let [counter (get @(:counters collector) name)]
    (.get ^AtomicLong (:value counter))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Gauge Operations
;; ══════════════════════════════════════════════════════════════════════════════

(defn register-gauge
  "Register a new gauge metric."
  ([collector name value-fn]
   (register-gauge collector name value-fn ""))
  ([collector name value-fn description]
   (register-gauge collector name value-fn description {}))
  ([collector name value-fn description labels]
   (let [gauge (->Gauge
                (str (:prefix collector) "_" name)
                description
                value-fn
                labels)]
     (swap! (:gauges collector) assoc name gauge)
     gauge)))

(defn gauge-get
  "Get gauge value."
  [collector name]
  (when-let [gauge (get @(:gauges collector) name)]
    ((:value-fn gauge))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Histogram Operations
;; ══════════════════════════════════════════════════════════════════════════════

(def default-buckets [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1 2.5 5 10])

(defn register-histogram
  "Register a new histogram metric."
  ([collector name]
   (register-histogram collector name default-buckets ""))
  ([collector name buckets]
   (register-histogram collector name buckets ""))
  ([collector name buckets description]
   (register-histogram collector name buckets description {}))
  ([collector name buckets description labels]
   (let [histogram (->Histogram
                    (str (:prefix collector) "_" name)
                    description
                    (vec (sort buckets))
                    (atom (vec (repeat (inc (count buckets)) 0)))
                    (atom 0)
                    (atom 0)
                    labels)]
     (swap! (:histograms collector) assoc name histogram)
     histogram)))

(defn histogram-record!
  "Record a value in the histogram."
  [collector name value]
  (when-let [histogram (get @(:histograms collector) name)]
    (swap! (:sum histogram) + value)
    (swap! (:count histogram) inc)
    ;; Find appropriate bucket
    (let [buckets (:buckets histogram)
          bucket-idx (loop [i 0]
                       (cond
                         (>= i (count buckets)) (count buckets)
                         (<= value (nth buckets i)) i
                         :else (recur (inc i))))]
      (swap! (:counts histogram)
             (fn [counts]
               (update counts bucket-idx inc))))))

(defn histogram-get
  "Get histogram statistics."
  [collector name]
  (when-let [histogram (get @(:histograms collector) name)]
    {:sum @(:sum histogram)
     :count @(:count histogram)
     :buckets (:buckets histogram)
     :counts @(:counts histogram)}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Summary Operations
;; ══════════════════════════════════════════════════════════════════════════════

(defn register-summary
  "Register a new summary metric."
  ([collector name]
   (register-summary collector name 1000 ""))
  ([collector name max-size]
   (register-summary collector name max-size ""))
  ([collector name max-size description]
   (register-summary collector name max-size description {}))
  ([collector name max-size description labels]
   (let [summary (->Summary
                  (str (:prefix collector) "_" name)
                  description
                  (atom [])
                  max-size
                  labels)]
     (swap! (:summaries collector) assoc name summary)
     summary)))

(defn summary-record!
  "Record a value in the summary."
  [collector name value]
  (when-let [summary (get @(:summaries collector) name)]
    (swap! (:values summary)
           (fn [values]
             (let [new-values (conj values value)]
               (if (> (count new-values) (:max-size summary))
                 (subvec new-values 1)
                 new-values))))))

(defn summary-get
  "Get summary statistics."
  [collector name]
  (when-let [summary (get @(:summaries collector) name)]
    (let [values @(:values summary)
          sorted (sort values)
          n (count sorted)]
      (when (pos? n)
        {:count n
         :sum (reduce + values)
         :min (first sorted)
         :max (last sorted)
         :mean (double (/ (reduce + values) n))
         :p50 (nth sorted (int (* 0.5 n)) nil)
         :p90 (nth sorted (int (* 0.9 n)) nil)
         :p95 (nth sorted (int (* 0.95 n)) nil)
         :p99 (nth sorted (int (* 0.99 n)) nil)}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; System Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn- get-memory-metrics []
  (let [runtime (Runtime/getRuntime)
        max-memory (.maxMemory runtime)
        total-memory (.totalMemory runtime)
        free-memory (.freeMemory runtime)
        used-memory (- total-memory free-memory)]
    {:max max-memory
     :total total-memory
     :free free-memory
     :used used-memory}))

(defn- get-thread-metrics []
  (let [thread-mx (ManagementFactory/getThreadMXBean)]
    {:count (.getThreadCount thread-mx)
     :daemon-count (.getDaemonThreadCount thread-mx)
     :peak-count (.getPeakThreadCount thread-mx)}))

(defn register-system-metrics
  "Register system-level metrics."
  [collector]
  ;; Memory gauges
  (register-gauge collector "memory_max" #(:max (get-memory-metrics))
                  "Maximum memory in bytes")
  (register-gauge collector "memory_total" #(:total (get-memory-metrics))
                  "Total memory in bytes")
  (register-gauge collector "memory_used" #(:used (get-memory-metrics))
                  "Used memory in bytes")
  (register-gauge collector "memory_free" #(:free (get-memory-metrics))
                  "Free memory in bytes")

  ;; Thread gauges
  (register-gauge collector "threads_count" #(:count (get-thread-metrics))
                  "Current thread count")
  (register-gauge collector "threads_daemon" #(:daemon-count (get-thread-metrics))
                  "Daemon thread count")
  (register-gauge collector "threads_peak" #(:peak-count (get-thread-metrics))
                  "Peak thread count")

  ;; Uptime gauge
  (register-gauge collector "uptime_ms"
                  #(- (System/currentTimeMillis) (.getTime ^Date (:start-time collector)))
                  "Uptime in milliseconds"))

;; ══════════════════════════════════════════════════════════════════════════════
;; Get All Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn get-metrics
  "Get all metrics as a map."
  [collector]
  {:counters (into {}
                   (for [[name counter] @(:counters collector)]
                     [name (.get ^AtomicLong (:value counter))]))
   :gauges (into {}
                 (for [[name gauge] @(:gauges collector)]
                   [name ((:value-fn gauge))]))
   :histograms (into {}
                     (for [[name _] @(:histograms collector)]
                       [name (histogram-get collector name)]))
   :summaries (into {}
                    (for [[name _] @(:summaries collector)]
                      [name (summary-get collector name)]))})

;; ══════════════════════════════════════════════════════════════════════════════
;; Prometheus Export
;; ══════════════════════════════════════════════════════════════════════════════

(defn- format-labels [labels]
  (if (empty? labels)
    ""
    (str "{"
         (string/join ","
                      (for [[k v] labels]
                        (str (name k) "=\"" v "\"")))
         "}")))

(defn- format-counter-prometheus [counter]
  (str "# HELP " (:name counter) " " (:description counter) "\n"
       "# TYPE " (:name counter) " counter\n"
       (:name counter) (format-labels (:labels counter)) " "
       (.get ^AtomicLong (:value counter)) "\n"))

(defn- format-gauge-prometheus [gauge]
  (str "# HELP " (:name gauge) " " (:description gauge) "\n"
       "# TYPE " (:name gauge) " gauge\n"
       (:name gauge) (format-labels (:labels gauge)) " "
       ((:value-fn gauge)) "\n"))

(defn- format-histogram-prometheus [histogram]
  (let [counts @(:counts histogram)
        buckets (:buckets histogram)
        sum @(:sum histogram)
        count @(:count histogram)
        name (:name histogram)
        labels-str (format-labels (:labels histogram))]
    (str
     "# HELP " name " " (:description histogram) "\n"
     "# TYPE " name " histogram\n"
     (string/join
      "\n"
      (concat
       (for [[i bucket] (map-indexed vector buckets)]
         (str name "_bucket" labels-str "{le=\"" bucket "\"} " (nth counts i)))
       [(str name "_bucket" labels-str "{le=\"+Inf\"} " (last counts))
        (str name "_sum" labels-str " " sum)
        (str name "_count" labels-str " " count)]))
     "\n")))

(defn export-prometheus
  "Export metrics in Prometheus format."
  [collector]
  (string/join
   "\n"
   (concat
    ;; Counters
    (map format-counter-prometheus (vals @(:counters collector)))
    ;; Gauges
    (map format-gauge-prometheus (vals @(:gauges collector)))
    ;; Histograms
    (map format-histogram-prometheus (vals @(:histograms collector))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-collector
  "Create a new metrics collector.

   Options:
     :prefix             - Metric name prefix (default: \"anima\")
     :register-system    - Register system metrics (default: true)"
  [{:keys [prefix register-system]
    :or {prefix "anima"
         register-system true}}]
  (let [collector (->MetricsCollector
                   prefix
                   (atom {})
                   (atom {})
                   (atom {})
                   (atom {})
                   (Date.)
                   {})]
    (when register-system
      (register-system-metrics collector))
    collector))

;; ══════════════════════════════════════════════════════════════════════════════
;; Predefined Metrics for Agent Cluster
;; ══════════════════════════════════════════════════════════════════════════════

(defn register-agent-metrics
  "Register standard metrics for the agent cluster."
  [collector]
  ;; Message metrics
  (register-counter collector "messages_received" "Total messages received")
  (register-counter collector "messages_processed" "Messages successfully processed")
  (register-counter collector "messages_failed" "Failed message processing")
  (register-histogram collector "message_latency" [10 50 100 250 500 1000 2500 5000]
                      "Message processing latency in ms")

  ;; Task metrics
  (register-counter collector "tasks_submitted" "Tasks submitted to workers")
  (register-counter collector "tasks_completed" "Tasks completed successfully")
  (register-counter collector "tasks_failed" "Tasks that failed")
  (register-histogram collector "task_duration" [100 500 1000 5000 10000 30000 60000]
                      "Task execution duration in ms")

  ;; Cache metrics
  (register-counter collector "cache_hits" "Cache hits")
  (register-counter collector "cache_misses" "Cache misses")
  (register-counter collector "cache_evictions" "Cache evictions")

  ;; Session metrics
  (register-gauge collector "sessions_active" (constantly 0)
                  "Active sessions count")

  ;; Worker pool metrics
  (register-gauge collector "workers_active" (constantly 0)
                  "Active worker count")
  (register-gauge collector "workers_idle" (constantly 0)
                  "Idle worker count")
  (register-counter collector "workers_tasks_total" "Total tasks processed by workers"))

(defn update-session-gauge!
  "Update active sessions gauge."
  [collector count]
  (when-let [gauge (get @(:gauges collector) "sessions_active")]
    (swap! (:gauges collector)
           (fn [gauges]
             (assoc gauges "sessions_active"
                    (assoc gauge :value-fn (constantly count)))))))

(defn update-worker-gauges!
  "Update worker pool gauges."
  [collector active-count idle-count]
  (swap! (:gauges collector)
         (fn [gauges]
           (-> gauges
               (update "workers_active"
                       (fn [g] (when g (assoc g :value-fn (constantly active-count)))))
               (update "workers_idle"
                       (fn [g] (when g (assoc g :value-fn (constantly idle-count)))))))))
