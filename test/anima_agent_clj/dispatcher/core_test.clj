(ns anima-agent-clj.dispatcher.core-test
  "Tests for the Event Dispatcher core module."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [anima-agent-clj.dispatcher.core :as core]
            [anima-agent-clj.dispatcher.balancer :as balancer]
            [anima-agent-clj.dispatcher.circuit-breaker :as cb]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Fixtures
;; ══════════════════════════════════════════════════════════════════════════════

(defn dispatcher-fixture [f]
  (f))

(use-fixtures :each dispatcher-fixture)

;; ══════════════════════════════════════════════════════════════════════════════
;; Priority Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest priority-levels-test
  (testing "Priority level definitions"
    (is (= 0 (:system core/priority-levels)))
    (is (= 1 (:realtime core/priority-levels)))
    (is (= 5 (:normal core/priority-levels)))
    (is (= 9 (:low core/priority-levels)))))

(deftest get-priority-test
  (testing "Get priority from keyword"
    (is (= 0 (core/get-priority :system)))
    (is (= 1 (core/get-priority :realtime)))
    (is (= 5 (core/get-priority :normal)))
    (is (= 9 (core/get-priority :low))))

  (testing "Get priority from number"
    (is (= 0 (core/get-priority 0)))
    (is (= 5 (core/get-priority 5)))
    (is (= 9 (core/get-priority 9))))

  (testing "Priority clamping"
    (is (= 0 (core/get-priority -1)))
    (is (= 9 (core/get-priority 100)))
    (is (= 5 (core/get-priority nil))))

  (testing "Unknown keyword defaults to 5"
    (is (= 5 (core/get-priority :unknown)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; DispatchMessage Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest make-dispatch-message-test
  (testing "Creating dispatch message with defaults"
    (let [msg (core/make-dispatch-message {:source "test-source"})]
      (is (string? (:id msg)))
      (is (string? (:trace-id msg)))
      (is (= "test-source" (:source msg)))
      (is (nil? (:target msg)))
      (is (nil? (:type msg)))
      (is (= 5 (:priority msg)))
      (is (nil? (:payload msg)))
      (is (= {} (:metadata msg)))
      (is (= 60000 (:ttl msg)))
      (is (= 0 (:retry-count msg)))
      (is (satisfies? clojure.core.async.impl.protocols/Channel (:result-ch msg)))))

  (testing "Creating dispatch message with custom values"
    (let [msg (core/make-dispatch-message
               {:source "source-1"
                :target "agent-1"
                :type :api-call
                :priority :realtime
                :payload {:content "hello"}
                :metadata {:key "value"}
                :ttl 30000})]
      (is (= "source-1" (:source msg)))
      (is (= "agent-1" (:target msg)))
      (is (= :api-call (:type msg)))
      (is (= 1 (:priority msg)))
      (is (= {:content "hello"} (:payload msg)))
      (is (= {:key "value"} (:metadata msg)))
      (is (= 30000 (:ttl msg))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; AgentInfo Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest make-agent-info-test
  (testing "Creating agent info"
    (let [agent-info (core/->AgentInfo
                      "agent-1"
                      :core
                      #{:api-call :inference}
                      nil
                      (atom :available)
                      (atom 0)
                      (atom (java.util.Date.))
                      {})]
      (is (= "agent-1" (:id agent-info)))
      (is (= :core (:type agent-info)))
      (is (= #{:api-call :inference} (:capabilities agent-info)))
      (is (= :available @(:status agent-info)))
      (is (= 0 @(:load agent-info))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dispatcher Creation Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest create-dispatcher-test
  (testing "Creating dispatcher with defaults"
    (let [dispatcher (core/create-dispatcher {})]
      (is (string? (:id dispatcher)))
      (is (nil? (:bus dispatcher)))
      (is (instance? clojure.lang.Atom (:agents dispatcher)))
      (is (instance? anima_agent_clj.dispatcher.balancer.Balancer (:balancer dispatcher)))
      (is (instance? anima_agent_clj.dispatcher.circuit_breaker.CircuitBreaker (:circuit-breaker dispatcher)))
      (is (= :stopped @(:status dispatcher)))
      (is (map? @(:stats dispatcher)))))

  (testing "Creating dispatcher with custom config"
    (let [dispatcher (core/create-dispatcher
                      {:max-queue-size 5000
                       :heartbeat-interval-ms 10000
                       :agent-timeout-ms 30000})]
      (is (= 5000 (get-in dispatcher [:config :max-queue-size])))
      (is (= 10000 (get-in dispatcher [:config :heartbeat-interval-ms])))
      (is (= 30000 (get-in dispatcher [:config :agent-timeout-ms]))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Agent Registration Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest agent-registration-test
  (testing "Registering and unregistering agents"
    (let [dispatcher (core/create-dispatcher {})
          agent-info (core/->AgentInfo
                      "agent-1"
                      :core
                      #{:api-call}
                      nil
                      (atom :available)
                      (atom 0)
                      (atom (java.util.Date.))
                      {})]

      ;; Register
      (core/register-agent! dispatcher agent-info)
      (is (= 1 (count (core/list-agents dispatcher))))
      (is (= agent-info (core/get-agent dispatcher "agent-1")))

      ;; List by type
      (is (= 1 (count (core/list-agents-by-type dispatcher :core))))

      ;; List by capability
      (is (= 1 (count (core/list-agents-by-capability dispatcher :api-call))))

      ;; Unregister
      (core/unregister-agent! dispatcher "agent-1")
      (is (= 0 (count (core/list-agents dispatcher))))
      (is (nil? (core/get-agent dispatcher "agent-1"))))))

(deftest list-available-agents-test
  (testing "Listing available agents"
    (let [dispatcher (core/create-dispatcher {})
          agent1 (core/->AgentInfo
                  "agent-1" :core #{}
                  nil (atom :available) (atom 0)
                  (atom (java.util.Date.)) {})
          agent2 (core/->AgentInfo
                  "agent-2" :worker #{}
                  nil (atom :busy) (atom 1)
                  (atom (java.util.Date.)) {})]

      (core/register-agent! dispatcher agent1)
      (core/register-agent! dispatcher agent2)

      (is (= 1 (count (core/list-available-agents dispatcher))))
      (is (= "agent-1" (:id (first (core/list-available-agents dispatcher))))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Lifecycle Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest start-stop-dispatcher-test
  (testing "Starting and stopping dispatcher"
    (let [dispatcher (core/create-dispatcher {})]
      (is (= :stopped @(:status dispatcher)))

      ;; Start
      (core/start-dispatcher! dispatcher)
      (is (= :running @(:status dispatcher)))

      ;; Stop
      (core/stop-dispatcher! dispatcher)
      (is (= :stopped @(:status dispatcher)))))

  (testing "Restart dispatcher"
    (let [dispatcher (core/create-dispatcher {})]
      (core/start-dispatcher! dispatcher)
      (is (= :running @(:status dispatcher)))

      (core/restart-dispatcher! dispatcher)
      (is (= :running @(:status dispatcher)))

      (core/stop-dispatcher! dispatcher))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dispatch Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest dispatch-test
  (testing "Dispatch returns a channel"
    (let [dispatcher (core/create-dispatcher {})
          result-ch (core/dispatch! dispatcher {:source "test" :type :api-call})]
      (is (satisfies? clojure.core.async.impl.protocols/Channel result-ch))
      ;; Close the channel to clean up
      (async/close! result-ch)))

  (testing "Dispatch queue stats are updated"
    (let [dispatcher (core/create-dispatcher {})
          _ (core/dispatch! dispatcher {:source "test" :type :api-call})]
      (is (= 1 (:messages-queued @(:stats dispatcher))))))

  (testing "Dispatch with available agent returns channel"
    (let [dispatcher (core/create-dispatcher {})
          agent-info (core/->AgentInfo
                      "agent-1"
                      :core
                      #{:api-call}
                      nil
                      (atom :available)
                      (atom 0)
                      (atom (java.util.Date.))
                      {})]
      (core/register-agent! dispatcher agent-info)

      (let [result-ch (core/dispatch! dispatcher {:source "test" :type :api-call})]
        (is (satisfies? clojure.core.async.impl.protocols/Channel result-ch))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status and Metrics Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest dispatcher-status-test
  (testing "Getting dispatcher status"
    (let [dispatcher (core/create-dispatcher {:id "test-dispatcher"})]
      (let [status (core/dispatcher-status dispatcher)]
        (is (= "test-dispatcher" (:id status)))
        (is (= :stopped (:status status)))
        (is (= 0 (:queue-size status)))
        (is (= 0 (:agent-count status)))
        (is (map? (:config status)))
        (is (map? (:stats status)))))))

(deftest dispatcher-metrics-test
  (testing "Getting dispatcher metrics"
    (let [dispatcher (core/create-dispatcher {})]
      (let [metrics (core/dispatcher-metrics dispatcher)]
        (is (map? metrics))
        (is (>= (:queue-size metrics) 0))
        (is (>= (:agent-count metrics) 0))
        (is (>= (:available-count metrics) 0))))))
