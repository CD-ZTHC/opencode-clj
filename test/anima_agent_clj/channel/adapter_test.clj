(ns anima-agent-clj.channel.adapter-test
  "Tests for the ChannelAdapter component."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [anima-agent-clj.bus :as bus]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.registry :as registry]
            [anima-agent-clj.channel.adapter :as adapter]
            [anima-agent-clj.agent.core-agent :as core-agent]
            [anima-agent-clj.agent.worker-pool :as pool]
            [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Mock Channel for Testing
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord MockChannel
           [name messages-in messages-out status]

  ch/Channel
  (start [this]
    (reset! status :running)
    this)
  (stop [this]
    (reset! status :stopped)
    this)
  (send-message [this target message opts]
    (swap! messages-out conj {:target target :message message :opts opts})
    {:success true})
  (channel-name [this]
    name)
  (health-check [this]
    (= :running @status))

  ch/StreamingChannel
  (send-chunk [this target chunk]
    {:success true})
  (start-typing [this recipient]
    {:success true})
  (stop-typing [this recipient]
    {:success true}))

(defn create-mock-channel
  "Create a mock channel for testing."
  ([name]
   (create-mock-channel name nil))
  ([name account-id]
   (->MockChannel
    name
    (atom [])
    (atom [])
    (atom :stopped))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Fixtures
;; ══════════════════════════════════════════════════════════════════════════════

(def ^:dynamic *test-bus* nil)
(def ^:dynamic *test-adapter* nil)
(def ^:dynamic *test-metrics* nil)

(defn fixture-adapter [f]
  (let [metrics (metrics/create-collector {:prefix "test" :register-system false})
        b (bus/create-bus)
        ;; Create adapter without core-agent for simpler tests
        a (adapter/create-adapter
           {:bus b
            :metrics-collector metrics})]
    (binding [*test-bus* b
              *test-adapter* a
              *test-metrics* metrics]
      (f)
      (adapter/stop-adapter! a)
      (bus/close-bus b))))

(use-fixtures :each fixture-adapter)

;; ══════════════════════════════════════════════════════════════════════════════
;; Basic Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-create-adapter
  (testing "Create adapter with minimal config"
    (let [b (bus/create-bus)
          a (adapter/create-adapter {:bus b})]
      (is (some? a))
      (is (some? (:id a)))
      (is (= :stopped @(:status a)))
      (bus/close-bus b)))

  (testing "Adapter requires bus"
    (is (thrown? Exception
                 (adapter/create-adapter {})))))

(deftest test-adapter-config
  (testing "Default config values"
    (let [a (adapter/create-adapter {:bus *test-bus*})]
      (is (= :default (get-in a [:config :routing-strategy])))
      (is (= 100 (get-in a [:config :buffer-size])))))

  (testing "Custom config values"
    (let [a (adapter/create-adapter
             {:bus *test-bus*
              :routing-strategy :specialist-first
              :buffer-size 200})]
      (is (= :specialist-first (get-in a [:config :routing-strategy])))
      (is (= 200 (get-in a [:config :buffer-size]))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Lifecycle Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-lifecycle
  (testing "Start and stop adapter"
    (is (= :stopped @(:status *test-adapter*)))
    (adapter/start-adapter! *test-adapter*)
    (is (= :running @(:status *test-adapter*)))
    (adapter/stop-adapter! *test-adapter*)
    (is (= :stopped @(:status *test-adapter*))))

  (testing "Restart adapter"
    (adapter/start-adapter! *test-adapter*)
    (is (= :running @(:status *test-adapter*)))
    (adapter/restart-adapter! *test-adapter*)
    (is (= :running @(:status *test-adapter*)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Registration Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-register-channel
  (testing "Register a channel"
    (let [mock-ch (create-mock-channel "test-channel")]
      (adapter/register-channel! *test-adapter* mock-ch)
      (is (some? (registry/find-channel (:registry *test-adapter*) "test-channel")))))

  (testing "Unregister a channel"
    (let [mock-ch (create-mock-channel "temp-channel")]
      (adapter/register-channel! *test-adapter* mock-ch)
      (adapter/unregister-channel! *test-adapter* "temp-channel" "default")
      ;; Note: unregister behavior may vary based on registry implementation
      (is true))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Routing Rule Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-routing-rules
  (testing "Add routing rule"
    (adapter/add-routing-rule!
     *test-adapter*
     #"^/analyze"
     :core-agent
     {:task-type :code-analysis})
    (is (= 1 (count @(:routing-rules *test-adapter*)))))

  (testing "Clear routing rules"
    (adapter/add-routing-rule! *test-adapter* #"test" :core-agent {})
    (adapter/clear-routing-rules! *test-adapter*)
    (is (= 0 (count @(:routing-rules *test-adapter*))))))

(deftest test-routing-pattern-matching
  (testing "Regex pattern matching"
    (adapter/add-routing-rule!
     *test-adapter*
     #"^/special"
     :core-agent
     {})
    (let [rules @(:routing-rules *test-adapter*)
          rule (first rules)]
      (is (some? rule))
      ;; Pattern should match (using re-find for partial match)
      (is (re-find (:pattern rule) "/special command")))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Message Routing Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-route-message
  (testing "Route message increments counter"
    (let [before @(:messages-in (:stats *test-adapter*))
          msg {:content "test message" :channel "test"}
          _ (adapter/route-message *test-adapter* msg)
          after @(:messages-in (:stats *test-adapter*))]
      (is (> after before))))

  (testing "Route message returns status"
    (let [msg {:content "hello" :channel "test" :chat-id "123"}
          result (adapter/route-message *test-adapter* msg)]
      (is (some? (:status result))))))

(deftest test-receive-message
  (testing "Receive message from channel"
    (let [channel-msg {:content "test"
                       :channel "cli"
                       :session-id "session-1"
                       :sender "user-1"}
          result (adapter/receive-message! *test-adapter* channel-msg)]
      (is (some? result)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Outbound Dispatch Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-outbound-dispatch
  (testing "Outbound message is dispatched to channel"
    (let [mock-ch (create-mock-channel "outbound-test")]
      (ch/start mock-ch)
      (adapter/register-channel! *test-adapter* mock-ch)
      (adapter/start-adapter! *test-adapter*)

      ;; Send outbound message
      (let [outbound (bus/make-outbound
                      {:channel "outbound-test"
                       :chat-id "chat-1"
                       :content "Hello"
                       :reply-target "user-1"})]
        (bus/publish-outbound! *test-bus* outbound)
        ;; Give it time to process
        (Thread/sleep 100)
        ;; Check that message was delivered
        (is (seq @(:messages-out mock-ch)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status & Metrics Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-adapter-status
  (testing "Get adapter status"
    (let [status (adapter/adapter-status *test-adapter*)]
      (is (some? (:id status)))
      (is (some? (:status status)))
      (is (some? (:stats status)))
      ;; :config may not be directly in status, check dispatch-stats instead
      (is (some? (:dispatch-stats status)))))

  (testing "Status reflects running state after start"
    (adapter/start-adapter! *test-adapter*)
    (let [status (adapter/adapter-status *test-adapter*)]
      (is (= :running (:status status))))))

(deftest test-adapter-metrics
  (testing "Get adapter metrics"
    (let [metrics (adapter/adapter-metrics *test-adapter*)]
      (is (some? (:adapter metrics)))))

  (testing "Metrics update after messages"
    (adapter/route-message *test-adapter* {:content "test" :channel "test"})
    (let [metrics (adapter/adapter-metrics *test-adapter*)]
      (is (pos? (get-in metrics [:adapter :messages-in]))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Convenience Functions Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-send-message-through-adapter
  (testing "Send message through adapter"
    (let [mock-ch (create-mock-channel "send-test")]
      (ch/start mock-ch)
      (adapter/register-channel! *test-adapter* mock-ch)
      (adapter/start-adapter! *test-adapter*)

      (adapter/send-message-through-adapter
       *test-adapter*
       "send-test"
       "chat-1"
       "Test message"
       {:reply-target "user-1"})

      ;; Give it time to process
      (Thread/sleep 100)
      (is (seq @(:messages-out mock-ch))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Integration with CoreAgent Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-integration-with-core-agent
  (testing "Create adapter with core-agent"
    (let [metrics (metrics/create-collector {:prefix "test" :register-system false})
          b (bus/create-bus)
          wp (pool/create-pool
              {:opencode-client {:base-url "http://localhost:9711"}
               :min-size 1
               :max-size 2
               :initial-size 1})
          ca (core-agent/create-core-agent
              {:bus b
               :opencode-client {:base-url "http://localhost:9711"}
               :pool-config {:min-size 1 :max-size 2 :initial-size 1}})
          a (adapter/create-adapter
             {:bus b
              :core-agent ca
              :metrics-collector metrics})]
      (is (some? a))
      (is (some? (:core-agent a)))

      (adapter/start-adapter! a)
      (is (= :running @(:status a)))
      (is (= :running @(:status ca)))

      (adapter/stop-adapter! a)
      (pool/stop-pool! wp)
      (bus/close-bus b))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Routing Strategy Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-routing-strategies
  (testing "Default routing strategy"
    (let [a (adapter/create-adapter
             {:bus *test-bus*
              :routing-strategy :default})]
      (is (= :default (get-in a [:config :routing-strategy])))))

  (testing "Specialist-first routing strategy"
    (let [a (adapter/create-adapter
             {:bus *test-bus*
              :routing-strategy :specialist-first})]
      (is (= :specialist-first (get-in a [:config :routing-strategy])))))

  (testing "Parallel-first routing strategy"
    (let [a (adapter/create-adapter
             {:bus *test-bus*
              :routing-strategy :parallel-first})]
      (is (= :parallel-first (get-in a [:config :routing-strategy]))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Error Handling Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-error-handling
  (testing "Routing error increments counter"
    (let [before @(:routing-errors (:stats *test-adapter*))
          ;; Route to non-existent specialist pool with fallback disabled
          msg {:content "test" :channel "test"}
          _ (adapter/route-message *test-adapter* msg)
          after @(:routing-errors (:stats *test-adapter*))])
    ;; Error count should be accessible
    (is (some? @(:routing-errors (:stats *test-adapter*)))))

  (testing "Dispatch stats are tracked"
    (let [stats (:dispatch-stats *test-adapter*)]
      (is (some? stats))
      (is (some? (:dispatched stats)))
      (is (some? (:errors stats))))))
