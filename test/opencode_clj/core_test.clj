(ns opencode-clj.core-test
  (:require [clojure.test :refer :all]
            [opencode-clj.core :as opencode]))

(deftest test-client-creation
  (testing "Create client with base URL"
    (let [client (opencode/client "http://127.0.0.1:9711")]
      (is (map? client))
      (is (= "http://127.0.0.1:9711" (:base-url client)))
      (is (nil? (:directory client)))
      (is (map? (:http-opts client)))))

  (testing "Create client with base URL and options"
    (let [client (opencode/client "http://127.0.0.1:9711" {:directory "/tmp" :http-opts {:timeout 5000}})]
      (is (= "http://127.0.0.1:9711" (:base-url client)))
      (is (= "/tmp" (:directory client)))
      (is (= {:timeout 5000} (:http-opts client))))))

(deftest test-function-exports
  (testing "All core functions are exported"
    (let [client (opencode/client "http://127.0.0.1:9711")]
      ;; Project management functions
      (is (fn? opencode/list-projects))
      (is (fn? opencode/current-project))

      ;; Session management functions
      (is (fn? opencode/list-sessions))
      (is (fn? opencode/create-session))
      (is (fn? opencode/get-session))
      (is (fn? opencode/update-session))
      (is (fn? opencode/delete-session))
      (is (fn? opencode/get-session-children))
      (is (fn? opencode/get-session-todo))
      (is (fn? opencode/init-session))
      (is (fn? opencode/fork-session))
      (is (fn? opencode/abort-session))
      (is (fn? opencode/share-session))
      (is (fn? opencode/unshare-session))
      (is (fn? opencode/get-session-diff))
      (is (fn? opencode/summarize-session))

      ;; Messaging functions
      (is (fn? opencode/list-messages))
      (is (fn? opencode/get-message))
      (is (fn? opencode/send-prompt))
      (is (fn? opencode/execute-command))
      (is (fn? opencode/run-shell-command))
      (is (fn? opencode/revert-message))
      (is (fn? opencode/unrevert-messages))
      (is (fn? opencode/respond-to-permission))

      ;; File operations
      (is (fn? opencode/list-files))
      (is (fn? opencode/read-file))
      (is (fn? opencode/get-file-status))
      (is (fn? opencode/find-text))
      (is (fn? opencode/find-files))
      (is (fn? opencode/find-symbols))

      ;; Configuration functions
      (is (fn? opencode/get-config))
      (is (fn? opencode/update-config))
      (is (fn? opencode/list-providers))
      (is (fn? opencode/list-commands))
      (is (fn? opencode/list-agents))
      (is (fn? opencode/get-tool-ids))
      (is (fn? opencode/list-tools))
      (is (fn? opencode/get-path))
      (is (fn? opencode/write-log))
      (is (fn? opencode/get-mcp-status))
      (is (fn? opencode/get-lsp-status))
      (is (fn? opencode/get-formatter-status))
      (is (fn? opencode/set-auth)))))
