(ns anima-agent-clj.channel.registry-test
  "Tests for channel registry module.

   Tests channel registration, lookup, and health reporting."
  (:require [clojure.test :refer [deftest is testing]]
            [anima-agent-clj.channel.registry :as registry]
            [anima-agent-clj.channel.cli :as cli]
            [anima-agent-clj.channel :as ch]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Registry Creation Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest create-registry-test
  (testing "Create empty registry"
    (let [reg (registry/create-registry)]
      (is (= 0 (registry/channel-count reg)))
      (is (empty? (registry/channel-names reg)))
      (is (empty? (registry/all-channels reg))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Registration Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest register-channel-test
  (testing "Register a channel"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (is (= 0 (registry/channel-count reg)))
      (registry/register reg cli-ch)
      (is (= 1 (registry/channel-count reg)))
      (is (= ["cli"] (vec (registry/channel-names reg)))))))

(deftest register-channel-default-account-test
  (testing "Register channel with default account"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (registry/register reg cli-ch "default")
      (is (= cli-ch (registry/find-channel reg "cli")))
      (is (= cli-ch (registry/find-channel reg "cli" "default"))))))

(deftest register-channel-custom-account-test
  (testing "Register channel with custom account"
    (let [reg (registry/create-registry)
          cli-ch1 (cli/create-cli-channel {})
          cli-ch2 (cli/create-cli-channel {})]
      (registry/register reg cli-ch1 "default")
      (registry/register reg cli-ch2 "production")
      (is (= 2 (registry/channel-count reg)))
      (is (= cli-ch1 (registry/find-channel reg "cli")))
      (is (= cli-ch1 (registry/find-channel reg "cli" "default")))
      (is (= cli-ch2 (registry/find-channel reg "cli" "production"))))))

(deftest register-multiple-channels-test
  (testing "Register multiple channels"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})
          cli-ch2 (cli/create-cli-channel {})]
      (registry/register reg cli-ch "cli1")
      (registry/register reg cli-ch2 "cli2")
      (is (= 2 (registry/channel-count reg)))
      (is (some? (registry/find-channel reg "cli" "cli1")))
      (is (some? (registry/find-channel reg "cli" "cli2"))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Unregistration Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest unregister-channel-test
  (testing "Unregister a channel"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (registry/register reg cli-ch)
      (is (= 1 (registry/channel-count reg)))
      (registry/unregister reg "cli")
      (is (= 0 (registry/channel-count reg)))
      (is (nil? (registry/find-channel reg "cli"))))))

(deftest unregister-with-account-test
  (testing "Unregister channel for specific account"
    (let [reg (registry/create-registry)
          cli-ch1 (cli/create-cli-channel {})
          cli-ch2 (cli/create-cli-channel {})]
      (registry/register reg cli-ch1 "default")
      (registry/register reg cli-ch2 "production")
      (is (= 2 (registry/channel-count reg)))
      (registry/unregister-with-account reg "cli" "production")
      (is (= 1 (registry/channel-count reg)))
      (is (some? (registry/find-channel reg "cli" "default")))
      (is (= cli-ch1 (registry/find-channel reg "cli" "production"))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Channel Lookup Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest find-channel-test
  (testing "Find channel by name"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (registry/register reg cli-ch)
      (is (= cli-ch (registry/find-channel reg "cli")))
      (is (nil? (registry/find-channel reg "nonexistent"))))))

(deftest find-channel-fallback-test
  (testing "Find channel falls back to default account"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (registry/register reg cli-ch "default")
      ;; Should find default even when requesting unknown account
      (is (= cli-ch (registry/find-channel reg "cli" "unknown"))))))

(deftest all-channels-test
  (testing "Get all registered channels"
    (let [reg (registry/create-registry)
          cli-ch1 (cli/create-cli-channel {})
          cli-ch2 (cli/create-cli-channel {})]
      (is (empty? (registry/all-channels reg)))
      (registry/register reg cli-ch1 "cli1")
      (registry/register reg cli-ch2 "cli2")
      (is (= 2 (count (registry/all-channels reg)))))))

(deftest channel-names-test
  (testing "Get channel names"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (is (empty? (registry/channel-names reg)))
      (registry/register reg cli-ch)
      (is (= '("cli") (registry/channel-names reg))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Lifecycle Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest start-all-test
  (testing "Start all registered channels"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (registry/register reg cli-ch)
      (is (false? @(:running? cli-ch)))
      (registry/start-all reg)
      (is (true? @(:running? cli-ch)))
      ;; Cleanup
      (registry/stop-all reg))))

(deftest stop-all-test
  (testing "Stop all registered channels"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (registry/register reg cli-ch)
      (ch/start cli-ch)
      (is (true? @(:running? cli-ch)))
      (registry/stop-all reg)
      (is (false? @(:running? cli-ch))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Health Report Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest health-report-empty-test
  (testing "Health report with no channels"
    (let [reg (registry/create-registry)
          report (registry/health-report reg)]
      (is (= 0 (:total report)))
      (is (= 0 (:healthy report)))
      (is (= 0 (:unhealthy report)))
      (is (false? (:all-healthy? report))))))

(deftest health-report-unhealthy-test
  (testing "Health report with unhealthy channel"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (registry/register reg cli-ch)
      (let [report (registry/health-report reg)]
        (is (= 1 (:total report)))
        (is (= 0 (:healthy report)))
        (is (= 1 (:unhealthy report)))
        (is (false? (:all-healthy? report)))))))

(deftest health-report-healthy-test
  (testing "Health report with healthy channel"
    (let [reg (registry/create-registry)
          cli-ch (cli/create-cli-channel {})]
      (registry/register reg cli-ch)
      (ch/start cli-ch)
      (let [report (registry/health-report reg)]
        (is (= 1 (:total report)))
        (is (= 1 (:healthy report)))
        (is (= 0 (:unhealthy report)))
        (is (true? (:all-healthy? report))))
      (ch/stop cli-ch))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Multi-Account Tests
;; ══════════════════════════════════════════════════════════════════════════════

(deftest multi-account-test
  (testing "Multiple accounts for same channel type"
    (let [reg (registry/create-registry)
          dev-ch (cli/create-cli-channel {})
          prod-ch (cli/create-cli-channel {})]
      (registry/register reg dev-ch "development")
      (registry/register reg prod-ch "production")
      (is (= 2 (registry/channel-count reg)))
      (is (= dev-ch (registry/find-channel reg "cli" "development")))
      (is (= prod-ch (registry/find-channel reg "cli" "production")))
      ;; Default lookup returns first available
      (is (some? (registry/find-channel reg "cli"))))))
