(ns opencode-clj.macros.async-test
  (:require [clojure.test :refer :all]
            [opencode-clj.macros.async :refer :all]))

(deftest test-async-operation-macro
  (testing "async-operation macro wraps operation in future"
    (let [future-result (async-operation (+ 1 2 3))
          result (deref future-result)]
      (is (= result 6)))))

(deftest test-parallel-operations-macro
  (testing "parallel-operations macro executes operations in parallel"
    (let [client {:url "http://127.0.0.1:9711"}
          results (parallel-operations client
                                       (fn [c] {:op1 c})
                                       (fn [c] {:op2 c}))]
      (is (= results [{:op1 client} {:op2 client}])))))

(deftest test-cache-async-macro
  (testing "cache-async macro creates cache with operations"
    (let [cache (cache-async)]
      (is (fn? (:get cache)))
      (is (fn? (:put cache)))
      (is (fn? (:clear cache)))

      (testing "cache put and get operations"
        ((:put cache) :key1 "value1")
        (is (= "value1" ((:get cache) :key1)))

        ((:put cache) :key2 "value2")
        (is (= "value2" ((:get cache) :key2)))

        (testing "cache clear operation"
          ((:clear cache))
          (is (nil? ((:get cache) :key1)))
          (is (nil? ((:get cache) :key2))))))))

(deftest test-async-error-handling
  (testing "async-operation handles exceptions gracefully"
    (let [future-result (async-operation
                         (throw (Exception. "Test error")))
          result (deref future-result)]
      (is (contains? result :error))
      (is (instance? Exception (:error result))))))
