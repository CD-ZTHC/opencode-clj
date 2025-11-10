(ns opencode-clj.client-test
  (:require [clojure.test :refer :all]
            [opencode-clj.client :as client]
            [clj-http.client :as http]))

(deftest test-default-opts
  (testing "Default HTTP options are set correctly"
    (let [opts (client/default-opts)]
      (is (false? (:throw-exceptions opts)))
      (is (= :json (:as opts)))
      (is (= :always (:coerce opts)))
      (is (= :json (:content-type opts)))
      (is (= :json (:accept opts))))))

(deftest test-build-url
  (testing "Build URL with base URL and endpoint"
    (is (= "http://localhost:9711/api" (client/build-url "http://localhost:9711" "/api")))
    (is (= "http://localhost:9711/api" (client/build-url "http://localhost:9711/" "/api")))
    (is (= "http://localhost:9711/api" (client/build-url "http://localhost:9711" "api")))
    (is (= "http://localhost:9711/api" (client/build-url "http://localhost:9711/" "api")))))

(deftest test-add-query-params
  (testing "Add query parameters to request options"
    (let [opts {:timeout 5000}
          params {:limit 10 :offset 0}]
      (is (= {:timeout 5000 :query-params {:limit 10 :offset 0}}
             (client/add-query-params opts params))))

    (testing "Empty params don't add query-params key"
      (let [opts {:timeout 5000}]
        (is (= {:timeout 5000} (client/add-query-params opts {})))
        (is (= {:timeout 5000} (client/add-query-params opts nil)))))))

(deftest test-parse-response
  (testing "Parse successful responses"
    (let [response-200 {:status 200 :body {:data "test"}}
          response-201 {:status 201 :body {:id "123"}}]
      (is (= {:success true :data {:data "test"}} (client/parse-response response-200)))
      (is (= {:success true :data {:id "123"}} (client/parse-response response-201)))))

  (testing "Parse error responses"
    (let [response-400 {:status 400 :body {:error "Bad request"}}
          response-404 {:status 404 :body {:error "Not found"}}
          response-500 {:status 500 :body {:error "Server error"}}
          response-999 {:status 999 :body {:error "Unknown"}}]
      (is (= {:success false :error :bad-request :data {:error "Bad request"}}
             (client/parse-response response-400)))
      (is (= {:success false :error :not-found :data {:error "Not found"}}
             (client/parse-response response-404)))
      (is (= {:success false :error :server-error :data {:error "Server error"}}
             (client/parse-response response-500)))
      (is (= {:success false :error :unknown :status 999 :data {:error "Unknown"}}
             (client/parse-response response-999))))))

(deftest test-get-request
  (testing "GET request with client configuration"
    (with-redefs [http/get (fn [url opts]
                             (is (= "http://127.0.0.1:9711/api" url))
                             (is (= :json (:as opts)))
                             (is (= {:limit 10} (:query-params opts)))
                             {:status 200 :body {:data "test"}})]
      (let [client {:base-url "http://127.0.0.1:9711" :directory "/tmp" :http-opts {}}]
        (client/get-request client "/api" {:limit 10})))))

(deftest test-post-request
  (testing "POST request with client configuration"
    (with-redefs [http/post (fn [url opts]
                              (is (= "http://127.0.0.1:9711/api" url))
                              (is (= {:data "test"} (:form-params opts)))
                              (is (= :json (:as opts)))
                              {:status 201 :body {:id "123"}})]
      (let [client {:base-url "http://127.0.0.1:9711" :directory "/tmp" :http-opts {}}]
        (client/post-request client "/api" {:data "test"})))))

(deftest test-patch-request
  (testing "PATCH request with client configuration"
    (with-redefs [http/patch (fn [url opts]
                               (is (= "http://127.0.0.1:9711/api" url))
                               (is (= {:title "Updated"} (:form-params opts)))
                               (is (= :json (:as opts)))
                               {:status 200 :body {:id "123"}})]
      (let [client {:base-url "http://127.0.0.1:9711" :directory "/tmp" :http-opts {}}]
        (client/patch-request client "/api" {:title "Updated"})))))

(deftest test-put-request
  (testing "PUT request with client configuration"
    (with-redefs [http/put (fn [url opts]
                             (is (= "http://127.0.0.1:9711/api" url))
                             (is (= {:data "replace"} (:form-params opts)))
                             (is (= :json (:as opts)))
                             {:status 200 :body {:id "123"}})]
      (let [client {:base-url "http://127.0.0.1:9711" :directory "/tmp" :http-opts {}}]
        (client/put-request client "/api" {:data "replace"})))))

(deftest test-delete-request
  (testing "DELETE request with client configuration"
    (with-redefs [http/delete (fn [url opts]
                                (is (= "http://127.0.0.1:9711/api" url))
                                (is (= :json (:as opts)))
                                (is (= {:force true} (:query-params opts)))
                                {:status 200 :body {}})]
      (let [client {:base-url "http://127.0.0.1:9711" :directory "/tmp" :http-opts {}}]
        (client/delete-request client "/api" {:force true})))))

(deftest test-client-with-custom-http-opts
  (testing "Client with custom HTTP options"
    (with-redefs [http/get (fn [url opts]
                             (is (= "http://127.0.0.1:9711/api" url))
                             (is (= 10000 (:timeout opts)))
                             (is (= "Bearer token" (get-in opts [:headers "Authorization"])))
                             {:status 200 :body {:data "test"}})]
      (let [client {:base-url "http://127.0.0.1:9711"
                    :http-opts {:timeout 10000
                                :headers {"Authorization" "Bearer token"}}}]
        (client/get-request client "/api")))))

(deftest test-client-with-directory
  (testing "Client with directory parameter"
    (with-redefs [http/get (fn [url opts]
                             (is (= "http://127.0.0.1:9711/api" url))
                             (is (= "/project" (:directory (:query-params opts))))
                             {:status 200 :body {:data "test"}})]
      (let [client {:base-url "http://127.0.0.1:9711" :directory "/project" :http-opts {}}]
        (client/get-request client "/api" {:directory "/project"})))))

(deftest test-merge-directory-with-params
  (testing "Directory parameter is merged with user params"
    (with-redefs [http/get (fn [url opts]
                             (is (= {:directory "/project" :limit 10} (:query-params opts)))
                             {:status 200 :body {:data "test"}})]
      (let [client {:base-url "http://127.0.0.1:9711" :directory "/project" :http-opts {}}]
        (client/get-request client "/api" {:directory "/project" :limit 10})))))
