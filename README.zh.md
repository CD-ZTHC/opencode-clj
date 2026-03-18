# Anima-Agent-Clj

一个全面的 Clojure 客户端库和 Agent 集群框架，用于与 [opencode-server](https://github.com/sst/opencode) REST API 交互，构建可扩展的多通道 AI 应用。

## 特性

- **完整 API 覆盖**: 实现所有 opencode-server 端点
- **地道 Clojure 风格**: 遵循 Clojure 最佳实践的函数式 API 设计
- **Agent 集群架构**: 具有异构计算能力的可扩展多代理系统
- **消息总线架构**: 通道与代理间的统一消息路由
- **多通道支持**: CLI、RabbitMQ、HTTP、WebSocket 及可扩展的通道系统
- **智能任务路由**: AI 驱动的任务分类和路由
- **流式支持**: 实时消息流能力

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Agent 集群架构                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                           外部接口层                                   │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐         │   │
│  │  │   CLI   │ │ RabbitMQ│ │  HTTP   │ │WebSocket│ │   ...   │         │   │
│  │  │ Channel │ │ Channel │ │ Channel │ │ Channel │ │ Channel │         │   │
│  │  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘         │   │
│  └───────┼───────────┼───────────┼───────────┼───────────┼───────────────┘   │
│          │           │           │           │           │                    │
│          └───────────┴─────┬─────┴───────────┴───────────┘                    │
│                              ↓                                                │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                       通道适配器中心                                   │   │
│  │                    (统一接入层 / 协议转换 / 路由)                        │   │
│  └────────────────────────────────┬─────────────────────────────────────┘   │
│                                   ↓                                          │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                           消息总线                                     │   │
│  │  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────────┐     │   │
│  │  │   入站总线      │ │   内部总线      │ │     出站总线        │     │   │
│  │  │  (外部请求入口) │ │  (组件间通信)   │ │   (响应/事件输出)   │     │   │
│  │  └────────┬────────┘ └────────┬────────┘ └──────────┬──────────┘     │   │
│  └───────────┼───────────────────┼────────────────────┼─────────────────┘   │
│              │                   │                    │                       │
│              ↓                   ↓                    ↓                       │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                          事件调度器                                    │  │
│  │              (优先级调度 / 负载均衡 / 路由 / 熔断)                       │  │
│  └───────────────────────────────────┬───────────────────────────────────┘  │
│                                      │                                       │
│              ┌───────────────────────┼───────────────────────┐               │
│              │                       │                       │               │
│              ↓                       ↓                       ↓               │
│  ┌───────────────────┐  ┌───────────────────┐  ┌───────────────────┐        │
│  │                   │  │                   │  │                   │        │
│  │  ┌─────────────┐  │  │  ┌─────────────┐  │  │  ┌─────────────┐  │        │
│  │  │ Core Agent  │  │  │  │ Core Agent  │  │  │  │ Core Agent  │  │        │
│  │  │  (核心代理)  │  │  │  │  (核心代理)  │  │  │  │  (核心代理)  │  │        │
│  │  └──────┬──────┘  │  │  └──────┬──────┘  │  │  └──────┬──────┘  │        │
│  │         │         │  │         │         │  │         │         │        │
│  │  ┌──────┴──────┐  │  │  ┌──────┴──────┐  │  │  ┌──────┴──────┐  │        │
│  │  │Worker Pool  │  │  │  │Worker Pool  │  │  │  │Worker Pool  │  │        │
│  │  │(工作代理池)  │  │  │  │(工作代理池)  │  │  │  │(工作代理池)  │  │        │
│  │  └─────────────┘  │  │  └─────────────┘  │  │  └─────────────┘  │        │
│  │                   │  │                   │  │                   │        │
│  │   Cluster Node 0  │  │   Cluster Node 1  │  │   Cluster Node N  │        │
│  └─────────┬─────────┘  └─────────┬─────────┘  └─────────┬─────────┘        │
│            │                      │                      │                  │
│            └──────────────────────┼──────────────────────┘                  │
│                                   ↓                                         │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                      并行代理池 (Parallel Agent Pool)                  │  │
│  │           (大规模并行任务处理 / MapReduce / 批量推理)                    │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                      专家代理池 (Specialist Agent Pool)                │  │
│  │    ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │  │
│  │    │  代码    │ │  搜索    │ │  分析    │ │  工具    │  ...          │  │
│  │    │  专家    │ │  专家    │ │  专家    │ │  专家    │               │  │
│  │    └──────────┘ └──────────┘ └──────────┘ └──────────┘               │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                          支撑层                                        │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐     │  │
│  │  │  上下文     │ │   结果      │ │    数据     │ │   配置      │     │  │
│  │  │  管理器     │ │   缓存      │ │   管道      │ │   中心      │     │  │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘     │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 核心组件

### Agent 类型

| Agent 类型 | 角色 | 职责 |
|-----------|------|------|
| **Core Agent** (核心代理) | 复杂推理 | 复杂推理、决策、编排协调、会话上下文管理 |
| **Worker Agent** (工作代理) | 轻量任务 | 简单查询响应、数据预处理/后处理、IO 密集型任务 |
| **Parallel Agent Pool** (并行代理池) | 并行处理 | 大规模并行任务、MapReduce、批量推理、数据分析 |
| **Specialist Agent Pool** (专家代理池) | 领域专家 | 代码生成、搜索、分析、工具执行、安全审计 |

### 基础设施组件

| 组件 | 角色 | 描述 |
|------|------|------|
| **Message Bus** (消息总线) | 通信枢纽 | 统一通信通道，包含入站、内部、出站子总线 |
| **Event Dispatcher** (事件调度器) | 任务调度 | 优先级调度、负载均衡、熔断保护 |
| **Context Manager** (上下文管理器) | 状态管理 | 会话存储、上下文窗口管理、状态持久化 |
| **Result Cache** (结果缓存) | 性能优化 | 多级缓存 (L1/L2/L3) 用于推理结果 |
| **Data Pipeline** (数据管道) | 数据流转 | 批量数据传输、ETL 处理、背压管理 |
| **Channel Adapters** (通道适配器) | 外部接口 | CLI、RabbitMQ、HTTP、WebSocket 集成 |

## 安装

在 `project.clj` 中添加依赖：

```clojure
[anima-agent-clj "0.1.0-SNAPSHOT"]
```

或在 `deps.edn` 中：

```clojure
anima-agent-clj {:mvn/version "0.1.0-SNAPSHOT"}
```

## 快速开始

### 1. 基本客户端

```clojure
(ns my-app.core
  (:require [anima-agent-clj.core :as opencode]))

;; 创建连接到 opencode-server 的客户端
(def client (opencode/client "http://127.0.0.1:9711"))

;; 创建会话并发送提示
(let [session (opencode/create-session client {:title "我的会话"})]
  (opencode/send-prompt client
                        (:id session)
                        {:text "你好，能帮我编程吗？"}
                        "user-chat-assistant"))
```

### 2. 消息总线架构

```clojure
(ns my-app.core
  (:require [anima-agent-clj.bus :as bus]
            [anima-agent-clj.agent :as agent]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.cli :as cli]
            [anima-agent-clj.channel.registry :as registry]
            [anima-agent-clj.channel.dispatch :as dispatch]
            [anima-agent-clj.channel.session :as session]))

;; 创建基础设施
(let [msg-bus (bus/create-bus)
      store (session/create-store)
      reg (registry/create-registry)
      stats (dispatch/create-dispatch-stats)

      ;; 创建 CLI 通道
      cli-ch (cli/create-cli-channel {:session-store store
                                      :bus msg-bus})

      ;; 创建代理
      msg-agent (agent/create-agent {:bus msg-bus
                                     :opencode-url "http://127.0.0.1:9711"})]

  ;; 注册并启动
  (registry/register reg cli-ch)
  (ch/start cli-ch)
  (agent/start-agent msg-agent)
  (dispatch/start-outbound-dispatch (:outbound-chan msg-bus) reg stats))
```

### 3. 智能任务路由

```clojure
(require '[anima-agent-clj.agent.intelligent-router :as router])

;; 创建智能路由器
(def router (router/create-intelligent-router
              {:bus bus
               :register-defaults true}))

;; 启动路由器
(router/start-router! router)

;; 发送消息
(router/send-message router "写一个排序函数" {})
;; => {:success true
;;     :task-type :code-generation
;;     :confidence 0.85
;;     :message "✨ 代码生成完成..."}

;; 停止路由器
(router/stop-router! router)
```

**支持的任务类型:**

| 类型 | 说明 | 关键词 |
|------|------|--------|
| `:code-generation` | 代码生成 | write, create, implement, 编写 |
| `:code-review` | 代码审查 | review, check, analyze, 审查 |
| `:code-debug` | 代码调试 | debug, fix, error, 调试, 修复 |
| `:code-refactor` | 代码重构 | refactor, optimize, 重构 |
| `:web-search` | 网络搜索 | search, find, 搜索 |
| `:documentation` | 文档生成 | document, docs, 文档 |
| `:data-analysis` | 数据分析 | analyze, data, 分析, 数据 |
| `:test-generation` | 测试生成 | test, spec, 测试 |
| `:general-chat` | 普通对话 | hello, hi, 你好 |

### 4. 实时多代理系统

```bash
# 启动 OpenCode 服务器 (默认: http://127.0.0.1:9711)
lein run -m realtime-multi-agent-demo

# 或使用自定义 URL
lein run -m realtime-multi-agent-demo -- --url http://my-server:9711
```

**交互示例:**

```
> 你好
  🏷️ AI分类: :simple-chat (置信度: 95%)
💬 你好！有什么可以帮你的吗？

> 用 React 前端和 Node.js 后端构建一个网站
  🏷️ AI分类: :complex-task (置信度: 95%)
🚀 任务已开始执行! ID: abc-123
输入 'status' 查看进度

> status
📋 任务状态:
🔄 构建网站... - 运行中 (35%)
  ⏳ 项目设置 - 待处理
  🔄 数据库设计 - 运行中
  ...
```

## 核心 API

### 会话管理

```clojure
;; 列出所有会话
(opencode/list-sessions client)

;; 创建新会话
(opencode/create-session client {:title "我的编程会话"})

;; 获取会话详情
(opencode/get-session client session-id)

;; 更新会话
(opencode/update-session client session-id {:title "更新后的标题"})

;; 删除会话
(opencode/delete-session client session-id)
```

### 消息操作

```clojure
;; 发送提示给 AI
(opencode/send-prompt client session-id
                     {:text "帮我调试这段代码"}
                     "user-chat-assistant")

;; 列出会话中的消息
(opencode/list-messages client session-id)
```

### 文件操作

```clojure
;; 列出项目中的文件
(opencode/list-files client)

;; 读取文件内容
(opencode/read-file client file-path)

;; 在文件中查找文本
(opencode/find-text client search-pattern)
```

## CLI 应用

```bash
# 启动交互式 CLI
lein run -m anima-agent-clj.cli-main

# 使用自定义选项
lein run -m anima-agent-clj.cli-main -- --url http://my-server:9711
lein run -m anima-agent-clj.cli-main -- --prompt 'ai> '
```

### CLI 命令

| 命令 | 描述 |
|------|------|
| `help` | 显示可用命令 |
| `status` | 显示会话状态 |
| `history` | 显示对话历史 |
| `clear` | 清除对话历史 |
| `exit/quit/:q` | 退出 CLI |

## 测试

```bash
lein test
lein test anima-agent-clj.core-test
```

## 构建

```bash
lein deps
lein uberjar
```

## 命名空间结构

```
anima-agent-clj.cluster
├── core                    ; 集群核心
├── node                    ; 节点管理
├── coordinator             ; 集群协调器
└── topology                ; 拓扑管理

anima-agent-clj.bus
├── core                    ; 总线核心
├── inbound                 ; 入站总线
├── outbound                ; 出站总线
├── internal                ; 内部总线
└── control                 ; 控制总线

anima-agent-clj.dispatcher
├── core                    ; 调度器核心
├── router                  ; 路由策略
├── balancer                ; 负载均衡
└── circuit-breaker         ; 熔断器

anima-agent-clj.agent
├── core                    ; Agent 核心
├── core-agent              ; 核心 Agent
├── worker-agent            ; Worker Agent
├── parallel-pool           ; 并行池
├── specialist-pool         ; 专家池
├── task-classifier         ; 任务分类
├── intelligent-router      ; 智能路由
└── ai-classifier           ; AI 分类器

anima-agent-clj.context
├── core                    ; 上下文核心
├── manager                 ; 上下文管理器
├── storage                 ; 存储层
└── compression             ; 压缩/摘要

anima-agent-clj.cache
├── core                    ; 缓存核心
├── l1                      ; L1 缓存
├── l2                      ; L2 缓存
└── l3                      ; L3 缓存 (Redis)

anima-agent-clj.pipeline
├── core                    ; Pipeline 核心
├── source                  ; 数据源
├── transform               ; 转换器
└── sink                    ; 数据目标

anima-agent-clj.channel
├── core                    ; Channel 协议
├── adapter                 ; 适配器
├── cli                     ; CLI Channel
├── rabbitmq                ; RabbitMQ Channel
├── http                    ; HTTP Channel
└── websocket               ; WebSocket Channel
```

## 贡献

1. Fork 本仓库
2. 创建功能分支
3. 进行更改
4. 添加测试
5. 运行测试套件
6. 提交 Pull Request

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件。

## 支持

- 问题反馈: [GitHub Issues](https://github.com/anima-agent-clj/anima-agent-clj/issues)
- 文档: [API 参考](https://github.com/anima-agent-clj/anima-agent-clj/wiki)
- OpenCode 服务器: [opencode-server](https://github.com/sst/opencode)
