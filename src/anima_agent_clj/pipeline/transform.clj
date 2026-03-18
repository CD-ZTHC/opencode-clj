(ns anima-agent-clj.pipeline.transform
  "Data transformers for the Pipeline system.

   Provides various transform implementations:
   - Map Transform: Apply function to each item
   - Mapcat Transform: Apply function and flatten results
   - Filter Transform: Keep items that pass predicate
   - Batch Transform: Group items into batches
   - Unbatch Transform: Flatten batches
   - Parallel Transform: Apply function in parallel

   Usage:
   ;; Map transform
   (def inc-xform (map-transform inc))

   ;; Mapcat transform
   (def flat-xform (mapcat-transform (fn [x] [x (* x 2)])))

   ;; Filter transform
   (def pos-xform (filter-transform pos?))

   ;; Parallel transform
   (def slow-xform (parallel-transform expensive-fn {:parallelism 4}))"
  (:require [clojure.core.async :as async]
            [anima-agent-clj.pipeline.core :as core])
  (:import [java.util UUID Date]
           [java.util.concurrent Executors ExecutorService]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Map Transform
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord MapTransform
  [id              ; String - Transform identifier
   transform-fn    ; Function - Transform function
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   ]

  core/ITransform
  (transform [this data context]
    (try
      (let [result ((:transform-fn this) data)]
        (swap! (:stats this) update :items-transformed inc)
        result)
      (catch Exception e
        (swap! (:stats this) update :errors inc)
        (throw e)))))

(defn map-transform
  "Create a transform that applies a function to each item.

   Example:
   (map-transform inc)
   (map-transform (fn [x] (* x 2)))"
  [f]
  (->MapTransform
   (str (UUID/randomUUID))
   f
   {}
   (atom {:items-transformed 0
          :errors 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Mapcat Transform
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord MapcatTransform
  [id              ; String - Transform identifier
   transform-fn    ; Function - Transform function returning collection
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   ]

  core/ITransform
  (transform [this data context]
    (try
      (let [result ((:transform-fn this) data)]
        (swap! (:stats this) update :items-transformed inc)
        ;; Return the collection - pipeline will need to handle this
        result)
      (catch Exception e
        (swap! (:stats this) update :errors inc)
        (throw e)))))

(defn mapcat-transform
  "Create a transform that applies a function and returns a collection.
   The pipeline will flatten the results.

   Example:
   (mapcat-transform (fn [x] [x (* x 2)]))"
  [f]
  (->MapcatTransform
   (str (UUID/randomUUID))
   f
   {}
   (atom {:items-transformed 0
          :errors 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Filter Transform
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord FilterTransform
  [id              ; String - Transform identifier
   predicate       ; Function - Filter predicate
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   ]

  core/IFilter
  (passes? [this data context]
    (try
      (let [result ((:predicate this) data)]
        (if result
          (swap! (:stats this) update :items-passed inc)
          (swap! (:stats this) update :items-filtered inc))
        result)
      (catch Exception e
        (swap! (:stats this) update :errors inc)
        false))))

(defn filter-transform
  "Create a filter that keeps items passing the predicate.

   Example:
   (filter-transform pos?)
   (filter-transform (fn [x] (> x 10)))"
  [pred]
  (->FilterTransform
   (str (UUID/randomUUID))
   pred
   {}
   (atom {:items-passed 0
          :items-filtered 0
          :errors 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Batch Transform
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord BatchTransform
  [id              ; String - Transform identifier
   batch-size      ; Integer - Batch size
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   current-batch   ; atom<vector> - Current batch accumulator
   ]

  core/ITransform
  (transform [this data context]
    (swap! (:current-batch this) conj data)
    (when (= (count @(:current-batch this)) (:batch-size this))
      (let [batch @(:current-batch this)]
        (reset! (:current-batch this) [])
        (swap! (:stats this) update :batches-created inc)
        batch))))

(defn batch-transform
  "Create a transform that groups items into batches.

   Note: Call flush-batch! at the end to get remaining items.

   Example:
   (batch-transform 100)"
  ([batch-size]
   (->BatchTransform
    (str (UUID/randomUUID))
    batch-size
    {}
    (atom {:batches-created 0})
    (atom []))))

(defn flush-batch!
  "Flush remaining items in batch."
  [batch-transform]
  (when (seq @(:current-batch batch-transform))
    (let [batch @(:current-batch batch-transform)]
      (reset! (:current-batch batch-transform) [])
      batch)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Unbatch Transform
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord UnbatchTransform
  [id              ; String - Transform identifier
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   ]

  core/ITransform
  (transform [this data context]
    (if (sequential? data)
      (do
        (swap! (:stats this) update :batches-expanded inc)
        (swap! (:stats this) update :items-emitted + (count data))
        data)  ; Return as-is, pipeline will handle
      data)))

(defn unbatch-transform
  "Create a transform that flattens batches.

   Example:
   (unbatch-transform)"
  []
  (->UnbatchTransform
   (str (UUID/randomUUID))
   {}
   (atom {:batches-expanded 0
          :items-emitted 0})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Parallel Transform
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord ParallelTransform
  [id              ; String - Transform identifier
   transform-fn    ; Function - Transform function
   parallelism     ; Integer - Number of parallel workers
   executor        ; ExecutorService - Thread pool
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   ]

  core/ITransform
  (transform [this data context]
    ;; For single item, just apply the function
    ;; For batches, use parallel execution
    (if (sequential? data)
      (let [items data
            result-ch (async/chan (count items))]
        ;; Submit all items to thread pool
        (doseq [item items]
          (.submit ^ExecutorService (:executor this)
                   ^Runnable (fn []
                     (try
                       (let [result ((:transform-fn this) item)]
                         (async/put! result-ch result)
                         (swap! (:stats this) update :items-transformed inc))
                       (catch Exception e
                         (swap! (:stats this) update :errors inc)
                         (async/put! result-ch {:error (.getMessage e)}))))))
        ;; Collect results
        (async/<!!
         (async/go
           (loop [results []
                  remaining (count items)]
             (if (zero? remaining)
               (do (async/close! result-ch) results)
               (if-let [r (async/<! result-ch)]
                 (recur (conj results r) (dec remaining))
                 results))))))
      ;; Single item
      (try
        (let [result ((:transform-fn this) data)]
          (swap! (:stats this) update :items-transformed inc)
          result)
        (catch Exception e
          (swap! (:stats this) update :errors inc)
          (throw e)))))

  java.lang.AutoCloseable
  (close [this]
    (when-let [exec ^ExecutorService (:executor this)]
      (.shutdown exec))))

(defn parallel-transform
  "Create a transform that applies a function in parallel.

   Options:
     :parallelism - Number of parallel workers (default: 4)

   Example:
   (parallel-transform expensive-fn {:parallelism 8})"
  ([f]
   (parallel-transform f {}))
  ([f {:keys [parallelism]
       :or {parallelism 4}}]
   (->ParallelTransform
    (str (UUID/randomUUID))
    f
    parallelism
    (Executors/newFixedThreadPool parallelism)
    {}
    (atom {:items-transformed 0
           :errors 0}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Compose Transforms
;; ══════════════════════════════════════════════════════════════════════════════

(defn compose-transforms
  "Compose multiple transforms into a single transform.

   Returns a new transform that applies each transform in sequence.

   Example:
   (compose-transforms
     (map-transform inc)
     (filter-transform pos?)
     (map-transform #(* % 2)))"
  [& transforms]
  (map-transform
   (fn [data]
     (reduce (fn [d t]
               (if (nil? d)
                 nil
                 (cond
                   (instance? MapTransform t)
                   (core/transform t d nil)

                   (instance? FilterTransform t)
                   (when (core/passes? t d nil) d)

                   (instance? MapcatTransform t)
                   (core/transform t d nil)

                   :else d)))
             data
             transforms))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Utility Transforms
;; ══════════════════════════════════════════════════════════════════════════════

(defn identity-transform
  "Create a transform that passes data through unchanged."
  []
  (map-transform identity))

(defn tap-transform
  "Create a transform that taps data (passes through but also calls side-effect fn).

   Example:
   (tap-transform #(println \"Saw:\" %))"
  [tap-fn]
  (map-transform
   (fn [data]
     (tap-fn data)
     data)))

(defn timing-transform
  "Create a transform that measures timing of processing.

   Calls callback with timing info after each item.

   Example:
   (timing-transform #(swap! timings conj %))"
  [callback]
  (map-transform
   (fn [data]
     (let [start (System/nanoTime)
           result data  ; Just pass through
           end (System/nanoTime)]
       (callback {:data data
                  :duration-ns (- end start)})
       result))))

(defn throttle-transform
  "Create a transform that throttles processing rate.

   Options:
     :rate-per-second - Maximum items per second (default: 100)

   Example:
   (throttle-transform {:rate-per-second 10})"
  [{:keys [rate-per-second]
    :or {rate-per-second 100}}]
  (let [interval-ms (/ 1000.0 rate-per-second)
        last-time (atom 0)]
    (map-transform
     (fn [data]
       (let [now (System/currentTimeMillis)
             elapsed (- now @last-time)
             wait-time (- interval-ms elapsed)]
         (when (pos? wait-time)
           (Thread/sleep (long wait-time)))
         (reset! last-time (System/currentTimeMillis))
         data)))))
