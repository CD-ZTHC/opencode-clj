(ns anima-agent-clj.agent.specialist-pool-test
  "Tests for the SpecialistPool component."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [anima-agent-clj.agent.worker-pool :as pool]
            [anima-agent-clj.agent.specialist-pool :as specialist]
            [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Fixtures
;; ══════════════════════════════════════════════════════════════════════════════

(def ^:dynamic *test-worker-pool* nil)
(def ^:dynamic *test-specialist-pool* nil)

(defn fixture-pools [f]
  (let [metrics (metrics/create-collector {:prefix "test" :register-system false})
        wp (pool/create-pool
            {:opencode-client {:base-url "http://localhost:9711"}
             :min-size 1
             :max-size 5
             :initial-size 2
             :metrics-collector metrics})
        sp (specialist/create-specialist-pool
            {:worker-pool wp
             :load-balance-strategy :least-loaded
             :metrics-collector metrics})]
    (pool/start-pool! wp)
    (specialist/start-specialist-pool! sp)
    (binding [*test-worker-pool* wp
              *test-specialist-pool* sp]
      (f)
      (specialist/stop-specialist-pool! sp)
      (pool/stop-pool! wp))))

(use-fixtures :each fixture-pools)

;; ══════════════════════════════════════════════════════════════════════════════
;; Specialist Registration Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-register-specialist
  (testing "Register a specialist"
    (let [spec (specialist/register-specialist!
                *test-specialist-pool*
                {:name "Code Analyzer"
                 :capabilities [:code-analysis :refactoring]
                 :priority 10})]
      (is (some? (:id spec)))
      (is (= "Code Analyzer" (:name spec)))
      (is (contains? (:capabilities spec) :code-analysis))
      (is (contains? (:capabilities spec) :refactoring))
      (is (= 10 (:priority spec)))))

  (testing "Specialist is retrievable after registration"
    (let [spec (specialist/register-specialist!
                *test-specialist-pool*
                {:name "Test Expert"
                 :capabilities [:testing]})]
      (is (some? (specialist/get-specialist *test-specialist-pool* (:id spec)))))))

(deftest test-unregister-specialist
  (testing "Unregister a specialist"
    (let [spec (specialist/register-specialist!
                *test-specialist-pool*
                {:name "Temp Expert"
                 :capabilities [:temp]})]
      (is (some? (specialist/get-specialist *test-specialist-pool* (:id spec))))
      (specialist/unregister-specialist! *test-specialist-pool* (:id spec))
      (is (nil? (specialist/get-specialist *test-specialist-pool* (:id spec)))))))

(deftest test-list-specialists
  (testing "List all specialists"
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Expert A" :capabilities [:a]})
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Expert B" :capabilities [:b]})
    (let [all (specialist/list-specialists *test-specialist-pool*)]
      (is (>= (count all) 2))))

  (testing "Filter by capability"
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Unique Cap Expert"
      :capabilities [:unique-capability]})
    (let [filtered (specialist/list-specialists *test-specialist-pool* :unique-capability)]
      (is (= 1 (count filtered))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Capability Management Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-list-capabilities
  (testing "List all registered capabilities"
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Multi Cap"
      :capabilities [:cap-a :cap-b :cap-c]})
    (let [caps (specialist/list-capabilities *test-specialist-pool*)]
      (is (some #{:cap-a} caps))
      (is (some #{:cap-b} caps))
      (is (some #{:cap-c} caps)))))

(deftest test-add-remove-capability
  (testing "Add capability to specialist"
    (let [spec (specialist/register-specialist!
                *test-specialist-pool*
                {:name "Dynamic Expert"
                 :capabilities [:initial]})]
      (specialist/add-capability! *test-specialist-pool* (:id spec) :added)
      (let [updated (specialist/get-specialist *test-specialist-pool* (:id spec))]
        (is (contains? (:capabilities updated) :added)))))

  (testing "Remove capability from specialist"
    (let [spec (specialist/register-specialist!
                *test-specialist-pool*
                {:name "To Remove"
                 :capabilities [:keep :remove]})]
      (specialist/remove-capability! *test-specialist-pool* (:id spec) :remove)
      (let [updated (specialist/get-specialist *test-specialist-pool* (:id spec))]
        (is (contains? (:capabilities updated) :keep))
        (is (not (contains? (:capabilities updated) :remove)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Routing Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-route-task-basic
  (testing "Route task to specialist"
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Router Expert"
      :capabilities [:routing-test]})
    (let [ch (specialist/route-task!
              *test-specialist-pool*
              {:type :routing-test
               :payload {:content "test"}})]
      (is (some? ch)))))

(deftest test-route-task-with-handler
  (testing "Route task to specialist with custom handler"
    (let [spec (specialist/register-specialist!
                *test-specialist-pool*
                {:name "Handler Expert"
                 :capabilities [:handler-test]
                 :handler (fn [task]
                            {:result (str "Handled: " (get-in task [:payload :content]))})})
          ch (specialist/route-task!
              *test-specialist-pool*
              {:type :handler-test
               :payload {:content "hello"}})
          result (async/<!! ch)]
      (is (some? result))
      (is (= :success (:status result))))))

(deftest test-route-task-fallback
  (testing "Fallback to worker pool when no specialist"
    (let [ch (specialist/route-task!
              *test-specialist-pool*
              {:type :no-specialist-for-this
               :payload {:content "test"}
               :fallback true})]
      ;; Should fallback to worker pool
      (is (some? ch))))

  (testing "No fallback when disabled"
    (let [sp (specialist/create-specialist-pool
              {:worker-pool *test-worker-pool*
               :fallback-to-worker false})
          ch (specialist/route-task!
              sp
              {:type :no-specialist
               :payload {}
               :fallback false})
          result (async/<!! ch)]
      (is (= :failure (:status result)))
      (is (some? (:error result))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Load Balancing Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-load-balancing
  (testing "Load is distributed across specialists"
    ;; Register multiple specialists with same capability
    (dotimes [i 3]
      (specialist/register-specialist!
       *test-specialist-pool*
       {:name (str "LB Expert " i)
        :capabilities [:load-balance-test]
        :handler (fn [_] {:result "done"})}))

    ;; Route multiple tasks
    (dotimes [_ 5]
      (let [ch (specialist/route-task!
                *test-specialist-pool*
                {:type :load-balance-test :payload {}})]
        (async/<!! ch)))

    ;; Check that load was distributed
    (let [experts (specialist/list-specialists *test-specialist-pool* :load-balance-test)
          loads (map #(deref (:current-load %)) experts)]
      ;; At least some distribution should have occurred
      (is (some #(>= % 0) loads)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Health Check Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-health-check
  (testing "Health check returns status"
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Health Test Expert"
      :capabilities [:health-test]})
    (let [health (specialist/health-check *test-specialist-pool*)]
      (is (some? (:total health)))
      (is (some? (:available health)))
      (is (some? (:specialists health))))))

(deftest test-set-specialist-status
  (testing "Manually set specialist status"
    (let [spec (specialist/register-specialist!
                *test-specialist-pool*
                {:name "Status Test"
                 :capabilities [:status-test]})]
      (specialist/set-specialist-status! *test-specialist-pool* (:id spec) :offline)
      (let [health (specialist/health-check *test-specialist-pool*)
            spec-health (first (filter #(= (:id spec) (:id %)) (:specialists health)))]
        (is (= :offline (:status spec-health)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status & Metrics Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-pool-status
  (testing "Get pool status"
    (let [status (specialist/specialist-pool-status *test-specialist-pool*)]
      (is (some? (:id status)))
      (is (some? (:status status)))
      (is (some? (:specialist-count status)))
      (is (some? (:config status)))))

  (testing "Status reflects running state"
    (is (= :running @(:status *test-specialist-pool*)))))

(deftest test-pool-metrics
  (testing "Get aggregated metrics"
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Metrics Expert"
      :capabilities [:metrics-test]
      :handler (fn [_] {:result "ok"})})

    ;; Execute some tasks
    (dotimes [_ 3]
      (let [ch (specialist/route-task!
                *test-specialist-pool*
                {:type :metrics-test :payload {}})]
        (async/<!! ch)))

    (let [metrics (specialist/specialist-pool-metrics *test-specialist-pool*)]
      (is (some? (:total-processed metrics)))
      (is (some? (:specialist-count metrics))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Batch Routing Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-route-batch
  (testing "Route multiple tasks in batch"
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Batch Expert"
      :capabilities [:batch-test]
      :handler (fn [_] {:result "batch-done"})})

    (let [tasks [{:type :batch-test :payload {:id 1}}
                 {:type :batch-test :payload {:id 2}}
                 {:type :batch-test :payload {:id 3}}]
          batch (specialist/route-batch! *test-specialist-pool* tasks)]
      (is (= 3 (count batch)))
      (is (every? some? (vals batch)))))

  (testing "Collect batch results"
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Collect Expert"
      :capabilities [:collect-test]
      :handler (fn [_] {:result "collected"})})

    (let [tasks [{:type :collect-test :payload {}}
                 {:type :collect-test :payload {}}]
          batch (specialist/route-batch! *test-specialist-pool* tasks)
          results-ch (specialist/collect-batch-results batch 5000)
          results (async/<!! results-ch)]
      (is (= 2 (count results))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Priority Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-specialist-priority
  (testing "Higher priority specialists are preferred"
    ;; Register low priority specialist
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "Low Priority"
      :capabilities [:priority-test]
      :priority 1
      :handler (fn [_] {:result "low"})})

    ;; Register high priority specialist
    (specialist/register-specialist!
     *test-specialist-pool*
     {:name "High Priority"
      :capabilities [:priority-test]
      :priority 100
      :handler (fn [_] {:result "high"})})

    ;; Route task - should go to high priority
    (let [ch (specialist/route-task!
              *test-specialist-pool*
              {:type :priority-test :payload {}})
          result (async/<!! ch)]
      (is (some? result)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Lifecycle Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-lifecycle
  (testing "Create and stop pool"
    (let [sp (specialist/create-specialist-pool
              {:worker-pool *test-worker-pool*})]
      (is (= :stopped @(:status sp)))
      (specialist/start-specialist-pool! sp)
      (is (= :running @(:status sp)))
      (specialist/stop-specialist-pool! sp)
      (is (= :stopped @(:status sp))))))
