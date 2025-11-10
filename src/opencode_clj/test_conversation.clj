(ns opencode-clj.test-conversation
  (:require [opencode-clj.core :as opencode]
            [opencode-clj.macros.core :as macros]))

;; 创建客户端连接到opencode服务器
(macros/defopencode test-client "http://127.0.0.1:9711")

(defn test-basic-conversation []
  (println "=== 开始基础对话测试 ===")

  ;; 创建会话
  (let [session (opencode/create-session test-client {:title "测试对话会话"})]
    (println "创建会话成功:" (:id session))

    ;; 发送第一条消息
    (let [response1 (opencode/send-prompt test-client (:id session) {:text "你好，请介绍一下你自己"} "user-chat-assistant")]
      (println "发送第一条消息成功")
      (println "响应:" response1))

    ;; 发送第二条消息
    (let [response2 (opencode/send-prompt test-client (:id session) {:text "你能帮我写一个python的hello world函数吗？"} "user-chat-assistant")]
      (println "发送第二条消息成功")
      (println "响应:" response2))

    ;; 获取会话历史
    (let [messages (opencode/list-messages test-client (:id session))]
      (println "会话历史消息数量:" (count messages))
      ;; (prn messages)
      (doseq [msg messages]
        (println "消息:" (->> msg :parts (mapv :text) (filterv some?)))))

    ;; 删除会话
    (prn  (opencode/delete-session test-client (:id session)))))

(defn -main [& args]
  (println "开始运行对话测试...")
  (try
    (test-basic-conversation)
    (println "\n=== 测试完成 ===")
    (catch Exception e
      (println "测试过程中出现错误:" (.getMessage e)))))
