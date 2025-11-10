(ns opencode-clj.utils-test
  (:require [clojure.test :refer :all]
            [opencode-clj.utils :as utils]))

(deftest test-handle-response
  (testing "Handle successful response"
    (let [response {:success true :data {:message "Success"}}]
      (is (= {:message "Success"} (utils/handle-response response)))))

  (testing "Handle error response with bad-request"
    (let [response {:success false :error :bad-request :data {:message "Invalid request"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Bad request"
                            (utils/handle-response response)))))

  (testing "Handle error response with not-found"
    (let [response {:success false :error :not-found :data {:message "Resource not found"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Resource not found"
                            (utils/handle-response response)))))

  (testing "Handle error response with server-error"
    (let [response {:success false :error :server-error :data {:message "Server error"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Server error"
                            (utils/handle-response response)))))

  (testing "Handle error response with unknown error"
    (let [response {:success false :error :unknown-error :data {:message "Unknown error"}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown error"
                            (utils/handle-response response))))))

(deftest test-success?
  (testing "Check success for successful response"
    (let [response {:success true :data {}}]
      (is (true? (utils/success? response)))))

  (testing "Check success for failed response"
    (let [response {:success false :error :bad-request}]
      (is (false? (utils/success? response)))))

  (testing "Check success for nil response"
    (is (nil? (utils/success? nil)))))

(deftest test-error?
  (testing "Check error for successful response"
    (let [response {:success true :data {}}]
      (is (false? (utils/error? response)))))

  (testing "Check error for failed response"
    (let [response {:success false :error :bad-request}]
      (is (true? (utils/error? response)))))

  (testing "Check error for nil response"
    (is (true? (utils/error? nil)))))

(deftest test-extract-error-message
  (testing "Extract error message from data.message"
    (let [response {:success false :error :bad-request :data {:message "Custom error message"}}]
      (is (= "Custom error message" (utils/extract-error-message response)))))

  (testing "Extract error message from data.errors"
    (let [response {:success false :error :bad-request :data {:errors ["Error 1" "Error 2"]}}]
      (is (= ["Error 1" "Error 2"] (utils/extract-error-message response)))))

  (testing "Extract error message from error key"
    (let [response {:success false :error :bad-request}]
      (is (= ":bad-request" (utils/extract-error-message response)))))

  (testing "Extract error message with no specific error info"
    (let [response {:success false}]
      (is (= "" (utils/extract-error-message response)))))

  (testing "Extract error message from nil response"
    (is (= "" (utils/extract-error-message nil)))))

(deftest test-with-default-params
  (testing "Merge user params with default params"
    (let [defaults {:timeout 5000 :retries 3}
          user-params {:timeout 10000 :host "localhost"}]
      (is (= {:timeout 10000 :retries 3 :host "localhost"}
             (utils/with-default-params defaults user-params)))))

  (testing "Merge with empty user params"
    (let [defaults {:timeout 5000 :retries 3}
          user-params {}]
      (is (= {:timeout 5000 :retries 3}
             (utils/with-default-params defaults user-params)))))

  (testing "Merge with nil user params"
    (let [defaults {:timeout 5000 :retries 3}]
      (is (= {:timeout 5000 :retries 3}
             (utils/with-default-params defaults nil)))))

  (testing "Merge with empty defaults"
    (let [defaults {}
          user-params {:timeout 5000}]
      (is (= {:timeout 5000}
             (utils/with-default-params defaults user-params))))))

(deftest test-validate-required
  (testing "Validate required parameters when all present"
    (let [params {:name "test" :age 25 :email "test@example.com"}
          required-keys [:name :age]]
      (is (= params (utils/validate-required params required-keys)))))

  (testing "Validate required parameters when missing some"
    (let [params {:name "test" :email "test@example.com"}
          required-keys [:name :age]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required parameters: :age"
                            (utils/validate-required params required-keys)))))

  (testing "Validate required parameters when all missing"
    (let [params {:email "test@example.com"}
          required-keys [:name :age]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required parameters: :name, :age"
                            (utils/validate-required params required-keys)))))

  (testing "Validate required parameters with empty params"
    (let [params {}
          required-keys [:name :age]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required parameters: :name, :age"
                            (utils/validate-required params required-keys)))))

  (testing "Validate required parameters with nil params"
    (let [required-keys [:name :age]]
      (is (thrown? java.lang.NullPointerException
                   (utils/validate-required nil required-keys)))))

  (testing "Validate required parameters with no required keys"
    (let [params {:name "test" :age 25}
          required-keys []]
      (is (= params (utils/validate-required params required-keys)))))

  (testing "Validate required parameters with nil required keys"
    (let [params {:name "test" :age 25}]
      (is (= params (utils/validate-required params nil))))))
