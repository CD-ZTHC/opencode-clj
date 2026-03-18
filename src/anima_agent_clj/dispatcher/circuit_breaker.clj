(ns anima-agent-clj.dispatcher.circuit-breaker
  "Circuit Breaker implementation for fault tolerance.

   The Circuit Breaker pattern prevents cascading failures in distributed systems
   by detecting failures and stopping requests to failing services.

   State Machine:
   CLOSED -> (failures exceed threshold) -> OPEN
   OPEN -> (timeout elapsed) -> HALF_OPEN
   HALF_OPEN -> (test succeeds) -> CLOSED
   HALF_OPEN -> (test fails) -> OPEN

   Usage:
   (def cb (create-circuit-breaker {:failure-threshold 5 :timeout-ms 60000}))
   (with-circuit-breaker cb some-risky-operation)
   (circuit-breaker-status cb)

   Reference: https://martinfowler.com/bria/circuitBreaker/"
  (:refer-clojure :exclude [reset!])
  (:require [clojure.core.async :as async]
            [anima-agent-clj.metrics :as metrics])
  (:import [java.util Date]
           [java.util.concurrent.atomic AtomicLong]
           [java.util.concurrent.locks ReentrantLock]))

;; Forward declaration
(declare valid-transition?)

;; ══════════════════════════════════════════════════════════════════════════════
;; Circuit Breaker States
;; ══════════════════════════════════════════════════════════════════════════════

(def states
  {:closed    "Circuit is closed, requests flow through"
   :open      "Circuit is open, requests are rejected"
   :half-open "Circuit is testing if service recovered"})

;; ══════════════════════════════════════════════════════════════════════════════
;; Circuit Breaker Record
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord CircuitBreaker
  [id              ; String - Unique identifier
   state           ; atom<keyword> - :closed, :open, :half-open
   failure-count   ; atom<int> - Current failure count
   success-count   ; atom<int> - Current success count (in half-open)
   config          ; Map - Configuration
   stats           ; atom<map> - Statistics
   metrics         ; MetricsCollector - Optional metrics
   lock]           ; ReentrantLock - For thread-safe state transitions

  ;; Configuration defaults
  ; :failure-threshold - Failures before opening (default: 5)
  ; :success-threshold - Successes before closing from half-open (default: 3)
  ; :timeout-ms - Time in open state before half-open (default: 60000)
  ; :half-open-max-calls - Max test calls in half-open (default: 5)
  ; :failure-rate-threshold - Failure rate percentage (default: 50)

  Object
  (toString [this]
    (str "#<CircuitBreaker " (:id this) " state=" @(:state this) ">")))

;; ══════════════════════════════════════════════════════════════════════════════
;; State Transitions
;; ══════════════════════════════════════════════════════════════════════════════

(defn- transition-to!
  "Thread-safe state transition with validation."
  [circuit-breaker new-state]
  (let [cb circuit-breaker
        lock (:lock cb)]
    (.lock lock)
    (try
      (let [old-state @(:state cb)]
        (when (valid-transition? old-state new-state)
          (clojure.core/reset! (:state cb) new-state)
          (swap! (:stats cb) assoc
                 :last-state-change (Date.)
                 :previous-state old-state)
          (when (:metrics cb)
            (metrics/counter-inc! (:metrics cb)
                                  (str "circuit_breaker_" (name new-state))))
          true))
      (finally
        (.unlock lock)))))

(defn- valid-transition?
  "Check if state transition is valid."
  [from-state to-state]
  (case from-state
    :closed (contains? #{:open} to-state)
    :open (contains? #{:half-open} to-state)
    :half-open (contains? #{:closed :open} to-state)
    false))

;; ══════════════════════════════════════════════════════════════════════════════
;; State Query Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn current-state
  "Get current circuit breaker state."
  [circuit-breaker]
  @(:state circuit-breaker))

(defn closed?
  "Check if circuit is closed (allowing requests)."
  [circuit-breaker]
  (= :closed @(:state circuit-breaker)))

(defn open?
  "Check if circuit is open (rejecting requests)."
  [circuit-breaker]
  (= :open @(:state circuit-breaker)))

(defn half-open?
  "Check if circuit is half-open (testing)."
  [circuit-breaker]
  (= :half-open @(:state circuit-breaker)))

(defn allows-request?
  "Check if circuit breaker allows a request through."
  [circuit-breaker]
  (let [state @(:state circuit-breaker)]
    (or (= :closed state)
        (and (= :half-open state)
             (let [successes @(:success-count circuit-breaker)
                   max-calls (get-in circuit-breaker [:config :half-open-max-calls] 5)]
               (< successes max-calls))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Failure Tracking
;; ══════════════════════════════════════════════════════════════════════════════

(defn- record-failure!
  "Record a failure and potentially trip the circuit."
  [circuit-breaker error]
  (let [cb circuit-breaker
        state @(:state cb)]
    (swap! (:failure-count cb) inc)
    (swap! (:stats cb)
           (fn [s]
             (-> s
                 (update :total-failures inc)
                 (assoc :last-failure (Date.)
                    :last-error (str error)))))
    (when (:metrics cb)
      (metrics/counter-inc! (:metrics cb) "circuit_breaker_failures"))

    ;; Check if we should trip the circuit
    (case state
      :closed
      (when (>= @(:failure-count cb)
                (get-in cb [:config :failure-threshold] 5))
        (transition-to! cb :open)
        (swap! (:stats cb) assoc :tripped-at (Date.)))

      :half-open
      (do
        ;; Any failure in half-open immediately opens
        (transition-to! cb :open)
        (clojure.core/reset! (:success-count cb) 0))

      :open nil)))

(defn- record-success!
  "Record a success and potentially close the circuit."
  [circuit-breaker]
  (let [cb circuit-breaker
        state @(:state cb)]
    (swap! (:stats cb)
           (fn [s]
             (-> s
                 (update :total-successes inc)
                 (assoc :last-success (Date.)))))

    (when (:metrics cb)
      (metrics/counter-inc! (:metrics cb) "circuit_breaker_successes"))

    (case state
      :closed
      ;; Reset failure count on success
      (clojure.core/reset! (:failure-count cb) 0)

      :half-open
      (do
        (swap! (:success-count cb) inc)
        (when (>= @(:success-count cb)
                  (get-in cb [:config :success-threshold] 3))
          (transition-to! cb :closed)
          (clojure.core/reset! (:failure-count cb) 0)
          (clojure.core/reset! (:success-count cb) 0)))

      :open nil)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Timeout Check
;; ══════════════════════════════════════════════════════════════════════════════

(defn- check-timeout!
  "Check if timeout has elapsed and transition to half-open if needed."
  [circuit-breaker]
  (let [cb circuit-breaker]
    (when (and (open? cb)
               (let [tripped-at (get @(:stats cb) :tripped-at)
                     timeout-ms (get-in cb [:config :timeout-ms] 60000)]
                 (and tripped-at
                      (> (- (System/currentTimeMillis)
                            (.getTime ^Date tripped-at))
                         timeout-ms))))
      (transition-to! cb :half-open)
      (clojure.core/reset! (:success-count cb) 0)
      true)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Main API
;; ══════════════════════════════════════════════════════════════════════════════

(defn with-circuit-breaker
  "Execute a function with circuit breaker protection.

   Returns the result of f if circuit allows and execution succeeds.
   Returns {:circuit-breaker :open :error ...} if circuit is open.
   Returns {:circuit-breaker :failed :error ...} if execution fails.

   Example:
   (with-circuit-breaker cb
     (fn [] (http-get \"http://api.example.com\")))"
  [circuit-breaker f]
  ;; First check timeout (may transition to half-open)
  (check-timeout! circuit-breaker)

  (if (allows-request? circuit-breaker)
    (try
      (let [result (f)]
        (record-success! circuit-breaker)
        result)
      (catch Exception e
        (record-failure! circuit-breaker e)
        {:circuit-breaker :failed
         :error (.getMessage e)
         :exception e}))
    {:circuit-breaker :open
     :error "Circuit breaker is open"
     :retry-after-ms (when-let [tripped (get @(:stats circuit-breaker) :tripped-at)]
                       (let [timeout (get-in circuit-breaker [:config :timeout-ms] 60000)
                             elapsed (- (System/currentTimeMillis) (.getTime ^Date tripped))]
                         (max 0 (- timeout elapsed))))}))

(defn with-circuit-breaker-async
  "Execute an async function with circuit breaker protection.

   Returns a channel that will receive the result.

   Example:
   (let [ch (with-circuit-breaker-async cb
             (fn [] (async-http-get \"http://api.example.com\")))]
     (async/<!! ch))"
  [circuit-breaker f]
  (async/go
    (with-circuit-breaker circuit-breaker f)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Manual Control
;; ══════════════════════════════════════════════════════════════════════════════

(defn trip!
  "Manually trip (open) the circuit breaker."
  [circuit-breaker]
  (let [cb circuit-breaker]
    (.lock (:lock cb))
    (try
      (when (closed? cb)
        (transition-to! cb :open)
        (swap! (:stats cb) assoc
               :tripped-at (Date.)
               :manually-tripped true))
      (finally
        (.unlock (:lock cb))))))

(defn reset!
  "Manually reset (close) the circuit breaker."
  [circuit-breaker]
  (let [cb circuit-breaker]
    (.lock (:lock cb))
    (try
      ;; Directly set state to closed (bypassing normal transition rules)
      (let [old-state @(:state cb)]
        (clojure.core/reset! (:state cb) :closed)
        (clojure.core/reset! (:failure-count cb) 0)
        (clojure.core/reset! (:success-count cb) 0)
        (swap! (:stats cb) assoc
               :reset-at (Date.)
               :manually-reset true
               :last-state-change (Date.)
               :previous-state old-state))
      (finally
        (.unlock (:lock cb))))))

(defn force-half-open!
  "Force circuit breaker to half-open state for testing."
  [circuit-breaker]
  (let [cb circuit-breaker]
    (.lock (:lock cb))
    (try
      (when (open? cb)
        (transition-to! cb :half-open)
        (clojure.core/reset! (:success-count cb) 0))
      (finally
        (.unlock (:lock cb))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Status and Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn circuit-breaker-status
  "Get current circuit breaker status."
  [circuit-breaker]
  (let [cb circuit-breaker
        stats @(:stats cb)]
    {:id (:id cb)
     :state @(:state cb)
     :failure-count @(:failure-count cb)
     :success-count @(:success-count cb)
     :config (:config cb)
     :allows-requests? (allows-request? cb)
     :stats stats}))

(defn circuit-breaker-metrics
  "Get circuit breaker metrics for monitoring."
  [circuit-breaker]
  (let [stats @(:stats circuit-breaker)]
    {:state @(:state circuit-breaker)
     :total-failures (:total-failures stats 0)
     :total-successes (:total-successes stats 0)
     :current-failures @(:failure-count circuit-breaker)
     :uptime-ms (when-let [created (:created-at stats)]
                  (- (System/currentTimeMillis) (.getTime ^Date created)))}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(def default-config
  {:failure-threshold 5      ; Failures before opening
   :success-threshold 3      ; Successes before closing from half-open
   :timeout-ms 60000         ; Time in open state before half-open (60s)
   :half-open-max-calls 5    ; Max test calls in half-open state
   :failure-rate-threshold 50}) ; Failure rate percentage (not yet implemented)
   

(defn create-circuit-breaker
  "Create a new CircuitBreaker instance.

   Options:
     :id - Unique identifier (auto-generated if not provided)
     :failure-threshold - Failures before opening (default: 5)
     :success-threshold - Successes before closing (default: 3)
     :timeout-ms - Time in open state (default: 60000)
     :half-open-max-calls - Max test calls (default: 5)
     :metrics - MetricsCollector instance

   Example:
   (create-circuit-breaker {:failure-threshold 10 :timeout-ms 30000})"
  [{:keys [id failure-threshold success-threshold timeout-ms
           half-open-max-calls failure-rate-threshold metrics]
    :or {failure-threshold 5
         success-threshold 3
         timeout-ms 60000
         half-open-max-calls 5
         failure-rate-threshold 50}
    :as opts}]
  (let [config (merge default-config
                      {:failure-threshold failure-threshold
                       :success-threshold success-threshold
                       :timeout-ms timeout-ms
                       :half-open-max-calls half-open-max-calls
                       :failure-rate-threshold failure-rate-threshold}
                      (select-keys opts [:failure-threshold :success-threshold
                                         :timeout-ms :half-open-max-calls
                                         :failure-rate-threshold]))]
    (->CircuitBreaker
     (or id (str (java.util.UUID/randomUUID)))
     (atom :closed)
     (atom 0)
     (atom 0)
     config
     (atom {:created-at (Date.)
            :total-failures 0
            :total-successes 0})
     metrics
     (ReentrantLock.))))
