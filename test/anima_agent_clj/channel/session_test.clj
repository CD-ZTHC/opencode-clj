(ns anima-agent-clj.channel.session-test
  "Tests for session management."
  (:require [clojure.test :refer [deftest is testing]]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.session :as session]))

;; ════════════════════════════════════════════════════════════════════════════
;; Session Store Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest create-store-test
  (is (some? (session/create-store))))

(deftest session-store-empty-test
  (let [store (session/create-store)]
    (is (= 0 (session/session-count store)))
    (is (empty? (session/get-all-sessions store)))))

;; ════════════════════════════════════════════════════════════════════════════
;; Session CRUD Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest create-session-test
  (let [store (session/create-store)
        sess (session/create-session store "cli")]
    (is (= 1 (session/session-count store)))
    (is (true? (session/session-exists? store (:id sess))))
    (is (string? (:id sess)))
    (is (= "cli" (:channel sess)))
    (is (string? (:routing-key sess)))))

(deftest create-session-with-options-test
  (let [store (session/create-store)
        sess (session/create-session store "rabbitmq"
                                     {:id "test-session-123"
                                      :routing-key "anima.session.test"
                                      :context {:user "alice"}})]
    (is (= 1 (session/session-count store)))
    (is (= "test-session-123" (:id sess)))
    (is (= "anima.session.test" (:routing-key sess)))
    (is (= "alice" (get-in sess [:context :user])))))

(deftest get-session-test
  (let [store (session/create-store)
        sess (session/create-session store "cli")]
    (is (= sess (session/get-session store (:id sess))))
    (is (nil? (session/get-session store "nonexistent")))))

(deftest get-session-by-routing-key-test
  (let [store (session/create-store)
        sess (session/create-session store "rabbitmq"
                                     {:routing-key "anima.session.user-alice"})]
    (is (some? (session/get-session-by-routing-key store "anima.session.user-alice")))
    (is (nil? (session/get-session-by-routing-key store "nonexistent")))))

(deftest get-sessions-by-channel-test
  (let [store (session/create-store)
        _ (session/create-session store "cli")
        _ (session/create-session store "cli")
        _ (session/create-session store "rabbitmq")]
    (is (= 2 (count (session/get-sessions-by-channel store "cli"))))
    (is (= 1 (count (session/get-sessions-by-channel store "rabbitmq"))))))

(deftest close-session-test
  (let [store (session/create-store)
        sess (session/create-session store "cli")]
    (is (= 1 (session/session-count store)))
    (is (some? (session/close-session store (:id sess))))
    (is (= 0 (session/session-count store)))
    (is (nil? (session/get-session store (:id sess))))))

;; ════════════════════════════════════════════════════════════════════════════
;; Session Context Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest update-session-context-test
  (let [store (session/create-store)
        sess (session/create-session store "cli")]
    (session/update-session-context store (:id sess) {:mode "chatting" :user "alice"})
    (let [updated (session/get-session store (:id sess))]
      (is (= "chatting" (get-in updated [:context :mode])))
      (is (= "alice" (get-in updated [:context :user]))))))

(deftest add-to-history-test
  (let [store (session/create-store)
        sess (session/create-session store "cli")]
    (session/add-to-history store (:id sess) {:role "user" :content "Hello"})
    (session/add-to-history store (:id sess) {:role "assistant" :content "Hi there!"})
    (let [history (session/get-history store (:id sess))]
      (is (= 2 (count history)))
      (is (= "user" (:role (first history))))
      (is (= "Hi there!" (:content (second history)))))))

;; ════════════════════════════════════════════════════════════════════════════
;; Session Stats Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest session-count-test
  (let [store (session/create-store)]
    (is (= 0 (session/session-count store)))
    (session/create-session store "cli")
    (is (= 1 (session/session-count store)))
    (session/create-session store "rabbitmq")
    (is (= 2 (session/session-count store)))))

(deftest get-stats-test
  (let [store (session/create-store)
        _ (session/create-session store "cli")
        _ (session/create-session store "cli")
        _ (session/create-session store "rabbitmq")
        stats (session/get-stats store)]
    (is (= 3 (:total stats)))
    (is (= 2 (get-in stats [:by-channel "cli"])))
    (is (= 1 (get-in stats [:by-channel "rabbitmq"])))))

;; ════════════════════════════════════════════════════════════════════════════
;; find-or-create-session Tests
;; ════════════════════════════════════════════════════════════════════════════

(deftest find-or-create-session-by-id-test
  (let [store (session/create-store)
        sess (session/create-session store "cli")]
    (let [found (session/find-or-create-session store "cli" {:session-id (:id sess)})]
      (is (= (:id sess) (:id found))))))

(deftest find-or-create-session-by-routing-key-test
  (let [store (session/create-store)
        sess (session/create-session store "rabbitmq"
                                     {:routing-key "anima.session.test"})]
    (let [found (session/find-or-create-session store "rabbitmq"
                                                {:routing-key "anima.session.test"})]
      (is (= (:id sess) (:id found))))))

(deftest find-or-create-session-creates-new-test
  (let [store (session/create-store)]
    (is (= 0 (session/session-count store)))
    (let [sess (session/find-or-create-session store "web" {})]
      (is (= 1 (session/session-count store)))
      (is (= "web" (:channel sess))))))

