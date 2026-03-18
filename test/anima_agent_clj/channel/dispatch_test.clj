(ns anima-agent-clj.channel.dispatch-test
  "Tests for message dispatch module.

   Tests message routing, statistics, and error handling."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [anima-agent-clj.channel.dispatch :as dispatch]
            [anima-agent-clj.channel.registry :as registry]
            [anima-agent-clj.channel.cli :as cli]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.bus :as bus]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dispatch Statistics Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest create-dispatch-stats-test
  (testing "Create dispatch statistics"
    (let [stats (dispatch/create-dispatch-stats)]
      (is (zero? @(:dispatched stats)))
      (is (zero? @(:errors stats)))
      (is (zero? @(:channel-not-found stats))))))

(deftest reset-stats-test
  (testing "Reset statistics"
    (let [stats (dispatch/create-dispatch-stats)
          _ (reset! (:dispatched stats) 5)
          _ (reset! (:errors stats) 2)
          _ (reset! (:channel-not-found stats) 1)]
      (is (= 5 @(:dispatched stats)))
      (is (= 2 @(:errors stats)))
      (is (= 1 @(:channel-not-found stats)))

      (dispatch/reset-stats stats)
      (is (zero? @(:dispatched stats)))
      (is (zero? @(:errors stats)))
      (is (zero? @(:channel-not-found stats))))))

(deftest get-stats-test
  (testing "Get statistics snapshot"
    (let [stats (dispatch/create-dispatch-stats)
          _ (reset! (:dispatched stats) 3)
          _ (reset! (:errors stats) 1)
          snapshot (dispatch/get-stats stats)]
      (is (= 3 (:dispatched snapshot)))
      (is (= 1 (:errors snapshot)))
      (is (= 0 (:channel-not-found snapshot))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dispatch Message Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest dispatch-to-channel-test
  (testing "Dispatch message to registered channel"
    (let [registry (registry/create-registry)
          cli-ch (cli/create-cli-channel {})
          stats (dispatch/create-dispatch-stats)
          _ (registry/register registry cli-ch)
          msg (bus/make-outbound
               {:channel "cli"
                :content "Test message"
                :stage :final})]
      (let [result (dispatch/dispatch-outbound-message msg registry stats)]
        (is (true? (:success result)))
        (is (= 1 @(:dispatched stats)))))))

(deftest dispatch-channel-not-found-test
  (testing "Dispatch fails when channel not registered"
    (let [registry (registry/create-registry)
          stats (dispatch/create-dispatch-stats)
          msg (bus/make-outbound
               {:channel "nonexistent"
                :content "Test"
                :stage :final})]
      (let [result (dispatch/dispatch-outbound-message msg registry stats)]
        (is (false? (:success result)))
        (is (= "Channel not found" (:error result)))
        (is (= 1 @(:channel-not-found stats)))))))

(deftest dispatch-with-account-id-test
  (testing "Dispatch respects account-id"
    (let [registry (registry/create-registry)
          cli-ch (cli/create-cli-channel {})
          stats (dispatch/create-dispatch-stats)
          _ (registry/register registry cli-ch "production")
          msg (bus/make-outbound
               {:channel "cli"
                :account-id "production"
                :content "Test"
                :stage :final})]
      (let [result (dispatch/dispatch-outbound-message msg registry stats)]
        (is (true? (:success result)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Outbound Dispatch Loop Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest start-dispatch-test
  (testing "Start dispatch returns stats"
    (let [bus (bus/create-bus)
          registry (registry/create-registry)
          result (dispatch/start-dispatch {:bus bus :registry registry})]
      (is (some? (:stats result)))
      (is (some? (:outbound-thread result)))
      ;; Clean up
      (async/close! (:outbound-thread result))
      (bus/close-bus bus))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Error Handling Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest dispatch-with-error-channel-test
  (testing "Dispatch handles errors gracefully"
    (let [registry (registry/create-registry)
          stats (dispatch/create-dispatch-stats)
          ;; Create a channel that will throw an exception
          error-ch (reify ch/Channel
                     (start [this] this)
                     (stop [this] this)
                     (send-message [_this _target _message _opts]
                       (throw (ex-info "Channel error" {})))
                     (channel-name [_this] "error"))
          _ (registry/register registry error-ch)
          msg (bus/make-outbound
               {:channel "error"
                :content "Test"
                :stage :final})]
      (let [result (dispatch/dispatch-outbound-message msg registry stats)]
        (is (false? (:success result)))
        (is (= 1 @(:errors stats)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Reply Target Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest dispatch-uses-reply-target-test
  (testing "Dispatch uses reply-target when available"
    (let [registry (registry/create-registry)
          cli-ch (cli/create-cli-channel {})
          stats (dispatch/create-dispatch-stats)
          _ (registry/register registry cli-ch)
          msg (bus/make-outbound
               {:channel "cli"
                :content "Test"
                :reply-target "target-user"
                :sender-id "original-sender"
                :stage :final})]
      ;; The send-message should receive reply-target as target
      (let [result (dispatch/dispatch-outbound-message msg registry stats)]
        (is (true? (:success result)))))))
