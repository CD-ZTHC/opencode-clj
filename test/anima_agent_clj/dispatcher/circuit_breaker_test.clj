(ns anima-agent-clj.dispatcher.circuit-breaker-test
  "Tests for the circuit breaker module."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anima-agent-clj.dispatcher.circuit-breaker :as cb]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Fixtures
;; ══════════════════════════════════════════════════════════════════════════════

(defn circuit-breaker-fixture [f]
  (f))

(use-fixtures :each circuit-breaker-fixture)

;; ══════════════════════════════════════════════════════════════════════════════
;; Creation Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest create-circuit-breaker-test
  (testing "Creating circuit breaker with defaults"
    (let [breaker (cb/create-circuit-breaker {})]
      (is (string? (:id breaker)))
      (is (= :closed (cb/current-state breaker)))
      (is (= 5 (get-in breaker [:config :failure-threshold])))
      (is (= 3 (get-in breaker [:config :success-threshold])))
      (is (= 60000 (get-in breaker [:config :timeout-ms])))))

  (testing "Creating circuit breaker with custom config"
    (let [breaker (cb/create-circuit-breaker {:failure-threshold 10
                                              :success-threshold 5
                                              :timeout-ms 30000})]
      (is (= 10 (get-in breaker [:config :failure-threshold])))
      (is (= 5 (get-in breaker [:config :success-threshold])))
      (is (= 30000 (get-in breaker [:config :timeout-ms]))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; State Query Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest state-query-test
  (testing "State query functions"
    (let [breaker (cb/create-circuit-breaker {})]
      (is (cb/closed? breaker))
      (is (not (cb/open? breaker)))
      (is (not (cb/half-open? breaker)))
      (is (cb/allows-request? breaker)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Manual Control Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest manual-control-test
  (testing "Manually tripping and resetting"
    (let [breaker (cb/create-circuit-breaker {:id "test-cb"})]
      ;; Trip manually
      (cb/trip! breaker)
      (is (cb/open? breaker))
      (is (not (cb/allows-request? breaker)))

      ;; Reset manually
      (cb/reset! breaker)
      (is (cb/closed? breaker))
      (is (cb/allows-request? breaker))
      (is (= 0 @(:failure-count breaker))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; with-circuit-breaker Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest with-circuit-breaker-success-test
  (testing "Successful execution"
    (let [breaker (cb/create-circuit-breaker {})]
      ;; Successful execution
      (let [result (cb/with-circuit-breaker breaker (fn [] (+ 1 2)))]
        (is (= 3 result)))
      (is (cb/closed? breaker))))

  (testing "Multiple successful executions"
    (let [breaker (cb/create-circuit-breaker {})]
      (dotimes [_ 10]
        (cb/with-circuit-breaker breaker (fn [] :ok)))
      (is (cb/closed? breaker)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest status-test
  (testing "Getting circuit breaker status"
    (let [breaker (cb/create-circuit-breaker {:id "test-cb"})]
      (let [status (cb/circuit-breaker-status breaker)]
        (is (= "test-cb" (:id status)))
        (is (= :closed (:state status)))
        (is (true? (:allows-requests? status)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Metrics Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest metrics-test
  (testing "Getting circuit breaker metrics"
    (let [breaker (cb/create-circuit-breaker {})]
      ;; Execute some operations
      (cb/with-circuit-breaker breaker (fn [] :ok))
      (cb/with-circuit-breaker breaker (fn [] :ok))

      (let [metrics (cb/circuit-breaker-metrics breaker)]
        (is (= :closed (:state metrics)))
        (is (>= (:total-successes metrics) 0))))))
