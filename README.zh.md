# OpenCode Clojure 客户端

一个全面的 Clojure 客户端库，用于与 [opencode-server](https://github.com/opencode-server/opencode-server) REST API 进行交互。该库为所有 opencode-server 功能提供了符合 Clojure 习惯的包装器，使您能够轻松地将 AI 驱动的编码助手集成到 Clojure 应用程序中。

## 特性

- **完整 API 覆盖**：完全实现所有 opencode-server 端点
- **符合 Clojure 习惯**：遵循 Clojure 最佳实践的清晰、函数式 API 设计
- **宏支持**：为常见操作提供便捷的宏
- **会话管理**：创建、管理和交互编码会话
- **消息处理**：发送提示并接收 AI 响应
- **文件操作**：读取、写入和管理项目文件
- **配置管理**：动态配置管理
- **异步支持**：异步操作以获得更好的性能

## 安装

在您的 `project.clj` 中添加以下依赖：

```clojure
[opencode-clj "0.1.0-SNAPSHOT"]
```

或者在您的 `deps.edn` 中：

```clojure
opencode-clj {:mvn/version "0.1.0-SNAPSHOT"}
```

## 快速开始

### 1. 创建客户端

```clojure
(ns my-app.core
  (:require [opencode-clj.core :as opencode]))

;; 创建连接到 opencode-server 的客户端
(def client (opencode/client "http://127.0.0.1:9711"))
```

### 2. 使用宏以获得便利

```clojure
(ns my-app.core
  (:require [opencode-clj.macros.core :as macros]))

;; 使用宏定义客户端
(macros/defopencode my-client "http://127.0.0.1:9711")
```

### 3. 基础对话示例

```clojure
(ns my-app.core
  (:require [opencode-clj.core :as opencode]
            [opencode-clj.macros.core :as macros]))

(macros/defopencode test-client "http://127.0.0.1:9711")

(defn test-basic-conversation []
  ;; 创建会话
  (let [session (opencode/create-session test-client {:title "测试对话会话"})]
    (println "创建会话成功:" (:id session))

    ;; 发送提示
    (let [response (opencode/send-prompt test-client 
                                        (:id session) 
                                        {:text "你好，你能帮我写一个 Python 的 hello world 函数吗？"}
                                        "user-chat-assistant")]
      (println "响应:" response))

    ;; 获取消息历史
    (let [messages (opencode/list-messages test-client (:id session))]
      (println "消息数量:" (count messages)))

    ;; 清理
    (opencode/delete-session test-client (:id session))))
```

## 核心 API

### 客户端管理

```clojure
;; 创建带选项的客户端
(def client (opencode/client "http://127.0.0.1:9711" 
                            {:directory "/path/to/project"
                             :http-opts {:timeout 5000}}))
```

### 会话管理

```clojure
;; 列出所有会话
(opencode/list-sessions client)

;; 创建新会话
(opencode/create-session client {:title "我的编码会话"})

;; 获取会话详情
(opencode/get-session client session-id)

;; 更新会话
(opencode/update-session client session-id {:title "更新后的标题"})

;; 删除会话
(opencode/delete-session client session-id)

;; 分支会话
(opencode/fork-session client session-id)

;; 共享会话
(opencode/share-session client session-id)
```

### 消息处理

```clojure
;; 向 AI 发送提示
(opencode/send-prompt client session-id 
                     {:text "帮我调试这段代码"} 
                     "user-chat-assistant")

;; 列出会话中的消息
(opencode/list-messages client session-id)

;; 执行命令
(opencode/execute-command client session-id command)

;; 运行 shell 命令
(opencode/run-shell-command client session-id command)
```

### 文件操作

```clojure
;; 列出项目中的文件
(opencode/list-files client)

;; 读取文件内容
(opencode/read-file client file-path)

;; 在文件中查找文本
(opencode/find-text client search-pattern)

;; 按模式查找文件
(opencode/find-files client file-pattern)

;; 查找符号
(opencode/find-symbols client symbol-pattern)
```

### 配置管理

```clojure
;; 获取当前配置
(opencode/get-config client)

;; 更新配置
(opencode/update-config client new-config)

;; 列出可用提供者
(opencode/list-providers client)

;; 列出可用命令
(opencode/list-commands client)

;; 列出可用代理
(opencode/list-agents client)
```

## 高级用法

### 使用聊天机器人宏

```clojure
(ns my-app.core
  (:require [opencode-clj.macros.chatbot :as chatbot]))

;; 定义具有自定义行为的聊天机器人
(chatbot/defchatbot my-bot "http://127.0.0.1:9711"
  :system-prompt "你是一个专门从事 Clojure 的有用编码助手。"
  :temperature 0.7)
```

### 异步操作

```clojure
(ns my-app.core
  (:require [opencode-clj.macros.async :as async]
            [clojure.core.async :refer [<!!]]))

;; 执行异步操作
(let [result-chan (async/send-prompt-async client session-id prompt)]
  (println "响应:" (<!! result-chan)))
```

### 复杂工作流的 DSL

```clojure
(ns my-app.core
  (:require [opencode-clj.macros.dsl :as dsl]))

;; 定义复杂工作流
(dsl/defworkflow code-review-workflow
  [client session-id file-path]
  (dsl/send-prompt "请审查这段代码的潜在问题")
  (dsl/wait-for-response)
  (dsl/send-prompt "你能提出改进建议吗？")
  (dsl/wait-for-response))
```

## 测试

运行测试套件：

```bash
lein test
```

运行特定测试：

```bash
lein test opencode-clj.core-test
lein test :only opencode-clj.core-test/test-client-creation
```

## 构建

构建项目：

```bash
lein deps
lein uberjar
```

## 配置

该库支持各种配置选项：

- `:base-url` - OpenCode 服务器 URL（必需）
- `:directory` - 项目目录路径
- `:http-opts` - HTTP 客户端选项（超时、头信息等）

## 错误处理

所有函数要么返回成功映射，要么抛出异常：

```clojure
(try
  (let [response (opencode/send-prompt client session-id prompt)]
    (if (:success response)
      (println "成功:" response)
      (println "错误:" (:error response))))
  (catch Exception e
    (println "异常:" (.getMessage e))))
```

## 贡献

1. Fork 仓库
2. 创建特性分支
3. 进行更改
4. 添加测试
5. 运行测试套件
6. 提交拉取请求

## 许可证

本项目采用 EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0 许可证 - 有关详细信息，请参阅 [LICENSE](LICENSE) 文件。

## 支持

- 问题：[GitHub Issues](https://github.com/opencode-clj/opencode-clj/issues)
- 文档：[API 参考](https://github.com/opencode-clj/opencode-clj/wiki)
- OpenCode 服务器：[opencode-server](https://github.com/opencode-server/opencode-server)