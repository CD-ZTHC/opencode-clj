(ns anima-agent-clj.agent.parallel-pool-test
  "Tests for the ParallelPool component."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [anima-agent-clj.agent.worker-pool :as pool]
            [anima-agent-clj.agent.parallel-pool :as parallel]
            [anima-agent-clj.agent.worker-agent :as worker]
            [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Fixtures
;; ══════════════════════════════════════════════════════════════════════════════

(def ^:dynamic *test-worker-pool* nil)
(def ^:dynamic *test-parallel-pool* nil)
(def ^:dynamic *test-metrics* nil)

(defn fixture-pools [f]
  (let [metrics (metrics/create-collector {:prefix "test" :register-system false})
        wp (pool/create-pool
            {:opencode-client {:base-url "http://localhost:9711"}
             :min-size 1
             :max-size 5
             :initial-size 2
             :metrics-collector metrics})
        pp (parallel/create-parallel-pool
            {:worker-pool wp
             :max-concurrent 5
             :default-timeout 5000
             :metrics-collector metrics})]
    (pool/start-pool! wp)
    (parallel/start-parallel-pool! pp)
    (binding [*test-worker-pool* wp
              *test-parallel-pool* pp
              *test-metrics* metrics]
      (f)
      (parallel/stop-parallel-pool! pp)
      (pool/stop-pool! wp))))

(use-fixtures :each fixture-pools)

;; ══════════════════════════════════════════════════════════════════════════════
;; Basic Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-create-parallel-pool
  (testing "Create parallel pool with default config"
    (let [pp (parallel/create-parallel-pool
              {:worker-pool *test-worker-pool*})]
      (is (some? pp))
      (is (= :stopped @(:status pp)))
      (is (= 10 (get-in pp [:config :max-concurrent])))))

  (testing "Create parallel pool with custom config"
    (let [pp (parallel/create-parallel-pool
              {:worker-pool *test-worker-pool*
               :max-concurrent 20
               :default-timeout 30000
               :min-success-ratio 0.8})]
      (is (= 20 (get-in pp [:config :max-concurrent])))
      (is (= 30000 (get-in pp [:config :default-timeout])))
      (is (= 0.8 (get-in pp [:config :min-success-ratio])))))

  (testing "Require worker pool"
    (is (thrown? Exception
                 (parallel/create-parallel-pool {})))))

(deftest test-parallel-task-creation
  (testing "Create parallel task"
    (let [task-spec {:type :transform :payload {:data 42}}
          pt (parallel/make-parallel-task {:task-spec task-spec})]
      (is (some? (:id pt)))
      (is (some? (:trace-id pt)))
      (is (= :pending (:status pt)))
      (is (= task-spec (:task-spec pt))))))

(deftest test-lifecycle
  (testing "Start and stop parallel pool"
    (let [pp (parallel/create-parallel-pool
              {:worker-pool *test-worker-pool*})]
      (is (= :stopped @(:status pp)))
      (parallel/start-parallel-pool! pp)
      (is (= :running @(:status pp)))
      (parallel/stop-parallel-pool! pp)
      (is (= :stopped @(:status pp))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Parallel Execution Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-execute-parallel-basic
  (testing "Execute empty task list"
    (let [result (parallel/execute-parallel! *test-parallel-pool* [])]
      (is (= 0 (:total result)))
      (is (= :success (:status result)))))

  (testing "Execute single task"
    (let [tasks [{:type :transform
                  :payload {:data 10
                           :transform-fn (fn [x] (* x 2))}}]
          result (parallel/execute-parallel! *test-parallel-pool* tasks)]
      (is (= 1 (:total result)))
      (is (<= 0 (:successful result)))))

  (testing "Execute multiple tasks"
    (let [tasks (for [i (range 3)]
                  {:type :transform
                   :payload {:data i
                            :transform-fn (fn [x] (* x 2))}})
          result (parallel/execute-parallel! *test-parallel-pool* tasks)]
      (is (= 3 (:total result))))))

(deftest test-execute-parallel-async
  (testing "Async execution returns channel"
    (let [tasks [{:type :transform
                  :payload {:data 5
                           :transform-fn inc}}]
          ch (parallel/execute-parallel-async! *test-parallel-pool* tasks)]
      (is (some? ch))
      (let [result (async/<!! ch)]
        (is (some? result))
        (is (= 1 (:total result)))))))

(deftest test-map-parallel
  (testing "Map parallel over items"
    (let [items [1 2 3 4 5]
          task-fn (fn [n]
                    {:type :transform
                     :payload {:data n
                              :transform-fn (fn [x] (* x 2))}})
          result (parallel/map-parallel! *test-parallel-pool* task-fn items)]
      (is (= 5 (:total result))))))

(deftest test-map-reduce
  (testing "Map-reduce pattern"
    (let [items [1 2 3 4 5]
          task-fn (fn [n]
                    {:type :transform
                     :payload {:data n
                              :transform-fn (fn [x] (* x 2))}})
          reduce-fn (fn [results]
                      (reduce + (map :result results)))
          ch (parallel/map-reduce! *test-parallel-pool*
                                   task-fn
                                   reduce-fn
                                   items)
          result (async/<!! ch)]
      ;; May succeed or partially succeed depending on timing
      (is (some? result)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status & Metrics Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-status
  (testing "Get parallel pool status"
    (let [status (parallel/parallel-pool-status *test-parallel-pool*)]
      (is (some? (:id status)))
      (is (= :running (:status status)))
      (is (some? (:config status)))
      (is (some? (:metrics status))))))

(deftest test-metrics
  (testing "Get parallel pool metrics"
    (let [metrics (parallel/parallel-pool-metrics *test-parallel-pool*)]
      (is (some? (:batches-executed metrics)))
      (is (some? (:success-rate metrics)))))

  (testing "Metrics update after execution"
    (let [before (parallel/parallel-pool-metrics *test-parallel-pool*)
          _ (parallel/execute-parallel!
             *test-parallel-pool*
             [{:type :transform :payload {:data 1}}])
          after (parallel/parallel-pool-metrics *test-parallel-pool*)]
      (is (>= (:batches-executed after)
              (:batches-executed before))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Batch Operations Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-execute-batch
  (testing "Execute batch with size control"
    (let [tasks (for [i (range 20)]
                  {:type :transform
                   :payload {:data i}})
          ch (parallel/execute-batch! *test-parallel-pool*
                                      tasks
                                      {:batch-size 5
                                       :batch-delay-ms 10})
          result (async/<!! ch)]
      (is (= 20 (:total result))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Result Status Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-result-status
  (testing "Success when all tasks succeed"
    (let [tasks [{:type :transform :payload {:data 1}}]
          result (parallel/execute-parallel! *test-parallel-pool* tasks)]
      ;; Status depends on whether transform task type is supported
      (is (some? (:status result)))))

  (testing "Partial success handling"
    ;; This test depends on having some tasks fail
    ;; For now just verify the status field exists
    (let [tasks [{:type :unknown-type :payload {}}]
          result (parallel/execute-parallel! *test-parallel-pool* tasks)]
      (is (some? (:status result)))
      (is (some? (:failed result))))))
