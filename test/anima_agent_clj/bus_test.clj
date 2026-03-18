(ns anima-agent-clj.bus-test
  "Tests for the message bus module."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [anima-agent-clj.bus :as bus]))

(deftest make-inbound-test
  (testing "Create inbound message with required fields"
    (let [msg (bus/make-inbound {:channel "cli"
                                 :content "Hello"})]
      (is (string? (:id msg)))
      (is (= "cli" (:channel msg)))
      (is (= "Hello" (:content msg)))
      (is (= "unknown" (:sender-id msg)))
      (is (= [] (:media msg)))
      (is (= {} (:metadata msg)))))

  (testing "Create inbound message with all fields"
    (let [msg (bus/make-inbound {:channel "rabbitmq"
                                 :sender-id "alice"
                                 :chat-id "chat-1"
                                 :content "Hi there"
                                 :session-key "session-abc"
                                 :media [{:type "image" :url "http://example.com/img.png"}]
                                 :metadata {:routing-key "anima.session.abc"}})]
      (is (= "rabbitmq" (:channel msg)))
      (is (= "alice" (:sender-id msg)))
      (is (= "chat-1" (:chat-id msg)))
      (is (= "Hi there" (:content msg)))
      (is (= "session-abc" (:session-key msg)))
      (is (= 1 (count (:media msg))))
      (is (= "anima.session.abc" (get-in msg [:metadata :routing-key]))))))

(deftest make-outbound-test
  (testing "Create outbound message with required fields"
    (let [msg (bus/make-outbound {:channel "cli"
                                  :content "Response"})]
      (is (string? (:id msg)))
      (is (= "cli" (:channel msg)))
      (is (= "Response" (:content msg)))
      (is (= "default" (:account-id msg)))
      (is (= :final (:stage msg)))
      (is (= [] (:media msg)))))

  (testing "Create outbound message with all fields"
    (let [msg (bus/make-outbound {:channel "rabbitmq"
                                  :account-id "prod"
                                  :chat-id "chat-1"
                                  :content "AI response"
                                  :media [{:type "text"}]
                                  :stage :chunk
                                  :reply-target "anima.session.abc"})]
      (is (= "rabbitmq" (:channel msg)))
      (is (= "prod" (:account-id msg)))
      (is (= :chunk (:stage msg)))
      (is (= "anima.session.abc" (:reply-target msg))))))

(deftest create-bus-test
  (testing "Create bus with default buffer sizes"
    (let [bus (bus/create-bus)]
      (is (some? (:inbound-chan bus)))
      (is (some? (:outbound-chan bus)))
      (bus/close-bus bus)))

  (testing "Create bus with custom buffer sizes"
    (let [bus (bus/create-bus {:inbound-buf 10 :outbound-buf 20})]
      (is (some? (:inbound-chan bus)))
      (bus/close-bus bus))))

(deftest bus-roundtrip-inbound-test
  (testing "Publish and consume inbound message"
    (let [bus (bus/create-bus)
          msg (bus/make-inbound {:channel "cli"
                                 :sender-id "user1"
                                 :content "Hello AI"})]
      (bus/publish-inbound! bus msg)
      (let [received (bus/consume-inbound! bus)]
        (is (= (:id msg) (:id received)))
        (is (= "cli" (:channel received)))
        (is (= "user1" (:sender-id received)))
        (is (= "Hello AI" (:content received))))
      (bus/close-bus bus))))

(deftest bus-roundtrip-outbound-test
  (testing "Publish and consume outbound message"
    (let [bus (bus/create-bus)
          msg (bus/make-outbound {:channel "cli"
                                  :content "AI response"
                                  :reply-target "user1"})]
      (bus/publish-outbound! bus msg)
      (let [received (bus/consume-outbound! bus)]
        (is (= (:id msg) (:id received)))
        (is (= "cli" (:channel received)))
        (is (= "AI response" (:content received)))
        (is (= "user1" (:reply-target received))))
      (bus/close-bus bus))))

(deftest bus-async-roundtrip-test
  (testing "Async publish and blocking consume"
    (let [bus (bus/create-bus)
          msg (bus/make-inbound {:channel "test"
                                 :content "async msg"})]
      (bus/publish-inbound-async! bus msg)
      (let [received (bus/consume-inbound! bus)]
        (is (= (:id msg) (:id received)))
        (is (= "async msg" (:content received))))
      (bus/close-bus bus))))

(deftest bus-close-test
  (testing "Closing bus returns nil on consume"
    (let [bus (bus/create-bus)]
      (bus/close-bus bus)
      (is (nil? (bus/consume-inbound! bus)))
      (is (nil? (bus/consume-outbound! bus))))))

(deftest bus-multiple-messages-test
  (testing "Multiple messages are consumed in order"
    (let [bus (bus/create-bus)]
      (bus/publish-inbound! bus (bus/make-inbound {:channel "cli" :content "msg1"}))
      (bus/publish-inbound! bus (bus/make-inbound {:channel "cli" :content "msg2"}))
      (bus/publish-inbound! bus (bus/make-inbound {:channel "cli" :content "msg3"}))
      (is (= "msg1" (:content (bus/consume-inbound! bus))))
      (is (= "msg2" (:content (bus/consume-inbound! bus))))
      (is (= "msg3" (:content (bus/consume-inbound! bus))))
      (bus/close-bus bus))))