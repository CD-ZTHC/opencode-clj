(ns anima-agent-clj.metrics-test
  "Tests for the Metrics Collection System."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anima-agent-clj.metrics :as metrics]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Fixtures
;; ══════════════════════════════════════════════════════════════════════════════

(def ^:dynamic *collector* nil)

(defn with-collector [f]
  (binding [*collector* (metrics/create-collector {:prefix "test" :register-system false})]
    (f)))

(use-fixtures :each with-collector)

;; ══════════════════════════════════════════════════════════════════════════════
;; Counter Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-counter
  (testing "Register and increment counter"
    (metrics/register-counter *collector* "test_counter" "Test counter")
    (is (zero? (metrics/counter-get *collector* "test_counter")))

    (metrics/counter-inc! *collector* "test_counter")
    (is (= 1 (metrics/counter-get *collector* "test_counter")))

    (metrics/counter-inc! *collector* "test_counter")
    (metrics/counter-inc! *collector* "test_counter")
    (is (= 3 (metrics/counter-get *collector* "test_counter"))))

  (testing "Add to counter"
    (metrics/register-counter *collector* "add_counter")
    (metrics/counter-add! *collector* "add_counter" 10)
    (is (= 10 (metrics/counter-get *collector* "add_counter"))))

  (testing "Counter with labels"
    (metrics/register-counter *collector* "labeled_counter" "" {:service "api"})
    (metrics/counter-inc! *collector* "labeled_counter")
    (is (= 1 (metrics/counter-get *collector* "labeled_counter")))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Gauge Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-gauge
  (testing "Register and get gauge"
    (let [state (atom 42)]
      (metrics/register-gauge *collector* "test_gauge"
                              #(deref state)
                              "Test gauge")
      (is (= 42 (metrics/gauge-get *collector* "test_gauge")))

      (reset! state 100)
      (is (= 100 (metrics/gauge-get *collector* "test_gauge")))))

  (testing "Gauge with function"
    (metrics/register-gauge *collector* "time_gauge"
                            #(System/currentTimeMillis))
    (let [value (metrics/gauge-get *collector* "time_gauge")]
      (is (pos? value)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Histogram Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-histogram
  (testing "Register and record histogram"
    (metrics/register-histogram *collector* "latency"
                                [10 50 100 500 1000]
                                "Latency histogram")
    ;; Record values
    (metrics/histogram-record! *collector* "latency" 25)
    (metrics/histogram-record! *collector* "latency" 75)
    (metrics/histogram-record! *collector* "latency" 200)

    (let [stats (metrics/histogram-get *collector* "latency")]
      (is (= 3 (:count stats)))
      (is (= 300 (:sum stats)))))

  (testing "Histogram bucket distribution"
    (metrics/register-histogram *collector* "buckets"
                                [10 100 1000])
    (metrics/histogram-record! *collector* "buckets" 5)   ; bucket 0
    (metrics/histogram-record! *collector* "buckets" 50)  ; bucket 1
    (metrics/histogram-record! *collector* "buckets" 500) ; bucket 2
    (metrics/histogram-record! *collector* "buckets" 5000) ; +Inf bucket

    (let [stats (metrics/histogram-get *collector* "buckets")]
      (is (= 4 (:count stats))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Summary Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-summary
  (testing "Register and record summary"
    (metrics/register-summary *collector* "response_time" 100 "Response times")

    ;; Record some values
    (doseq [v [10 20 30 40 50 60 70 80 90 100]]
      (metrics/summary-record! *collector* "response_time" v))

    (let [stats (metrics/summary-get *collector* "response_time")]
      (is (= 10 (:count stats)))
      (is (= 10 (:min stats)))
      (is (= 100 (:max stats)))
      (is (= 550 (:sum stats)))
      (is (= 55.0 (:mean stats)))))

  (testing "Summary percentiles"
    (metrics/register-summary *collector* "percentiles" 1000)

    ;; Record 100 values
    (doseq [v (range 1 101)]
      (metrics/summary-record! *collector* "percentiles" v))

    (let [stats (metrics/summary-get *collector* "percentiles")]
      (is (some? (:p50 stats)))
      (is (some? (:p90 stats)))
      (is (some? (:p95 stats)))
      (is (some? (:p99 stats))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Get All Metrics Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-get-metrics
  (testing "Get all metrics"
    (metrics/register-counter *collector* "counter1")
    (metrics/register-gauge *collector* "gauge1" (constantly 42))
    (metrics/register-histogram *collector* "hist1")
    (metrics/register-summary *collector* "sum1")

    (metrics/counter-inc! *collector* "counter1")
    (metrics/histogram-record! *collector* "hist1" 100)
    (metrics/summary-record! *collector* "sum1" 50)

    (let [all-metrics (metrics/get-metrics *collector*)]
      (is (map? all-metrics))
      (is (contains? all-metrics :counters))
      (is (contains? all-metrics :gauges))
      (is (contains? all-metrics :histograms))
      (is (contains? all-metrics :summaries))

      (is (= 1 (get-in all-metrics [:counters "counter1"])))
      (is (= 42 (get-in all-metrics [:gauges "gauge1"]))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Prometheus Export Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-prometheus-export
  (testing "Export format"
    (metrics/register-counter *collector* "requests" "Total requests")
    (metrics/register-gauge *collector* "connections"
                            (constantly 5) "Active connections")

    (metrics/counter-inc! *collector* "requests")
    (metrics/counter-inc! *collector* "requests")

    (let [export (metrics/export-prometheus *collector*)]
      (is (string? export))
      (is (clojure.string/includes? export "test_requests"))
      (is (clojure.string/includes? export "test_connections"))
      (is (clojure.string/includes? export "# TYPE"))
      (is (clojure.string/includes? export "# HELP")))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Agent Metrics Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-agent-metrics
  (testing "Register agent metrics"
    (metrics/register-agent-metrics *collector*)

    ;; Verify metrics are registered
    (is (some? (metrics/counter-get *collector* "messages_received")))
    (is (some? (metrics/counter-get *collector* "cache_hits")))

    ;; Increment some metrics
    (metrics/counter-inc! *collector* "messages_received")
    (metrics/counter-inc! *collector* "cache_hits")
    (metrics/counter-inc! *collector* "cache_hits")

    (is (= 1 (metrics/counter-get *collector* "messages_received")))
    (is (= 2 (metrics/counter-get *collector* "cache_hits")))))

;; ══════════════════════════════════════════════════════════════════════════════
;; System Metrics Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-system-metrics
  (let [sys-collector (metrics/create-collector {:prefix "sys" :register-system true})]
    (testing "Memory metrics"
      (is (pos? (metrics/gauge-get sys-collector "memory_max")))
      (is (pos? (metrics/gauge-get sys-collector "memory_used"))))

    (testing "Thread metrics"
      (is (pos? (metrics/gauge-get sys-collector "threads_count"))))

    (testing "Uptime"
      (is (pos? (metrics/gauge-get sys-collector "uptime_ms"))))))
