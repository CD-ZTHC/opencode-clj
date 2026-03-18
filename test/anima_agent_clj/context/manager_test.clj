(ns anima-agent-clj.context.manager-test
  "Tests for the Context Management System."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anima-agent-clj.context.core :as core]
            [anima-agent-clj.context.storage :as storage]
            [anima-agent-clj.context.manager :as manager]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Fixtures
;; ══════════════════════════════════════════════════════════════════════════════

(def ^:dynamic *manager* nil)

(defn with-manager [f]
  (binding [*manager* (manager/create-manager {:enable-l2 false :enable-gc false})]
    (try
      (f)
      (finally
        (manager/close-manager *manager*)))))

(use-fixtures :each with-manager)

;; ══════════════════════════════════════════════════════════════════════════════
;; Core Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-make-context-entry
  (testing "Creating context entry"
    (let [entry (core/make-context-entry "test:key" {:data "value"})]
      (is (= "test:key" (:key entry)))
      (is (= {:data "value"} (:value entry)))
      (is (= :l1 (:tier entry)))
      (is (some? (:created-at entry))))))

(deftest test-key-operations
  (testing "Key prefix extraction"
    (is (= "session" (core/key-prefix "session:123:history")))
    (is (= "task" (core/key-prefix "task:abc")))
    (is (= nil (core/key-prefix "no-prefix"))))

  (testing "Key parts"
    (is (= ["session" "123" "history"] (core/key-parts "session:123:history"))))

  (testing "Pattern matching"
    (is (core/matches-pattern? "session:123" "session:*"))
    (is (core/matches-pattern? "session:123:history" "session:*"))
    (is (not (core/matches-pattern? "task:123" "session:*")))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Manager Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-basic-operations
  (testing "Set and get context"
    (core/set-context! *manager* "test:1" {:value "hello"})
    (is (= {:value "hello"} (core/get-context *manager* "test:1"))))

  (testing "Get non-existent key"
    (is (nil? (core/get-context *manager* "non-existent"))))

  (testing "Delete context"
    (core/set-context! *manager* "test:2" {:value "to-delete"})
    (is (true? (core/delete-context! *manager* "test:2")))
    (is (nil? (core/get-context *manager* "test:2")))))

(deftest test-get-or-create
  (testing "Get or create with new key"
    (let [result (core/get-or-create *manager* "new:key" (fn [] {:created true}))]
      (is (= {:created true} result))
      (is (= {:created true} (core/get-context *manager* "new:key")))))

  (testing "Get or create with existing key"
    (core/set-context! *manager* "existing:key" {:value "original"})
    (let [result (core/get-or-create *manager* "existing:key" (fn [] {:created false}))]
      (is (= {:value "original"} result)))))

(deftest test-session-operations
  (testing "Session context"
    (manager/set-session-context! *manager* "sess-123" {:user "alice"})
    (is (= {:user "alice"} (manager/get-session-context *manager* "sess-123"))))

  (testing "Session history"
    (manager/add-to-session-history! *manager* "sess-123" {:role :user :content "Hello"})
    (manager/add-to-session-history! *manager* "sess-123" {:role :assistant :content "Hi!"})
    (let [history (manager/get-session-history *manager* "sess-123")]
      (is (= 2 (count history)))
      (is (= :user (:role (first history))))))

  (testing "Clear session context"
    (manager/set-session-context! *manager* "sess-456" {:data "to-clear"})
    (manager/clear-session-context! *manager* "sess-456")
    (is (nil? (manager/get-session-context *manager* "sess-456")))))

(deftest test-task-operations
  (testing "Task context"
    (manager/set-task-context! *manager* "task-1" {:status :running})
    (is (= {:status :running} (manager/get-task-context *manager* "task-1"))))

  (testing "Task result"
    (manager/set-task-result! *manager* "task-1" {:output "done"})
    (is (= {:output "done"} (manager/get-task-result *manager* "task-1")))))

(deftest test-snapshot
  (testing "Create and restore snapshot"
    (core/set-context! *manager* "snap:test" {:version 1})
    (let [snap (core/snapshot *manager* "snap:test")]
      (is (some? snap))
      (is (= "snap:test" (:key snap)))
      ;; Modify context
      (core/set-context! *manager* "snap:test" {:version 2})
      (is (= {:version 2} (core/get-context *manager* "snap:test")))
      ;; Restore snapshot
      (core/restore *manager* (:id snap))
      (is (= {:version 1} (core/get-context *manager* "snap:test"))))))

(deftest test-stats
  (testing "Stats include entry count"
    (core/set-context! *manager* "stats:1" {:a 1})
    (core/set-context! *manager* "stats:2" {:b 2})
    (let [stats (core/get-stats *manager*)]
      (is (>= (get-in stats [:l1 :entries]) 2)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Storage Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-memory-storage
  (let [storage (storage/create-memory-storage {:max-entries 100})]
    (testing "Set and get"
      (core/set-entry storage "key1" "value1" {})
      (is (= "value1" (:value (core/get-entry storage "key1")))))

    (testing "Delete"
      (is (true? (core/delete-entry storage "key1")))
      (is (nil? (core/get-entry storage "key1"))))

    (testing "Entry count"
      (core/set-entry storage "key2" "value2" {})
      (core/set-entry storage "key3" "value3" {})
      (is (= 2 (core/entry-count storage))))))

(deftest test-ttl-expiry
  (testing "Entry with TTL expires"
    (let [entry (core/make-context-entry "key" "value" {:ttl 100})]
      (is (not (core/is-expired? entry)))
      (Thread/sleep 150)
      (is (core/is-expired? entry)))))

(deftest test-size-estimation
  (testing "Estimate size of various types"
    (is (= 0 (core/estimate-size nil)))
    (is (pos? (core/estimate-size "hello")))
    (is (pos? (core/estimate-size {:a 1 :b 2})))
    (is (pos? (core/estimate-size [1 2 3 4 5])))))
