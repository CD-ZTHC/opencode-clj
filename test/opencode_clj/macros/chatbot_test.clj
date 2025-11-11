(ns opencode-clj.macros.chatbot-test) (ns opencode-clj.macros.chatbot-test
                                        (:require [clojure.test :refer :all]
                                                  [opencode-clj.macros.chatbot :refer :all]))

(deftest test-def-chatbot-macro
  (testing "def-chatbot creates a chatbot with default configuration"
    (def-chatbot test-bot
      :title "Test Bot"
      :system-prompt "You are a test assistant")

    (is (map? test-bot))
    (is (= "Test Bot" (:title test-bot)))
    (is (= "You are a test assistant" (:system-prompt test-bot)))
    (is (= {:base-url "http://127.0.0.1:9711"} (:client test-bot)))
    (is (= {:providerID "anthropic" :modelID "claude-3"} (:default-model test-bot)))))

(deftest test-multimodal-message-macro
  (testing "multimodal-message creates message with parts"
    (let [msg (multimodal-message
               :parts [{:type "text" :content "Hello"}
                       {:type "image" :url "test.jpg"}]
               :metadata {:priority "high"})]
      (is (map? msg))
      (is (= 2 (count (:parts msg))))
      (is (= "high" (get-in msg [:metadata :priority]))))))

(deftest test-conversation-state-macro
  (testing "conversation-state creates state management function"
    (def-chatbot state-test-bot
      :title "State Test Bot")

    (let [state-manager (conversation-state state-test-bot
                                            :mode "test"
                                            :context {:test true})]
      (is (fn? state-manager))
      (let [current-state (state-manager :get)]
        (is (= "test" (:mode current-state)))
        (is (true? (get-in current-state [:context :test])))))))

(deftest test-message-handlers
  (testing "on-message and on-command create handler maps"
    (let [text-handler (on-message :text
                                   (println "Text message received"))
          command-handler (on-command "/test"
                                      (println "Test command received"))]
      (is (map? text-handler))
      (is (map? command-handler))
      (is (contains? (:on-message text-handler) :text))
      (is (contains? (:on-command command-handler) "/test")))))

(deftest test-message-pipeline-macro
  (testing "message-pipeline creates processing function"
    (def-chatbot pipeline-test-bot
      :title "Pipeline Test Bot")

    (let [pipeline (message-pipeline pipeline-test-bot
                                     :step1 (fn [x] (str x "-step1"))
                                     :step2 (fn [x] (str x "-step2")))]
      (is (fn? pipeline))
      (let [result (pipeline "input")]
        (is (= "input-step1-step2" result))))))

(deftest test-async-chat-macro
  (testing "async-chat creates async communication channels"
    (def-chatbot async-test-bot
      :title "Async Test Bot")

    (let [async-system (async-chat async-test-bot
                                   (str "Echo: " input#))]
      (is (map? async-system))
      (is (contains? async-system :input))
      (is (contains? async-system :output)))))
