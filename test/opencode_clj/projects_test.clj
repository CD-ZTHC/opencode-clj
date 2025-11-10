(ns opencode-clj.projects-test
  (:require [clojure.test :refer :all]
            [opencode-clj.projects :as projects]
            [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]))

(deftest test-list-projects
  (testing "List projects function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/project" endpoint))
                                     (is (map? client))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (projects/list-projects client)))))

(deftest test-list-projects-with-params
  (testing "List projects with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/project" endpoint))
                                     (is (= {:limit 10} params))
                                     {:success true :data []})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (projects/list-projects client {:limit 10})))))

(deftest test-current-project
  (testing "Get current project function calls correct endpoint"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/project/current" endpoint))
                                     (is (map? client))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (projects/current-project client)))))

(deftest test-current-project-with-params
  (testing "Get current project with parameters"
    (with-redefs [http/get-request (fn [client endpoint params]
                                     (is (= "/project/current" endpoint))
                                     (is (= {:include-files true} params))
                                     {:success true :data {}})
                  utils/handle-response identity]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (projects/current-project client {:include-files true})))))

(deftest test-error-handling
  (testing "Error responses are properly handled"
    (with-redefs [http/get-request (fn [_ _ _]
                                     {:success false :error :server-error})
                  utils/handle-response (fn [response]
                                          (throw (ex-info "Server error" response)))]
      (let [client {:base-url "http://127.0.0.1:9711"}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (projects/list-projects client)))))))
