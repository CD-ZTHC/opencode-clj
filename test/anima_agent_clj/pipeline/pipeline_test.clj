(ns anima-agent-clj.pipeline.pipeline-test
  "Tests for the pipeline module."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [anima-agent-clj.pipeline.core :as core]
            [anima-agent-clj.pipeline.source :as source]
            [anima-agent-clj.pipeline.transform :as transform]
            [anima-agent-clj.pipeline.sink :as sink]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Fixtures
;; ══════════════════════════════════════════════════════════════════════════════

(defn pipeline-fixture [f]
  (f))

(use-fixtures :each pipeline-fixture)

;; ══════════════════════════════════════════════════════════════════════════════
;; Pipeline Creation Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest create-pipeline-test
  (testing "Creating a pipeline"
    (let [pipeline (core/create-pipeline {})]
      (is (string? (:id pipeline)))
      (is (= :idle @(:status pipeline)))
      (is (nil? @(:source pipeline)))
      (is (empty? @(:transforms pipeline)))
      (is (empty? @(:filters pipeline)))
      (is (nil? @(:aggregate pipeline)))
      (is (nil? @(:sink pipeline))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Sink Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest channel-sink-test
  (testing "Creating and using a channel sink"
    (let [out-ch (async/chan 10)
          ch-sink (sink/channel-sink out-ch)]
      (is (= :open @(:status ch-sink)))

      ;; Write data
      (core/write ch-sink "test-data" nil)
      (core/write ch-sink "test-data-2" nil)

      ;; Check stats
      (is (= 2 (:items-written @(:stats ch-sink))))

      ;; Read from channel
      (is (= "test-data" (async/<!! out-ch)))
      (is (= "test-data-2" (async/<!! out-ch)))

      ;; Close sink
      (core/close-sink ch-sink)
      (is (= :closed @(:status ch-sink))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Collection Sink Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest collection-sink-test
  (testing "Creating and using a collection sink"
    (let [coll-sink (sink/collection-sink)]
      (is (= :open @(:status coll-sink)))

      ;; Write data
      (core/write coll-sink 1 nil)
      (core/write coll-sink 2 nil)
      (core/write coll-sink 3 nil)

      ;; Check collection
      (is (= [1 2 3] (sink/get-collection coll-sink)))

      ;; Clear collection
      (sink/clear-collection! coll-sink)
      (is (= [] (sink/get-collection coll-sink)))

      ;; Close sink
      (core/close-sink coll-sink)
      (is (= :closed @(:status coll-sink))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Null Sink Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest null-sink-test
  (testing "Null sink discards data"
    (let [null-sink (sink/null-sink)]
      (core/write null-sink "data" nil)
      (core/write null-sink "more-data" nil)
      (is (= 2 (:items-discarded @(:stats null-sink)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Map Transform Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest map-transform-test
  (testing "Map transform applies function"
    (let [inc-xform (transform/map-transform inc)]
      (is (= 2 (core/transform inc-xform 1 nil)))
      (is (= 11 (core/transform inc-xform 10 nil)))
      (is (= 2 (:items-transformed @(:stats inc-xform)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Filter Transform Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest filter-transform-test
  (testing "Filter transform keeps matching items"
    (let [pos-xform (transform/filter-transform pos?)]
      (is (true? (core/passes? pos-xform 1 nil)))
      (is (true? (core/passes? pos-xform 100 nil)))
      (is (false? (core/passes? pos-xform -1 nil)))
      (is (false? (core/passes? pos-xform 0 nil)))
      (is (= 2 (:items-passed @(:stats pos-xform))))
      (is (= 2 (:items-filtered @(:stats pos-xform)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Collection Source Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest collection-source-test
  (testing "Collection source emits items"
    (let [coll-src (source/collection-source [1 2 3 4 5])
          out-ch (core/start-source coll-src nil)]
      (is (= :running @(:status coll-src)))

      ;; Read items
      (is (= 1 (async/<!! out-ch)))
      (is (= 2 (async/<!! out-ch)))
      (is (= 3 (async/<!! out-ch)))
      (is (= 4 (async/<!! out-ch)))
      (is (= 5 (async/<!! out-ch)))

      ;; Channel should close
      (is (nil? (async/<!! out-ch)))
      (is (= :completed @(:status coll-src))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Callback Sink Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest callback-sink-test
  (testing "Callback sink calls function"
    (let [collected (atom [])
          cb-sink (sink/callback-sink #(swap! collected conj %))]
      (core/write cb-sink 1 nil)
      (core/write cb-sink 2 nil)
      (core/write cb-sink 3 nil)

      (is (= [1 2 3] @collected))
      (is (= 3 (:items-processed @(:stats cb-sink)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Multi Sink Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest multi-sink-test
  (testing "Multi sink writes to multiple sinks"
    (let [coll1 (sink/collection-sink)
          coll2 (sink/collection-sink)
          multi (sink/multi-sink [coll1 coll2])]
      (core/write multi "data" nil)
      (core/write multi "more" nil)

      (is (= ["data" "more"] (sink/get-collection coll1)))
      (is (= ["data" "more"] (sink/get-collection coll2)))

      (core/close-sink multi))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Utility Transforms Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest utility-transforms-test
  (testing "Identity transform"
    (let [id-xform (transform/identity-transform)]
      (is (= 42 (core/transform id-xform 42 nil)))))

  (testing "Tap transform"
    (let [tapped (atom nil)
          tap-xform (transform/tap-transform #(reset! tapped %))]
      (is (= 42 (core/transform tap-xform 42 nil)))
      (is (= 42 @tapped)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Batch Transform Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest batch-transform-test
  (testing "Batch transform groups items"
    (let [batch-xform (transform/batch-transform 3)]
      ;; First two items return nil (accumulating)
      (is (nil? (core/transform batch-xform 1 nil)))
      (is (nil? (core/transform batch-xform 2 nil)))
      ;; Third item triggers batch
      (is (= [1 2 3] (core/transform batch-xform 3 nil)))

      ;; Flush remaining
      (is (nil? (transform/flush-batch! batch-xform))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Compose Transforms Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest compose-transforms-test
  (testing "Composing multiple transforms"
    (let [composed (transform/compose-transforms
                    (transform/map-transform inc)
                    (transform/map-transform #(* % 2)))]
      (is (= 4 (core/transform composed 1 nil)))  ; (1+1)*2 = 4
      (is (= 6 (core/transform composed 2 nil)))))) ; (2+1)*2 = 6
