(ns anima-agent-clj.cache.cache-test
  "Tests for the Result Cache System."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [anima-agent-clj.cache.core :as core]
            [anima-agent-clj.cache.lru :as lru]
            [anima-agent-clj.cache.ttl :as ttl]))

;; ══════════════════════════════════════════════════════════════════════════════
;; LRU Cache Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-lru-basic-operations
  (let [cache (lru/create-lru-cache {:max-entries 100})]
    (testing "Set and get"
      (lru/cache-set! cache "key1" "value1")
      (is (= "value1" (lru/cache-get cache "key1"))))

    (testing "Get non-existent key"
      (is (nil? (lru/cache-get cache "non-existent"))))

    (testing "Delete"
      (lru/cache-set! cache "key2" "value2")
      (is (true? (lru/cache-delete! cache "key2")))
      (is (nil? (lru/cache-get cache "key2"))))

    (testing "Has key"
      (lru/cache-set! cache "key3" "value3")
      (is (true? (core/has-key? cache "key3")))
      (is (false? (core/has-key? cache "no-key"))))))

(deftest test-lru-eviction
  (let [cache (lru/create-lru-cache {:max-entries 3})]
    (testing "Evicts least recently used"
      (lru/cache-set! cache "a" 1)
      (lru/cache-set! cache "b" 2)
      (lru/cache-set! cache "c" 3)
      ;; Access 'a' to make it recently used
      (lru/cache-get cache "a")
      ;; Add new entry, should evict 'b' (LRU)
      (lru/cache-set! cache "d" 4)
      (is (= 1 (lru/cache-get cache "a")))  ; Still exists
      (is (nil? (lru/cache-get cache "b")))  ; Evicted
      (is (= 3 (lru/cache-get cache "c")))
      (is (= 4 (lru/cache-get cache "d"))))

    (testing "Stats include evictions"
      (let [stats (core/stats cache)]
        (is (pos? (:evictions stats)))))))

(deftest test-lru-get-or-compute
  (let [cache (lru/create-lru-cache {:max-entries 100})
        compute-count (atom 0)]
    (testing "Computes on cache miss"
      (let [result (lru/cache-get-or-compute
                    cache "compute-key"
                    (fn []
                      (swap! compute-count inc)
                      "computed-value"))]
        (is (= "computed-value" result))
        (is (= 1 @compute-count))))

    (testing "Uses cache on hit"
      (let [result (lru/cache-get-or-compute
                    cache "compute-key"
                    (fn []
                      (swap! compute-count inc)
                      "should-not-be-called"))]
        (is (= "computed-value" result))
        (is (= 1 @compute-count)))) ; compute-count unchanged

    (testing "Stats show hits and misses"
      (let [stats (core/stats cache)]
        (is (pos? (:hits stats)))))))

(deftest test-lru-update
  (let [cache (lru/create-lru-cache {:max-entries 100})]
    (testing "Update existing key"
      (lru/cache-set! cache "update-key" "original")
      (lru/cache-set! cache "update-key" "updated")
      (is (= "updated" (lru/cache-get cache "update-key"))))

    (testing "Size reflects current entries"
      (lru/cache-set! cache "k1" "v1")
      (lru/cache-set! cache "k2" "v2")
      (is (= 3 (core/entry-count cache)))))) ; update-key, k1, k2

;; ══════════════════════════════════════════════════════════════════════════════
;; TTL Cache Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-ttl-basic-operations
  (let [cache (ttl/create-ttl-cache {:default-ttl 60000 :enable-cleanup false})]
    (testing "Set and get"
      (ttl/cache-set! cache "key1" "value1")
      (is (= "value1" (ttl/cache-get cache "key1"))))

    (testing "Delete"
      (ttl/cache-set! cache "key2" "value2")
      (is (true? (ttl/cache-delete! cache "key2")))
      (is (nil? (ttl/cache-get cache "key2"))))

    (testing "Clear"
      (ttl/cache-set! cache "k1" "v1")
      (ttl/cache-set! cache "k2" "v2")
      (core/clear-all cache)
      (is (zero? (core/entry-count cache))))))

(deftest test-ttl-expiry
  (let [cache (ttl/create-ttl-cache {:default-ttl 100 :enable-cleanup false})]
    (testing "Entry expires after TTL"
      (ttl/cache-set! cache "expire-key" "value")
      (is (= "value" (ttl/cache-get cache "expire-key")))
      (Thread/sleep 150)
      (is (nil? (ttl/cache-get cache "expire-key"))))

    (testing "Custom TTL override"
      (ttl/cache-set! cache "custom-ttl" "value" {:ttl 50})
      (Thread/sleep 100)
      (is (nil? (ttl/cache-get cache "custom-ttl"))))))

(deftest test-ttl-get-or-compute
  (let [cache (ttl/create-ttl-cache {:default-ttl 60000 :enable-cleanup false})]
    (testing "Compute on miss"
      (let [result (ttl/cache-get-or-compute
                    cache "compute-key"
                    (fn [] {:computed true}))]
        (is (= {:computed true} result))))

    (testing "Cache hit on second call"
      (ttl/cache-get cache "compute-key") ; Ensure it's cached
      (let [stats (core/stats cache)]
        (is (pos? (:hits stats)))))))

(deftest test-ttl-remaining
  (let [cache (ttl/create-ttl-cache {:default-ttl 1000 :enable-cleanup false})]
    (testing "Get remaining TTL"
      (ttl/cache-set! cache "ttl-key" "value")
      (let [remaining (ttl/cache-remaining-ttl cache "ttl-key")]
        (is (some? remaining))
        (is (pos? remaining))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Core Helper Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest test-cache-key-generation
  (testing "API cache key"
    (let [key1 (core/make-api-cache-key "session-1" "hello")
          key2 (core/make-api-cache-key "session-1" "hello")
          key3 (core/make-api-cache-key "session-1" "world")]
      (is (= key1 key2))
      (is (not= key1 key3))))

  (testing "Task cache key"
    (let [key1 (core/make-task-cache-key :api-call {:content "test"})
          key2 (core/make-task-cache-key :api-call {:content "test"})]
      (is (= key1 key2))))

  (testing "Session cache key"
    (is (= "session:sess-1:history"
           (core/make-session-cache-key "sess-1" "history")))))

(deftest test-hit-rate
  (testing "Calculate hit rate"
    (is (= 0.0 (core/hit-rate {:hits 0 :misses 0})))
    (is (= 0.5 (core/hit-rate {:hits 5 :misses 5})))
    (is (= 0.75 (core/hit-rate {:hits 75 :misses 25}))))

  (testing "Zero total"
    (is (= 0.0 (core/hit-rate {:hits 0 :misses 0})))))

(deftest test-size-estimation
  (testing "Estimate size"
    (is (= 0 (core/estimate-size nil)))
    (is (= 5 (core/estimate-size "hello")))
    (is (pos? (core/estimate-size {:a 1 :b 2})))))
