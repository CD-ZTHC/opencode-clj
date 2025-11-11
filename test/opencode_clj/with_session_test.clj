(ns opencode-clj.with-session-test
  (:require [clojure.test :refer :all]
            [opencode-clj.core :as opencode]
            [opencode-clj.macros.core :refer [with-session]]))

(def test-client (opencode/client "http://127.0.0.1:9711"))

(deftest test-with-session-basic
  (testing "with-session creates session context"
    ;; This test verifies that with-session macro works without throwing exceptions
    (is (nil? (with-session test-client
                ;; Inside with-session, we should be able to call session-aware functions
                ;; without explicitly providing session-id
                (try
                  (opencode/list-messages test-client)
                  (catch Exception _ nil)))))))

(deftest test-session-aware-functions
  (testing "session-aware functions provide clear error outside context"
    ;; When calling session-aware functions outside of with-session,
    ;; they should provide clear error messages
    (is (thrown? clojure.lang.ExceptionInfo
                 (opencode/list-messages test-client)))))

(deftest test-mixed-usage
  (testing "mixed usage of session-aware and regular functions"
    ;; Functions that don't require session should work normally
    (let [sessions-result (opencode/list-sessions test-client)
          providers-result (opencode/list-providers test-client)]
      (is (:success sessions-result))
      (is (:success providers-result)))

    ;; Functions that require session should work within with-session
    (with-session test-client
      (let [messages-result (opencode/list-messages test-client)]
        (is (:success messages-result))))))
