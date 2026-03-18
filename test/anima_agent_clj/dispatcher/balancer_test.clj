(ns anima-agent-clj.dispatcher.balancer-test
  "Tests for the load balancer module."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anima-agent-clj.dispatcher.balancer :as balancer]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Fixtures
;; ══════════════════════════════════════════════════════════════════════════════

(defn balancer-fixture [f]
  (f))

(use-fixtures :each balancer-fixture)

;; ══════════════════════════════════════════════════════════════════════════════
;; Target Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest make-target-test
  (testing "Creating a target with defaults"
    (let [target (balancer/make-target {})]
      (is (string? (:id target)))
      (is (= 1 (:weight target)))
      (is (= 10 (:capacity target)))
      (is (= {} (:metadata target)))
      (is (= :available @(:status target)))
      (is (= {:active 0 :total 0 :errors 0 :last-error nil} @(:load target)))))

  (testing "Creating a target with custom values"
    (let [target (balancer/make-target {:id "test-1" :weight 5 :capacity 20 :metadata {:host "localhost"}})]
      (is (= "test-1" (:id target)))
      (is (= 5 (:weight target)))
      (is (= 20 (:capacity target)))
      (is (= {:host "localhost"} (:metadata target))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Balancer Creation Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest create-balancer-test
  (testing "Creating balancer with defaults"
    (let [b (balancer/create-balancer {})]
      (is (string? (:id b)))
      (is (= :round-robin (:strategy b)))
      (is (empty? @(:targets b)))
      (is (empty? @(:target-ids b)))))

  (testing "Creating balancer with custom strategy"
    (let [b (balancer/create-balancer {:strategy :least-connections})]
      (is (= :least-connections (:strategy b))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Target Management Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest add-remove-target-test
  (testing "Adding and removing targets"
    (let [b (balancer/create-balancer {})
          t1 (balancer/make-target {:id "t1"})
          t2 (balancer/make-target {:id "t2"})]

      ;; Add targets
      (balancer/add-target! b t1)
      (balancer/add-target! b t2)
      (is (= 2 (count @(:targets b))))
      (is (= 2 (count @(:target-ids b))))

      ;; Get target
      (is (= t1 (balancer/get-target b "t1")))
      (is (= t2 (balancer/get-target b "t2")))

      ;; List targets
      (is (= 2 (count (balancer/list-targets b))))

      ;; Remove target
      (balancer/remove-target! b "t1")
      (is (= 1 (count @(:targets b))))
      (is (nil? (balancer/get-target b "t1"))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Round-Robin Selection Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest round-robin-selection-test
  (testing "Round-robin selection cycles through targets"
    (let [b (balancer/create-balancer {:strategy :round-robin})
          t1 (balancer/make-target {:id "t1"})
          t2 (balancer/make-target {:id "t2"})
          t3 (balancer/make-target {:id "t3"})]
      (balancer/add-target! b t1)
      (balancer/add-target! b t2)
      (balancer/add-target! b t3)

      ;; Should select all targets eventually
      (let [selections (for [_ (range 6)]
                         (:id (balancer/select-target b)))]
        ;; Check that we get 6 selections
        (is (= 6 (count selections)))
        ;; All selections should be valid target ids
        (is (every? #{"t1" "t2" "t3"} selections))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Least-Connections Selection Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest least-connections-selection-test
  (testing "Least-connections selects target with fewest connections"
    (let [b (balancer/create-balancer {:strategy :least-connections})
          t1 (balancer/make-target {:id "t1"})
          t2 (balancer/make-target {:id "t2"})]
      (balancer/add-target! b t1)
      (balancer/add-target! b t2)

      ;; Both have 0 connections, should select one
      (is (some #{(:id (balancer/select-target b))} ["t1" "t2"]))

      ;; Add load to t1
      (balancer/update-load! b "t1" :inc)
      (balancer/update-load! b "t1" :inc)

      ;; t2 should now be selected (fewer connections)
      (is (= "t2" (:id (balancer/select-target b)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Load Tracking Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest load-tracking-test
  (testing "Load tracking operations"
    (let [b (balancer/create-balancer {})
          t1 (balancer/make-target {:id "t1"})]
      (balancer/add-target! b t1)

      ;; Initial load
      (is (= {:active 0 :total 0 :errors 0 :last-error nil} (balancer/get-load b "t1")))

      ;; Increment load
      (balancer/update-load! b "t1" :inc)
      (is (= 1 (:active (balancer/get-load b "t1"))))
      (is (= 1 (:total (balancer/get-load b "t1"))))

      ;; Decrement load
      (balancer/update-load! b "t1" :dec)
      (is (= 0 (:active (balancer/get-load b "t1"))))

      ;; Error tracking
      (balancer/update-load! b "t1" :error)
      (is (= 1 (:errors (balancer/get-load b "t1")))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest status-management-test
  (testing "Setting target status"
    (let [b (balancer/create-balancer {})
          t1 (balancer/make-target {:id "t1"})]
      (balancer/add-target! b t1)

      ;; Mark offline
      (balancer/mark-target-offline! b "t1")
      (is (= :offline @(:status t1)))
      (is (empty? (balancer/list-available-targets b)))

      ;; Mark available
      (balancer/mark-target-available! b "t1")
      (is (= :available @(:status t1)))
      (is (= 1 (count (balancer/list-available-targets b)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Balancer Status Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest balancer-status-test
  (testing "Getting balancer status"
    (let [b (balancer/create-balancer {:strategy :round-robin})
          t1 (balancer/make-target {:id "t1"})]
      (balancer/add-target! b t1)

      (let [status (balancer/balancer-status b)]
        (is (string? (:id status)))
        (is (= :round-robin (:strategy status)))
        (is (= 1 (:target-count status)))
        (is (= 1 (:available-count status)))
        (is (map? (:targets status)))))))

(deftest balancer-metrics-test
  (testing "Getting balancer metrics"
    (let [b (balancer/create-balancer {})
          t1 (balancer/make-target {:id "t1"})
          t2 (balancer/make-target {:id "t2"})]
      (balancer/add-target! b t1)
      (balancer/add-target! b t2)
      (balancer/update-load! b "t1" :inc)
      (balancer/mark-target-offline! b "t2")

      (let [metrics (balancer/balancer-metrics b)]
        (is (= 2 (:total-targets metrics)))
        (is (= 1 (:available-targets metrics)))
        (is (= 1 (:offline-targets metrics)))
        (is (= 1 (:total-active metrics)))))))
