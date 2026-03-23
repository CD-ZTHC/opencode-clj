(ns anima-agent-clj.test-utils
  "Test utilities for anima-agent-clj tests.

   Provides helpers for testing against a real opencode-server
   running at http://127.0.0.1:9711

   Usage:
   (require '[anima-agent-clj.test-utils :as tu])

   (deftest my-test
     (tu/when-server-available
       (let [client (tu/test-client)]
         ...)))"
  (:require [anima-agent-clj.client :as client]
            [clj-http.client :as http]
            [clojure.test :refer [is]]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Configuration
;; ══════════════════════════════════════════════════════════════════════════════

(def ^:dynamic *test-url*
  "Base URL for the test server"
  "http://127.0.0.1:9711")

(def ^:dynamic *test-timeout*
  "Timeout in ms for server availability check"
  5000)

;; ══════════════════════════════════════════════════════════════════════════════
;; Server Availability
;; ══════════════════════════════════════════════════════════════════════════════

(defn server-available?
  "Check if the opencode-server is available.
   Returns true if server responds to a health check, false otherwise."
  ([]
   (server-available? *test-url*))
  ([url]
   (try
     (let [response (http/get (str url "/")
                              {:throw-exceptions false
                               :socket-timeout *test-timeout*
                               :connection-timeout *test-timeout*})]
       (<= 200 (:status response 0) 299))
     (catch Exception _
       false))))

(defmacro when-server-available
  "Execute body only if the test server is available.
   Skips the test gracefully if server is not running."
  [& body]
  `(if (server-available?)
     (do ~@body)
     (do
       (println "Skipping test - server not available at" *test-url*)
       (is true "Server not available - test skipped"))))

(defmacro deftest-with-server
  "Define a test that requires a running server.
   Uses clojure.test/deftest internally but wraps in when-server-available."
  [name & body]
  `(clojure.test/deftest ~name
     (when-server-available
       ~@body)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Client
;; ══════════════════════════════════════════════════════════════════════════════

(defn test-client
  "Create a test client configured for the test server."
  ([]
   (test-client *test-url*))
  ([url]
   {:base-url url
    :directory nil
    :http-opts {:socket-timeout 30000
                :connection-timeout 10000}}))

(defn test-client-with-dir
  "Create a test client with a specific directory."
  [dir]
  (assoc (test-client) :directory dir))

;; ══════════════════════════════════════════════════════════════════════════════
;; Test Helpers
;; ══════════════════════════════════════════════════════════════════════════════

(defn random-session-id
  "Generate a random session ID for testing."
  []
  (str "test-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defmacro with-clean-session
  "Execute body with a new session, cleaning up afterward.
   Binds session-id to the created session's ID."
  [client sym & body]
  `(let [~sym (let [resp# (anima-agent-clj.sessions/create-session ~client)]
                (when-let [id# (:id resp#)]
                  id#))]
     (try
       ~@body
       (finally
         (when ~sym
           (try
             (anima-agent-clj.sessions/delete-session ~client ~sym)
             (catch Exception _#)))))))

(defmacro with-session
  "Create a session for testing, automatically clean up after."
  [client-sym session-sym & body]
  `(let [~session-sym (anima-agent-clj.sessions/create-session ~client-sym)]
     (try
       (when-let [id# (:id ~session-sym)]
         (binding [*session-id* id#]
           ~@body))
       (finally
         (when-let [id# (:id ~session-sym)]
           (try
             (anima-agent-clj.sessions/delete-session ~client-sym id#)
             (catch Exception _#)))))))

(def ^:dynamic *session-id* nil)

;; ══════════════════════════════════════════════════════════════════════════════
;; Assertion Helpers
;; ══════════════════════════════════════════════════════════════════════════════

(defn assert-success
  "Assert that a response indicates success."
  [response]
  (is (map? response) "Response should be a map")
  (is (contains? response :id) "Response should contain :id")
  response)

(defn assert-error
  "Assert that a response indicates an error."
  [response]
  (is (map? response) "Response should be a map")
  (is (false? (:success response true)) "Response should indicate failure")
  response)
