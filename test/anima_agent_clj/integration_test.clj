(ns anima-agent-clj.integration-test
  "Integration tests for the full message flow.
   Tests: Channel → Bus.inbound → Agent → Bus.outbound → Dispatch → Channel"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [anima-agent-clj.bus :as bus]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.dispatch :as dispatch]
            [anima-agent-clj.channel.registry :as registry]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Mock Channel for testing
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord MockChannel
           [name-str ; Channel name
            sent-messages ; Atom<vector> - messages sent to this channel
            should-fail]) ; boolean - simulate failure

(defn create-mock-channel
  "Create a mock channel for testing."
  ([name-str]
   (create-mock-channel name-str false))
  ([name-str should-fail]
   (->MockChannel
    name-str
    (atom [])
    should-fail)))

(extend-type MockChannel
  ch/Channel
  (start [this] {:success true})
  (stop [this] {:success true})
  (send-message [this target message opts]
    (if (:should-fail this)
      {:success false :error "Mock send failed"}
      (do
        (swap! (:sent-messages this) conj
               {:target target :message message :opts opts})
        {:success true})))
  (channel-name [this] (:name-str this))
  (health-check [this] (not (:should-fail this))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Bus Integration Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest bus-inbound-outbound-flow-test
  (testing "Message flows from inbound to outbound via manual relay"
    (let [test-bus (bus/create-bus {:inbound-buf 10 :outbound-buf 10})]
      (let [inbound (bus/make-inbound {:channel "test"
                                       :sender-id "user1"
                                       :content "Hello AI"})]
        (bus/publish-inbound! test-bus inbound)
        (let [received (bus/consume-inbound! test-bus)
              outbound (bus/make-outbound {:channel (:channel received)
                                           :content (str "Echo: " (:content received))
                                           :reply-target (:sender-id received)
                                           :stage :final})]
          (is (= "Hello AI" (:content received)))
          (bus/publish-outbound! test-bus outbound)
          (let [response (bus/consume-outbound! test-bus)]
            (is (= "test" (:channel response)))
            (is (= "Echo: Hello AI" (:content response)))
            (is (= "user1" (:reply-target response)))
            (is (= :final (:stage response))))))
      (bus/close-bus test-bus))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dispatch Integration Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest outbound-dispatch-delivers-to-channel-test
  (testing "Outbound dispatch delivers messages to the correct channel"
    (let [test-bus (bus/create-bus {:inbound-buf 10 :outbound-buf 10})
          mock (create-mock-channel "test")
          reg (registry/create-registry)
          stats (dispatch/create-dispatch-stats)]
      (registry/register reg mock)
      (let [dispatch-thread (dispatch/start-outbound-dispatch
                             (:outbound-chan test-bus) reg stats)]
        (bus/publish-outbound! test-bus
                               (bus/make-outbound {:channel "test"
                                                   :content "Response from AI"
                                                   :reply-target "user1"
                                                   :stage :final}))
        (Thread/sleep 100)
        (is (= 1 (count @(:sent-messages mock))))
        (is (= "Response from AI" (:message (first @(:sent-messages mock)))))
        (let [s (dispatch/get-stats stats)]
          (is (= 1 (:dispatched s)))
          (is (= 0 (:errors s))))
        (bus/close-bus test-bus)
        (async/<!! dispatch-thread)))))

(deftest outbound-dispatch-handles-missing-channel-test
  (testing "Outbound dispatch handles missing channel gracefully"
    (let [test-bus (bus/create-bus {:inbound-buf 10 :outbound-buf 10})
          reg (registry/create-registry)
          stats (dispatch/create-dispatch-stats)]
      (let [dispatch-thread (dispatch/start-outbound-dispatch
                             (:outbound-chan test-bus) reg stats)]
        (bus/publish-outbound! test-bus
                               (bus/make-outbound {:channel "nonexistent"
                                                   :content "Lost message"}))
        (Thread/sleep 100)
        (let [s (dispatch/get-stats stats)]
          (is (= 0 (:dispatched s)))
          (is (= 1 (:channel-not-found s))))
        (bus/close-bus test-bus)
        (async/<!! dispatch-thread)))))

(deftest outbound-dispatch-handles-send-failure-test
  (testing "Outbound dispatch handles channel send failure"
    (let [test-bus (bus/create-bus {:inbound-buf 10 :outbound-buf 10})
          mock (create-mock-channel "failing" true)
          reg (registry/create-registry)
          stats (dispatch/create-dispatch-stats)]
      (registry/register reg mock)
      (let [dispatch-thread (dispatch/start-outbound-dispatch
                             (:outbound-chan test-bus) reg stats)]
        (bus/publish-outbound! test-bus
                               (bus/make-outbound {:channel "failing"
                                                   :content "Will fail"}))
        (Thread/sleep 100)
        (let [s (dispatch/get-stats stats)]
          (is (= 0 (:dispatched s)))
          (is (= 1 (:errors s))))
        (bus/close-bus test-bus)
        (async/<!! dispatch-thread)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Full Flow Integration Test
;; ══════════════════════════════════════════════════════════════════════════════

(deftest full-message-flow-test
  (testing "Complete flow: publish inbound → manual agent → outbound dispatch → channel"
    (let [test-bus (bus/create-bus {:inbound-buf 10 :outbound-buf 10})
          mock (create-mock-channel "cli")
          reg (registry/create-registry)
          stats (dispatch/create-dispatch-stats)]
      (registry/register reg mock)
      (let [dispatch-thread (dispatch/start-outbound-dispatch
                             (:outbound-chan test-bus) reg stats)]
        ;; 1. Channel publishes to inbound
        (bus/publish-inbound! test-bus
                              (bus/make-inbound {:channel "cli"
                                                 :sender-id "user"
                                                 :content "What is Clojure?"}))
        ;; 2. Agent consumes and processes (simulated)
        (let [inbound (bus/consume-inbound! test-bus)]
          (is (= "What is Clojure?" (:content inbound)))
          (bus/publish-outbound! test-bus
                                 (bus/make-outbound {:channel (:channel inbound)
                                                     :content "Clojure is a functional programming language."
                                                     :reply-target (:sender-id inbound)
                                                     :stage :final})))
        ;; 3. Wait for dispatch
        (Thread/sleep 100)
        ;; 4. Verify delivery
        (is (= 1 (count @(:sent-messages mock))))
        (let [delivered (first @(:sent-messages mock))]
          (is (= "Clojure is a functional programming language." (:message delivered)))
          (is (= "user" (:target delivered)))
          (is (= :final (get-in delivered [:opts :stage]))))
        (bus/close-bus test-bus)
        (async/<!! dispatch-thread)))))

(deftest multiple-channels-dispatch-test
  (testing "Dispatch routes to correct channel when multiple channels registered"
    (let [test-bus (bus/create-bus {:inbound-buf 10 :outbound-buf 10})
          cli-mock (create-mock-channel "cli")
          rmq-mock (create-mock-channel "rabbitmq")
          reg (registry/create-registry)
          stats (dispatch/create-dispatch-stats)]
      (registry/register reg cli-mock)
      (registry/register reg rmq-mock)
      (let [dispatch-thread (dispatch/start-outbound-dispatch
                             (:outbound-chan test-bus) reg stats)]
        (bus/publish-outbound! test-bus
                               (bus/make-outbound {:channel "cli"
                                                   :content "CLI response"}))
        (bus/publish-outbound! test-bus
                               (bus/make-outbound {:channel "rabbitmq"
                                                   :content "RMQ response"}))
        (Thread/sleep 200)
        (is (= 1 (count @(:sent-messages cli-mock))))
        (is (= "CLI response" (:message (first @(:sent-messages cli-mock)))))
        (is (= 1 (count @(:sent-messages rmq-mock))))
        (is (= "RMQ response" (:message (first @(:sent-messages rmq-mock)))))
        (let [s (dispatch/get-stats stats)]
          (is (= 2 (:dispatched s))))
        (bus/close-bus test-bus)
        (async/<!! dispatch-thread)))))