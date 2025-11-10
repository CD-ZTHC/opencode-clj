(ns opencode-clj.macros.core-test
  (:require [clojure.test :refer :all]
            [opencode-clj.macros.core :refer :all]
            [opencode-clj.core :as opencode]))

(def test-client (opencode/client "http://127.0.0.1:9711"))

(deftest test-defopencode-macro
  (testing "defopencode macro defines a client and requires namespaces"
    (defopencode test-client-real "http://127.0.0.1:9711")
    (is (some? test-client-real))
    (is (map? test-client-real))
    (is (= "http://127.0.0.1:9711" (:base-url test-client-real)))))

(deftest test-where-macro
  (testing "where macro builds query parameters"
    (let [params (where :name "test" :limit 10)]
      (is (= params {:name "test" :limit 10})))))

(deftest test-select-macro
  (testing "select macro creates field vector"
    (let [fields (select :id :name :status)]
      (is (= fields [:id :name :status])))))

(deftest test-success-error-macros
  (testing "->success and ->error macros handle responses"
    (let [success-response {:success true :data "result"}
          error-response {:success false :error "failed"}]
      (is (= (->success success-response :ok) :ok))
      (is (= (->success error-response :ok) error-response))
      (is (= (->error error-response :fail) :fail))
      (is (= (->error success-response :fail) success-response)))))

(deftest test-try-api-macro
  (testing "try-api macro handles exceptions"
    (let [result (try
                   (try-api
                    (throw (ex-info "Test error" {:error :not-found})))
                   (catch Exception e
                     e))]
      (is (instance? clojure.lang.ExceptionInfo result))
      (is (= "Not found" (.getMessage result))))))

(deftest test-batch-macro
  (testing "batch macro executes operations"
    (let [ops [(fn [c] {:op1 c})
               (fn [c] {:op2 c})]
          results (batch test-client (fn [c] {:op1 c}) (fn [c] {:op2 c}))]
      (is (= results [{:op1 test-client} {:op2 test-client}])))))

(deftest test-parallel-macro
  (testing "parallel macro executes operations in parallel"
    (let [results (parallel test-client (fn [c] {:op1 c}) (fn [c] {:op2 c}))]
      (is (= results [{:op1 test-client} {:op2 test-client}])))))

(deftest test-with-session-macro
  (testing "with-session macro creates session context"
    (let [session-id "test-session-123"
          result (with-session session-id test-client
                   (str "Session: " session-id " Client: " (:base-url client)))]
      (is (string? result))
      (is (.contains result "Session: test-session-123"))
      (is (.contains result "Client: http://127.0.0.1:9711")))))

(deftest test-real-api-operations
  (testing "Macros work with real API operations"
    ;; Create a session using defopencode client
    (defopencode real-client "http://127.0.0.1:9711")

    (let [session (opencode/create-session real-client {:title "Macro Test Session"})]
      (testing "Session creation should succeed"
        (is (map? session))
        (is (contains? session :id))
        (is (contains? session :title)))

      (testing "where macro for query parameters"
        (let [query (where :session-id (:id session) :limit 5)]
          (is (= query {:session-id (:id session) :limit 5}))))

      (testing "select macro for field selection"
        (let [fields (select :id :title :created-at)]
          (is (= fields [:id :title :created-at])))))))
