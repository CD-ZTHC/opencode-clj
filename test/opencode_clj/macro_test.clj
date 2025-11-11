(ns opencode-clj.macro-test
  (:require [clojure.test :refer :all]
            [opencode-clj.macros.core :refer [def-session-fn]]
            [clojure.walk :refer [macroexpand-all]]))

(defmacro expand-def-session-fn
  "Helper macro to expand def-session-fn for testing"
  [& args]
  `(macroexpand-all '(def-session-fn ~@args)))

(deftest test-macro-expansion-print
  (testing "Print macro expansion for inspection"
    (let [expanded (expand-def-session-fn
                    test-fn
                    "Test function"
                    [client session-id & [params]]
                    (println client session-id params))]
      (println "=== MACRO EXPANSION ===")
      (prn expanded)
      (println "=======================")
      (is (seq? expanded)))))

(deftest test-macro-expansion-fixed-args
  (testing "Print macro expansion for fixed args function"
    (let [expanded (expand-def-session-fn
                    test-fn-fixed
                    "Test function with fixed args"
                    [client session-id updates]
                    (println client session-id updates))]
      (println "=== FIXED ARGS EXPANSION ===")
      (prn expanded)
      (println "============================")
      (is (seq? expanded)))))

(comment
  ;; Manual testing examples
  (expand-def-session-fn
   list-messages
   "List messages for a session"
   [client session-id & [params]]
   (-> (http/get-request client (str "/session/" session-id "/message") params)
       utils/handle-response))

  (expand-def-session-fn
   update-session
   "Update session properties"
   [client session-id updates]
   (-> (http/patch-request client (str "/session/" session-id) updates)
       utils/handle-response)))
