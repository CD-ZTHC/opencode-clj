(ns anima-agent-clj.channel.cli-test
  "Tests for CLI channel module.

   Tests CLI channel creation, commands, and message handling."
  (:require [clojure.test :refer [deftest is testing]]
            [anima-agent-clj.channel.cli :as cli]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.bus :as bus]))

;; ══════════════════════════════════════════════════════════════════════════════
;; CLI Channel Creation Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest create-cli-channel-test
  (testing "Create CLI channel with default options"
    (let [cli-ch (cli/create-cli-channel {})]
      (is (= "cli" (ch/channel-name cli-ch)))
      (is (false? @(:running? cli-ch)))
      (is (nil? (:bus cli-ch)))
      (is (= "anima> " (:prompt cli-ch))))))

(deftest create-cli-channel-with-bus-test
  (testing "Create CLI channel with bus"
    (let [bus (bus/create-bus)
          cli-ch (cli/create-cli-channel {:bus bus})]
      (is (= bus (:bus cli-ch))))))

(deftest create-cli-channel-with-prompt-test
  (testing "Create CLI channel with custom prompt"
    (let [cli-ch (cli/create-cli-channel {:prompt "custom> "})]
      (is (= "custom> " (:prompt cli-ch))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; CLI Channel Lifecycle Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest cli-start-test
  (testing "Start CLI channel"
    (let [cli-ch (cli/create-cli-channel {})]
      (is (false? @(:running? cli-ch)))
      (ch/start cli-ch)
      (is (true? @(:running? cli-ch)))
      (ch/stop cli-ch))))

(deftest cli-stop-test
  (testing "Stop CLI channel"
    (let [cli-ch (cli/create-cli-channel {})]
      (ch/start cli-ch)
      (is (true? @(:running? cli-ch)))
      (ch/stop cli-ch)
      (is (false? @(:running? cli-ch))))))

(deftest cli-health-check-test
  (testing "CLI channel health check"
    (let [cli-ch (cli/create-cli-channel {})]
      (is (false? (ch/health-check cli-ch)))
      (ch/start cli-ch)
      (is (true? (ch/health-check cli-ch)))
      (ch/stop cli-ch)
      (is (false? (ch/health-check cli-ch))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; CLI Send Message Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest cli-send-message-test
  (testing "CLI channel sends message"
    (let [cli-ch (cli/create-cli-channel {})]
      (ch/start cli-ch)
      (let [result (ch/send-message cli-ch "test-target" "Hello!" {})]
        (is (true? (:success result))))
      (ch/stop cli-ch))))

(deftest cli-send-message-with-options-test
  (testing "CLI channel sends message with options"
    (let [cli-ch (cli/create-cli-channel {})]
      (ch/start cli-ch)
      (let [result (ch/send-message cli-ch "target" "Test message" {:stage :chunk})]
        (is (true? (:success result))))
      (ch/stop cli-ch))))

;; ══════════════════════════════════════════════════════════════════════════════
;; CLI Streaming Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest cli-send-chunk-test
  (testing "CLI channel sends chunk"
    (let [cli-ch (cli/create-cli-channel {})]
      (ch/start cli-ch)
      (let [result (ch/send-chunk cli-ch "target" "chunk text")]
        (is (true? (:success result))))
      (ch/stop cli-ch))))

(deftest cli-typing-indicators-test
  (testing "CLI channel typing indicators"
    (let [cli-ch (cli/create-cli-channel {})]
      (ch/start cli-ch)
      (is (true? (:success (ch/start-typing cli-ch "target"))))
      (is (true? (:success (ch/stop-typing cli-ch "target"))))
      (ch/stop cli-ch))))

;; ══════════════════════════════════════════════════════════════════════════════
;; CLI Command Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest quit-command-test
  (testing "Quit commands are recognized"
    (is (true? (cli/is-quit-command? "exit")))
    (is (true? (cli/is-quit-command? "quit")))
    (is (true? (cli/is-quit-command? ":q")))
    (is (true? (cli/is-quit-command? "/quit")))
    (is (true? (cli/is-quit-command? "/exit")))
    (is (true? (cli/is-quit-command? "bye")))
    (is (true? (cli/is-quit-command? "  exit  ")))
    (is (false? (cli/is-quit-command? "hello")))
    (is (false? (cli/is-quit-command? "exitnow")))
    (is (false? (cli/is-quit-command? "")))))

;; ══════════════════════════════════════════════════════════════════════════════
;; CLI Channel with Session Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest cli-channel-with-session-test
  (testing "CLI channel can be created with session"
    (let [session {:id "test-session"
                   :channel "cli"
                   :routing-key "anima.session.test"
                   :context {}}
          cli-ch (cli/create-cli-channel-with-session session {})]
      (is (= session (:default-session cli-ch))))))
