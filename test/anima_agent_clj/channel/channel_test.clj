(ns anima-agent-clj.channel.channel-test
  "Tests for channel protocol and implementations."
  (:require [clojure.test :refer [deftest is testing]]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.cli :as cli]
            [anima-agent-clj.channel.registry :as registry]))

;; ════════════════════════════════════════════════════════════════════════════
;; Channel Protocol Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest create-message-test
  (let [msg (ch/create-message {:session-id "test-session"
                                :sender "alice"
                                :content "Hello"
                                :channel "cli"})]
    (is (string? (:id msg)))
    (is (= "test-session" (:session-id msg)))
    (is (= "alice" (:sender msg)))
    (is (= "Hello" (:content msg)))
    (is (= "cli" (:channel msg)))
    (is (number? (:timestamp msg)))))

(deftest channel-message-record-test
  (let [msg (ch/->ChannelMessage
             "msg-123"
             "session-456"
             "alice"
             "Hello World"
             "cli"
             (System/currentTimeMillis)
             nil nil nil false nil nil)]
    (is (= "msg-123" (:id msg)))
    (is (= "session-456" (:session-id msg)))
    (is (= "alice" (:sender msg)))
    (is (= "Hello World" (:content msg)))
    (is (= "cli" (:channel msg)))))

;; ════════════════════════════════════════════════════════════════════════════
;; Routing Key Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest extract-session-id-test
  (is (= "abc123" (ch/extract-session-id "anima.session.abc123")))
  (is (= "user-alice" (ch/extract-session-id "anima.session.user-alice")))
  (is (nil? (ch/extract-session-id "anima.user.alice")))
  (is (nil? (ch/extract-session-id "anima.broadcast"))))

(deftest extract-user-id-test
  (is (= "alice" (ch/extract-user-id "anima.user.alice")))
  (is (= "bob@example.com" (ch/extract-user-id "anima.user.bob@example.com")))
  (is (nil? (ch/extract-user-id "anima.session.abc123"))))

(deftest make-session-routing-key-test
  (is (= "anima.session.abc123" (ch/make-session-routing-key "abc123")))
  (is (= "anima.session.user-alice" (ch/make-session-routing-key "user-alice"))))

(deftest make-user-routing-key-test
  (is (= "anima.user.alice" (ch/make-user-routing-key "alice")))
  (is (= "anima.user.bob@example.com" (ch/make-user-routing-key "bob@example.com"))))

(deftest make-channel-routing-key-test
  (is (= "anima.channel.cli" (ch/make-channel-routing-key "cli")))
  (is (= "anima.channel.rabbitmq" (ch/make-channel-routing-key "rabbitmq"))))

(deftest broadcast-routing-key-test
  (is (= "anima.broadcast" ch/broadcast-routing-key)))

;; ════════════════════════════════════════════════════════════════════════════
;; Helper Functions Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest success?-test
  (is (true? (ch/success? {:success true})))
  (is (true? (ch/success? {:success true :data "ok"})))
  (is (false? (ch/success? {:success false})))
  (is (false? (ch/success? {}))))

(deftest error?-test
  (is (true? (ch/error? {:success false})))
  (is (true? (ch/error? {:success false :error "failed"})))
  (is (false? (ch/error? {:success true})))
  (is (false? (ch/error? {}))))

(deftest ok-test
  (is (= {:success true} (ch/ok)))
  (is (= {:success true :data "test"} (ch/ok {:data "test"}))))

(deftest err-test
  (is (= {:success false :error "Something went wrong"}
         (ch/err "Something went wrong")))
  (is (= {:success false :error "Failed" :code 500}
         (ch/err "Failed" {:code 500}))))

;; ════════════════════════════════════════════════════════════════════════════
;; CLI Channel Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest create-cli-channel-test
  (let [cli-ch (cli/create-cli-channel {})]
    (is (= "cli" (ch/channel-name cli-ch)))
    (is (false? (ch/health-check cli-ch)))  ; Not started yet
    (is (instance? clojure.lang.Atom (:running? cli-ch)))))

(deftest cli-channel-lifecycle-test
  (let [cli-ch (cli/create-cli-channel {})]
    ;; Start
    (ch/start cli-ch)
    (is (true? @(:running? cli-ch)))
    ;; Stop
    (ch/stop cli-ch)
    (is (false? @(:running? cli-ch)))))

(deftest cli-send-message-test
  (let [cli-ch (cli/create-cli-channel {})]
    (ch/start cli-ch)
    ;; Send message (this prints to stdout)
    (let [result (ch/send-message cli-ch "anima.session.test" "Hello World!" {})]
      (is (true? (ch/success? result))))
    (ch/stop cli-ch)))

(deftest cli-quit-command-test
  (is (true? (cli/is-quit-command? "exit")))
  (is (true? (cli/is-quit-command? "quit")))
  (is (true? (cli/is-quit-command? ":q")))
  (is (true? (cli/is-quit-command? "/quit")))
  (is (true? (cli/is-quit-command? "/exit")))
  (is (true? (cli/is-quit-command? "  exit  ")))
  (is (false? (cli/is-quit-command? "hello")))
  (is (false? (cli/is-quit-command? ""))))

;; ════════════════════════════════════════════════════════════════════════════
;; Channel Registry Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest create-registry-test
  (let [reg (registry/create-registry)]
    (is (= 0 (registry/channel-count reg)))))

(deftest register-channel-test
  (let [reg (registry/create-registry)
        cli-ch (cli/create-cli-channel {})]
    (is (= 0 (registry/channel-count reg)))
    (registry/register reg cli-ch)
    (is (= 1 (registry/channel-count reg)))
    (is (= cli-ch (registry/find-channel reg "cli")))
    (is (nil? (registry/find-channel reg "nonexistent")))))

(deftest register-channel-with-account-test
  (let [reg (registry/create-registry)
        cli-ch1 (cli/create-cli-channel {})
        cli-ch2 (cli/create-cli-channel {})]
    (registry/register reg cli-ch1 "default")
    (registry/register reg cli-ch2 "production")
    (is (= cli-ch1 (registry/find-channel reg "cli")))  ; Default account
    (is (= cli-ch2 (registry/find-channel reg "cli" "production")))))

(deftest unregister-channel-test
  (let [reg (registry/create-registry)
        cli-ch (cli/create-cli-channel {})]
    (registry/register reg cli-ch)
    (is (= 1 (registry/channel-count reg)))
    (registry/unregister reg "cli")
    (is (= 0 (registry/channel-count reg)))
    (is (nil? (registry/find-channel reg "cli")))))

(deftest all-channels-test
  (let [reg (registry/create-registry)
        cli-ch (cli/create-cli-channel {})]
    (is (empty? (registry/all-channels reg)))
    (registry/register reg cli-ch)
    (is (= 1 (count (registry/all-channels reg))))))

(deftest channel-names-test
  (let [reg (registry/create-registry)
        cli-ch (cli/create-cli-channel {})]
    (registry/register reg cli-ch)
    (is (= ["cli"] (vec (registry/channel-names reg))))))

(deftest health-report-test
  (let [reg (registry/create-registry)
        cli-ch (cli/create-cli-channel {})]
    (is (false? (:all-healthy? (registry/health-report reg))))  ; No channels
    (registry/register reg cli-ch)
    (is (false? (:all-healthy? (registry/health-report reg))))  ; Not started
    (ch/start cli-ch)
    (is (true? (:all-healthy? (registry/health-report reg))))
    (is (= 1 (:healthy (registry/health-report reg))))
    (ch/stop cli-ch)))
