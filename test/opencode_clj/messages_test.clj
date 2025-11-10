(ns opencode-clj.messages-test
  (:require [clojure.test :refer :all]
            [opencode-clj.messages :as messages]
            [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]))

(deftest test-list-messages
  (testing "List messages function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session/123/message" endpoint))
                                     (is (map? client))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/list-messages client "123")))))

(deftest test-list-messages-with-params
  (testing "List messages with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session/123/message" endpoint))
                                     (is (= {:limit 10} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/list-messages client "123" {:limit 10})))))

(deftest test-get-message
  (testing "Get message function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session/123/message/msg-1" endpoint))
                                     (is (map? client))
                                     {:success true :data {:id "msg-1"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/get-message client "123" "msg-1")))))

(deftest test-get-message-with-params
  (testing "Get message with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/session/123/message/msg-1" endpoint))
                                     (is (= {:include-parts true} params))
                                     {:success true :data {:id "msg-1"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/get-message client "123" "msg-1" {:include-parts true})))))

(deftest test-send-prompt-string
  (testing "Send prompt with string message"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/message" endpoint))
                                      (is (= {:parts [{:type "text" :text "Hello world"}]} body))
                                      {:success true :data {:id "msg-1"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/send-prompt client "123" "Hello world")))))

(deftest test-send-prompt-text-map
  (testing "Send prompt with {:text ...} message"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/message" endpoint))
                                      (is (= {:parts [{:type "text" :text "Hello from map"}]} body))
                                      {:success true :data {:id "msg-1"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/send-prompt client "123" {:text "Hello from map"})))))

(deftest test-send-prompt-with-parts
  (testing "Send prompt with explicit parts"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/message" endpoint))
                                      (is (= {:parts [{:type "text" :text "Hello"}
                                                      {:type "code" :text "println('hi')"}]} body))
                                      {:success true :data {:id "msg-1"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/send-prompt client "123" {:parts [{:type "text" :text "Hello"}
                                                    {:type "code" :text "println('hi')"}]})))))

(deftest test-send-prompt-invalid-format
  (testing "Send prompt with invalid format throws exception"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/send-prompt client "123" 123)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/send-prompt client "123" {:invalid "format"}))))))

(deftest test-execute-command
  (testing "Execute command function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/command" endpoint))
                                      (is (= {:arguments ["arg1" "arg2"] :command "test-command"} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/execute-command client "123" {:arguments ["arg1" "arg2"] :command "test-command"})))))

(deftest test-execute-command-with-optional-params
  (testing "Execute command with all optional parameters"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/command" endpoint))
                                      (is (= {:arguments ["arg1"] :command "test-command"
                                              :agent "test-agent" :model "test-model" :messageID "msg-1"} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/execute-command client "123" {:arguments ["arg1"] :command "test-command"
                                                :agent "test-agent" :model "test-model" :message-id "msg-1"})))))

(deftest test-execute-command-validation
  (testing "Execute command validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/execute-command client "123" {:command "test-command"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/execute-command client "123" {:arguments ["arg1"]}))))))

(deftest test-run-shell-command
  (testing "Run shell command function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/shell" endpoint))
                                      (is (= {:agent "test-agent" :command "ls -la"} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/run-shell-command client "123" {:agent "test-agent" :command "ls -la"})))))

(deftest test-run-shell-command-validation
  (testing "Run shell command validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/run-shell-command client "123" {:agent "test-agent"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/run-shell-command client "123" {:command "ls -la"}))))))

(deftest test-revert-message
  (testing "Revert message function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/revert" endpoint))
                                      (is (= {:messageID "msg-1"} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/revert-message client "123" {:message-id "msg-1"})))))

(deftest test-revert-message-with-part-id
  (testing "Revert message with part ID"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/revert" endpoint))
                                      (is (= {:messageID "msg-1" :partID "part-1"} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/revert-message client "123" {:message-id "msg-1" :part-id "part-1"})))))

(deftest test-revert-message-validation
  (testing "Revert message validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/revert-message client "123" {}))))))

(deftest test-unrevert-messages
  (testing "Unrevert messages function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint params]
                                      (is (= "/session/123/unrevert" endpoint))
                                      (is (map? client))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/unrevert-messages client "123")))))

(deftest test-respond-to-permission
  (testing "Respond to permission function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/session/123/permissions/perm-1" endpoint))
                                      (is (= {:response "once"} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/respond-to-permission client "123" "perm-1" "once")))))

(deftest test-respond-to-permission-validation
  (testing "Respond to permission validates response value"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/respond-to-permission client "123" "perm-1" "invalid")))
      (is (thrown? clojure.lang.ExceptionInfo
                   (messages/respond-to-permission client "123" "perm-1" ""))))))

(deftest test-respond-to-permission-valid-responses
  (testing "Respond to permission accepts valid response values"
    (with-redefs [http/post-request (fn [_ _ _] {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (messages/respond-to-permission client "123" "perm-1" "once")
        (messages/respond-to-permission client "123" "perm-1" "always")
        (messages/respond-to-permission client "123" "perm-1" "reject")))))

(deftest test-error-handling
  (testing "Error responses are properly handled"
    (with-redefs [http/get-request (fn [_ _ _]
                                     {:success false :error :server-error})
                  utils/handle-response (fn [response]
                                          (throw (ex-info "Server error" response)))]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (messages/list-messages client "123")))))))
