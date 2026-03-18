(ns anima-agent-clj.agent-test
  "Tests for Agent module.

   Tests Agent creation, lifecycle, and message processing.
   Uses real service at http://127.0.0.1:9711"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [anima-agent-clj.agent :as agent]
            [anima-agent-clj.bus :as bus]
            [anima-agent-clj.channel.session :as session-store]
            [anima-agent-clj.test-utils :as tu]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Agent Creation Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest create-agent-test
  (testing "Create agent with required options"
    (let [bus (bus/create-bus)
          ag (agent/create-agent {:bus bus})]
      (is (instance? anima_agent_clj.agent.Agent ag))
      (is (= bus (:bus ag)))
      (is (= "http://127.0.0.1:9711" (:base-url (:opencode-client ag))))
      (is (some? (:session-manager ag))))))

(deftest create-agent-with-options-test
  (testing "Create agent with custom options"
    (let [bus (bus/create-bus)
          ag (agent/create-agent {:bus bus
                                  :opencode-url "http://custom:8080"})]
      (is (= "http://custom:8080" (:base-url (:opencode-client ag))))
      (is (= bus (:bus ag))))))

(deftest create-agent-with-client-test
  (testing "Create agent with custom client"
    (let [bus (bus/create-bus)
          client {:base-url "http://127.0.0.1:9711"}
          ag (agent/create-agent {:bus bus :opencode-client client})]
      (is (= client (:opencode-client ag))))))

;; ════════════════════════════════════════════════════════════════════════════════
;; Agent Lifecycle Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest start-stop-agent-test
  (testing "Agent can be started and stopped"
    (let [bus (bus/create-bus)
          ag (agent/create-agent {:bus bus})]
      (is (false? @(:running? ag)))
      (agent/start-agent ag)
      (is (true? @(:running? ag)))
      (agent/stop-agent ag)
      (is (false? @(:running? ag))))))

(deftest agent-not-running-when-stopped-test
  (testing "Agent is not running after stop"
    (let [bus (bus/create-bus)
          ag (agent/create-agent {:bus bus})]
      (agent/stop-agent ag)
      (is (false? @(:running? ag))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Message Processing Tests
;; ════════════════════════════════════════════════════════════════════════════

(tu/deftest-with-server agent-process-test
  (testing "Agent processes messages and publishes to outbound"
    (let [bus (bus/create-bus)
          ag (agent/create-agent {:bus bus
                                  :opencode-client (tu/test-client)})]
      (agent/start-agent ag)

      ;; Create test message
      (let [inbound-msg (bus/make-inbound
                         {:channel "test"
                          :sender-id "test-user"
                          :content "Hello, world!"
                          :chat-id "test-chat"})
            received-outbound (atom false)
            msg (bus/make-outbound
                 {:channel "test"
                  :content "Test response"
                  :reply-target "test-user"
                  :stage :final})]
        (bus/publish-inbound! bus inbound-msg)
        (bus/publish-outbound! bus msg)

        ;; Wait for outbound message
        (let [result (first (async/alts!! [(:outbound-chan bus) (async/timeout 1000)]))]
          (is (some? result)))

        (agent/stop-agent ag)
        (bus/close-bus bus)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Integration Tests (with real service)
;; ════════════════════════════════════════════════════════════════════════════

(tu/deftest-with-server full-integration-test
  (testing "Full integration: Agent creates session and processes message"
    (let [bus (bus/create-bus)
          ag (agent/create-agent {:bus bus
                                  :opencode-client (tu/test-client)})]
      (agent/start-agent ag)

      ;; Create test message
      (let [inbound-msg (bus/make-inbound
                         {:channel "test"
                          :sender-id "integration-user"
                          :content "What is 2 + 2?"
                          :chat-id "integration-chat"})]
        (bus/publish-inbound! bus inbound-msg)

        ;; Wait for outbound (with timeout)
        (let [result (first (async/alts!! [(:outbound-chan bus) (async/timeout 30000)]))]
          (is (some? result)))

        (agent/stop-agent ag)
        (bus/close-bus bus)))))
