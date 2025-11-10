(ns opencode-clj.config-test
  (:require [clojure.test :refer :all]
            [opencode-clj.config :as config]
            [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]))

(deftest test-get-config
  (testing "Get config function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/config" endpoint))
                                     (is (map? client))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/get-config client)))))

(deftest test-get-config-with-params
  (testing "Get config with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/config" endpoint))
                                     (is (= {:include-secrets true} params))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/get-config client {:include-secrets true})))))

(deftest test-update-config
  (testing "Update config function calls correct endpoint"
    (with-redefs [http/patch-request (fn [client endpoint body]
                                       (is (= "/config" endpoint))
                                       (is (= {:api-key "new-key"} body))
                                       {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/update-config client {:api-key "new-key"})))))

(deftest test-list-providers
  (testing "List providers function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/config/providers" endpoint))
                                     (is (map? client))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/list-providers client)))))

(deftest test-list-providers-with-params
  (testing "List providers with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/config/providers" endpoint))
                                     (is (= {:include-models true} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/list-providers client {:include-models true})))))

(deftest test-list-commands
  (testing "List commands function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/command" endpoint))
                                     (is (map? client))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/list-commands client)))))

(deftest test-list-commands-with-params
  (testing "List commands with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/command" endpoint))
                                     (is (= {:category "file"} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/list-commands client {:category "file"})))))

(deftest test-list-agents
  (testing "List agents function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/agent" endpoint))
                                     (is (map? client))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/list-agents client)))))

(deftest test-list-agents-with-params
  (testing "List agents with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/agent" endpoint))
                                     (is (= {:enabled-only true} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/list-agents client {:enabled-only true})))))

(deftest test-get-tool-ids
  (testing "Get tool IDs function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/experimental/tool/ids" endpoint))
                                     (is (map? client))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/get-tool-ids client)))))

(deftest test-get-tool-ids-with-params
  (testing "Get tool IDs with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/experimental/tool/ids" endpoint))
                                     (is (= {:include-builtin false} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/get-tool-ids client {:include-builtin false})))))

(deftest test-list-tools
  (testing "List tools function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/experimental/tool" endpoint))
                                     (is (= {:provider "openai" :model "gpt-4"} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/list-tools client {:provider "openai" :model "gpt-4"})))))

(deftest test-list-tools-validation
  (testing "List tools validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/list-tools client {:provider "openai"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/list-tools client {:model "gpt-4"}))))))

(deftest test-get-path
  (testing "Get path function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/path" endpoint))
                                     (is (map? client))
                                     {:success true :data {:path "/tmp"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/get-path client)))))

(deftest test-get-path-with-params
  (testing "Get path with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/path" endpoint))
                                     (is (= {:absolute true} params))
                                     {:success true :data {:path "/tmp"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/get-path client {:absolute true})))))

(deftest test-write-log
  (testing "Write log function calls correct endpoint"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/log" endpoint))
                                      (is (= {:service "test-service" :level "info" :message "test message"} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/write-log client {:service "test-service" :level "info" :message "test message"})))))

(deftest test-write-log-with-extra
  (testing "Write log with extra data"
    (with-redefs [http/post-request (fn [client endpoint body]
                                      (is (= "/log" endpoint))
                                      (is (= {:service "test-service" :level "info" :message "test message" :extra {:user "test"}} body))
                                      {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/write-log client {:service "test-service" :level "info" :message "test message" :extra {:user "test"}})))))

(deftest test-write-log-validation
  (testing "Write log validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/write-log client {:service "test-service" :level "info"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/write-log client {:level "info" :message "test message"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/write-log client {:service "test-service" :message "test message"}))))))

(deftest test-write-log-level-validation
  (testing "Write log validates level parameter"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/write-log client {:service "test-service" :level "invalid" :message "test message"}))))))

(deftest test-write-log-valid-levels
  (testing "Write log accepts valid level values"
    (with-redefs [http/post-request (fn [_ _ _] {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/write-log client {:service "test-service" :level "debug" :message "test message"})
        (config/write-log client {:service "test-service" :level "info" :message "test message"})
        (config/write-log client {:service "test-service" :level "warn" :message "test message"})
        (config/write-log client {:service "test-service" :level "error" :message "test message"})))))

(deftest test-get-mcp-status
  (testing "Get MCP status function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/mcp" endpoint))
                                     (is (map? client))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/get-mcp-status client)))))

(deftest test-get-lsp-status
  (testing "Get LSP status function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/lsp" endpoint))
                                     (is (map? client))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/get-lsp-status client)))))

(deftest test-get-formatter-status
  (testing "Get formatter status function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/formatter" endpoint))
                                     (is (map? client))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/get-formatter-status client)))))

(deftest test-set-auth
  (testing "Set auth function calls correct endpoint"
    (with-redefs [http/put-request (fn [client endpoint body]
                                     (is (= "/auth/test-auth" endpoint))
                                     (is (= {:api-key "secret-key"} body))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (config/set-auth client "test-auth" {:api-key "secret-key"})))))

(deftest test-error-handling
  (testing "Error responses are properly handled"
    (with-redefs [http/get-request (fn [_ _ _]
                                     {:success false :error :server-error})
                  utils/handle-response (fn [response]
                                          (throw (ex-info "Server error" response)))]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (config/get-config client)))))))
