(ns opencode-clj.files-test
  (:require [clojure.test :refer :all]
            [opencode-clj.files :as files]
            [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]))

(deftest test-list-files
  (testing "List files function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/file" endpoint))
                                     (is (= {:path "/tmp"} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/list-files client {:path "/tmp"})))))

(deftest test-list-files-validation
  (testing "List files validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/list-files client {}))))))

(deftest test-read-file
  (testing "Read file function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/file/content" endpoint))
                                     (is (= {:path "/tmp/test.txt"} params))
                                     {:success true :data {:content "Hello world"}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/read-file client {:path "/tmp/test.txt"})))))

(deftest test-read-file-validation
  (testing "Read file validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/read-file client {}))))))

(deftest test-get-file-status
  (testing "Get file status function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/file/status" endpoint))
                                     (is (map? client))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/get-file-status client)))))

(deftest test-get-file-status-with-params
  (testing "Get file status with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/file/status" endpoint))
                                     (is (= {:path "/tmp"} params))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/get-file-status client {:path "/tmp"})))))

(deftest test-find-text
  (testing "Find text function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/find" endpoint))
                                     (is (= {:pattern "hello"} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/find-text client "hello")))))

(deftest test-find-text-with-params
  (testing "Find text with additional parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/find" endpoint))
                                     (is (= {:pattern "hello" :path "/tmp" :case-sensitive true} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/find-text client "hello" {:path "/tmp" :case-sensitive true})))))

(deftest test-find-text-validation
  (testing "Find text validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/find-text client nil))))))

(deftest test-find-files
  (testing "Find files function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/find/file" endpoint))
                                     (is (= {:query "*.txt"} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/find-files client "*.txt")))))

(deftest test-find-files-with-params
  (testing "Find files with additional parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/find/file" endpoint))
                                     (is (= {:query "*.txt" :path "/tmp" :limit 10} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/find-files client "*.txt" {:path "/tmp" :limit 10})))))

(deftest test-find-files-validation
  (testing "Find files validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/find-files client nil))))))

(deftest test-find-symbols
  (testing "Find symbols function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/find/symbol" endpoint))
                                     (is (= {:query "function"} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/find-symbols client "function")))))

(deftest test-find-symbols-with-params
  (testing "Find symbols with additional parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/find/symbol" endpoint))
                                     (is (= {:query "function" :kind "function" :limit 5} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (files/find-symbols client "function" {:kind "function" :limit 5})))))

(deftest test-find-symbols-validation
  (testing "Find symbols validates required parameters"
    (let [client {:base-url "http://127.0.0.1:9711"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (files/find-symbols client nil))))))

(deftest test-error-handling
  (testing "Error responses are properly handled"
    (with-redefs [http/get-request (fn [_ _ _]
                                     {:success false :error :server-error})
                  utils/handle-response (fn [response]
                                          (throw (ex-info "Server error" response)))]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (files/list-files client {:path "/tmp"})))))))
