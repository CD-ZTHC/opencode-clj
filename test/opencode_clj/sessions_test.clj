(ns opencode-clj.sessions-test
  (:require [clojure.test :refer :all]
            [opencode-clj.sessions :as sessions]
            [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]))

(deftest test-list-sessions
  (testing "List sessions function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session" endpoint))
                                     (is (map? client))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/list-sessions client)))))

(deftest test-list-sessions-with-params
  (testing "List sessions with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session" endpoint))
                                     (is (= {:limit 10} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/list-sessions client {:limit 10})))))

(deftest test-create-session
  (testing "Create session function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint options]
                                      (is (= "/session" endpoint))
                                      (is (= {:title "Test Session"} options))
                                      {:success true :data {:id "123"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/create-session client {:title "Test Session"})))))

(deftest test-create-session-no-options
  (testing "Create session without options"
    (with-redefs [http/post-request (fn [client endpoint options]
                                      (is (= "/session" endpoint))
                                      (is (empty? options))
                                      {:success true :data {:id "123"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/create-session client)))))

(deftest test-get-session
  (testing "Get session function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session/123" endpoint))
                                     (is (map? client))
                                     {:success true :data {:id "123"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/get-session client "123")))))

(deftest test-get-session-with-params
  (testing "Get session with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session/123" endpoint))
                                     (is (= {:include-messages true} params))
                                     {:success true :data {:id "123"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/get-session client "123" {:include-messages true})))))

(deftest test-update-session
  (testing "Update session function calls correct endpoint"
    (with-redefs [http/patch-request (fn [client endpoint updates]
                                       (is (= "/session/123" endpoint))
                                       (is (= {:title "Updated Title"} updates))
                                       {:success true :data {:id "123"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/update-session client "123" {:title "Updated Title"})))))

(deftest test-delete-session
  (testing "Delete session function calls correct endpoint"
    (with-redefs [http/delete-request (fn [client endpoint params]
                                        (is (= "/session/123" endpoint))
                                        (is (map? client))
                                        {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/delete-session client "123")))))

(deftest test-get-session-children
  (testing "Get session children function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session/123/children" endpoint))
                                     (is (map? client))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/get-session-children client "123")))))

(deftest test-get-session-todo
  (testing "Get session todo function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session/123/todo" endpoint))
                                     (is (map? client))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/get-session-todo client "123")))))

(deftest test-init-session
  (testing "Init session function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/init" endpoint))
                                      (is (= {:modelID "model-1" :providerID "provider-1" :messageID "msg-1"} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/init-session client "123" {:modelID "model-1" :providerID "provider-1" :messageID "msg-1"})))))

(deftest test-init-session-validation
  (testing "Init session validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sessions/init-session client "123" {:modelID "model-1"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (sessions/init-session client "123" {:providerID "provider-1"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (sessions/init-session client "123" {:messageID "msg-1"}))))))

(deftest test-fork-session
  (testing "Fork session function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/fork" endpoint))
                                      (is (= {:messageID "msg-1"} body))
                                      {:success true :data {:id "456"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/fork-session client "123" "msg-1")))))

(deftest test-fork-session-no-message-id
  (testing "Fork session without message ID"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/fork" endpoint))
                                      (is (empty? body))
                                      {:success true :data {:id "456"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/fork-session client "123")))))

(deftest test-abort-session
  (testing "Abort session function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint params]
                                      (is (= "/session/123/abort" endpoint))
                                      (is (map? client))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/abort-session client "123")))))

(deftest test-share-session
  (testing "Share session function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint params]
                                      (is (= "/session/123/share" endpoint))
                                      (is (map? client))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/share-session client "123")))))

(deftest test-unshare-session
  (testing "Unshare session function calls correct endpoint"
    (with-redefs [http/delete-request (fn [client endpoint params]
                                        (is (= "/session/123/share" endpoint))
                                        (is (map? client))
                                        {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/unshare-session client "123")))))

(deftest test-get-session-diff
  (testing "Get session diff function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session/123/diff" endpoint))
                                     (is (map? client))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/get-session-diff client "123")))))

(deftest test-summarize-session
  (testing "Summarize session function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/summarize" endpoint))
                                      (is (= {:providerID "provider-1" :modelID "model-1"} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (sessions/summarize-session client "123" {:providerID "provider-1" :modelID "model-1"})))))

(deftest test-summarize-session-validation
  (testing "Summarize session validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sessions/summarize-session client "123" {:providerID "provider-1"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (sessions/summarize-session client "123" {:modelID "model-1"}))))))

(deftest test-error-handling
  (testing "Error responses are properly handled"
    (with-redefs [http/get-request (fn [_ _ _]
                                     {:success false :error :server-error})
                  utils/handle-response (fn [response]
                                          (throw (ex-info "Server error" response)))]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (sessions/list-sessions client)))))))
