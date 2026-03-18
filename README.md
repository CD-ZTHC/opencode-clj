# Anima-Agent-Clj

A comprehensive Clojure client library and agent cluster framework for building scalable, multi-channel AI applications with the [opencode-server](https://github.com/sst/opencode) REST API.

## Features

- **Full API Coverage**: Complete implementation of all opencode-server endpoints
- **Idiomatic Clojure**: Clean, functional API design following Clojure best practices
- **Agent Cluster Architecture**: Scalable multi-agent system with heterogeneous computing capabilities
- **Message Bus Architecture**: Unified message routing between channels and agents
- **Multi-Channel Support**: CLI, RabbitMQ, HTTP, WebSocket, and extensible channel system
- **Intelligent Task Routing**: AI-powered task classification and routing
- **Streaming Support**: Real-time message streaming capabilities

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Agent Cluster Architecture                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                         External Interface Layer                       │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐         │   │
│  │  │   CLI   │ │ RabbitMQ│ │  HTTP   │ │WebSocket│ │   ...   │         │   │
│  │  │ Channel │ │ Channel │ │ Channel │ │ Channel │ │ Channel │         │   │
│  │  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘         │   │
│  └───────┼───────────┼───────────┼───────────┼───────────┼───────────────┘   │
│          │           │           │           │           │                    │
│          └───────────┴─────┬─────┴───────────┴───────────┘                    │
│                              ↓                                                │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                         Channel Adapter Hub                            │   │
│  │                    (统一接入层 / 协议转换 / 路由)                        │   │
│  └────────────────────────────────┬─────────────────────────────────────┘   │
│                                   ↓                                          │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                           Message Bus                                  │   │
│  │  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────────┐     │   │
│  │  │   Inbound Bus   │ │  Internal Bus   │ │    Outbound Bus     │     │   │
│  │  │  (外部请求入口)  │ │  (组件间通信)   │ │   (响应/事件输出)   │     │   │
│  │  └────────┬────────┘ └────────┬────────┘ └──────────┬──────────┘     │   │
│  └───────────┼───────────────────┼────────────────────┼─────────────────┘   │
│              │                   │                    │                       │
│              ↓                   ↓                    ↓                       │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        Event Dispatcher                                │  │
│  │              (中断控制器 - 优先级调度 / 负载均衡 / 路由)                  │  │
│  └───────────────────────────────────┬───────────────────────────────────┘  │
│                                      │                                       │
│              ┌───────────────────────┼───────────────────────┐               │
│              │                       │                       │               │
│              ↓                       ↓                       ↓               │
│  ┌───────────────────┐  ┌───────────────────┐  ┌───────────────────┐        │
│  │   Core Agent      │  │   Core Agent      │  │   Core Agent      │        │
│  │   (复杂推理)       │  │   (复杂推理)       │  │   (复杂推理)       │        │
│  │        ↓          │  │        ↓          │  │        ↓          │        │
│  │   Worker Pool     │  │   Worker Pool     │  │   Worker Pool     │        │
│  │   (轻量任务)       │  │   (轻量任务)       │  │   (轻量任务)       │        │
│  │                   │  │                   │  │                   │        │
│  │   Cluster Node 0  │  │   Cluster Node 1  │  │   Cluster Node N  │        │
│  └─────────┬─────────┘  └─────────┬─────────┘  └─────────┬─────────┘        │
│            │                      │                      │                  │
│            └──────────────────────┼──────────────────────┘                  │
│                                   ↓                                         │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     Parallel Agent Pool                                │  │
│  │           (大规模并行任务处理 / MapReduce / 批量推理)                    │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     Specialist Agent Pool                              │  │
│  │    ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │  │
│  │    │  Code    │ │  Search  │ │  Analysis│ │   Tool   │  ...          │  │
│  │    │ Special. │ │ Special. │ │ Special. │ │ Special. │               │  │
│  │    └──────────┘ └──────────┘ └──────────┘ └──────────┘               │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                        Support Layer                                   │  │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐     │  │
│  │  │  Context    │ │   Result    │ │    Data     │ │   Config    │     │  │
│  │  │  Manager    │ │   Cache     │ │  Pipeline   │ │   Center    │     │  │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘     │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Core Components

### Agent Types

| Agent Type | Role | Responsibilities |
|------------|------|------------------|
| **Core Agent** | Complex Reasoning | Complex reasoning, decision making, orchestration, session context management |
| **Worker Agent** | Lightweight Tasks | Simple queries, data preprocessing/postprocessing, IO-intensive tasks |
| **Parallel Agent Pool** | Parallel Processing | Large-scale parallel tasks, MapReduce, batch inference, data analysis |
| **Specialist Agent Pool** | Domain Experts | Code generation, search, analysis, tool execution, security audit |

### Infrastructure Components

| Component | Role | Description |
|-----------|------|-------------|
| **Message Bus** | Communication Hub | Unified communication channel with inbound, internal, and outbound sub-buses |
| **Event Dispatcher** | Task Scheduling | Priority scheduling, load balancing, circuit breaking |
| **Context Manager** | State Management | Session storage, context window management, state persistence |
| **Result Cache** | Performance | Multi-level caching (L1/L2/L3) for inference results |
| **Data Pipeline** | Data Flow | Batch data transfer, ETL processing, backpressure management |
| **Channel Adapters** | External Interface | CLI, RabbitMQ, HTTP, WebSocket integrations |

## Installation

Add the following dependency to your `project.clj`:

```clojure
[anima-agent-clj "0.1.0-SNAPSHOT"]
```

Or in your `deps.edn`:

```clojure
anima-agent-clj {:mvn/version "0.1.0-SNAPSHOT"}
```

## Quick Start

### 1. Basic Client

```clojure
(ns my-app.core
  (:require [anima-agent-clj.core :as opencode]))

;; Create a client connected to your opencode-server
(def client (opencode/client "http://127.0.0.1:9711"))

;; Create a session and send a prompt
(let [session (opencode/create-session client {:title "My Session"})]
  (opencode/send-prompt client
                        (:id session)
                        {:text "Hello, can you help me with programming?"}
                        "user-chat-assistant"))
```

### 2. Message Bus Architecture

```clojure
(ns my-app.core
  (:require [anima-agent-clj.bus :as bus]
            [anima-agent-clj.agent :as agent]
            [anima-agent-clj.channel :as ch]
            [anima-agent-clj.channel.cli :as cli]
            [anima-agent-clj.channel.registry :as registry]
            [anima-agent-clj.channel.dispatch :as dispatch]
            [anima-agent-clj.channel.session :as session]))

;; Create infrastructure
(let [msg-bus (bus/create-bus)
      store (session/create-store)
      reg (registry/create-registry)
      stats (dispatch/create-dispatch-stats)

      ;; Create CLI channel
      cli-ch (cli/create-cli-channel {:session-store store
                                      :bus msg-bus})

      ;; Create agent
      msg-agent (agent/create-agent {:bus msg-bus
                                     :opencode-url "http://127.0.0.1:9711"})]

  ;; Register and start
  (registry/register reg cli-ch)
  (ch/start cli-ch)
  (agent/start-agent msg-agent)
  (dispatch/start-outbound-dispatch (:outbound-chan msg-bus) reg stats))
```

### 3. Intelligent Task Routing

```clojure
(require '[anima-agent-clj.agent.intelligent-router :as router])

;; Create intelligent router
(def router (router/create-intelligent-router
              {:bus bus
               :register-defaults true}))

;; Start router
(router/start-router! router)

;; Send message with automatic classification
(router/send-message router "Write a function to sort a list" {})
;; => {:success true
;;     :task-type :code-generation
;;     :confidence 0.85
;;     :message "✨ 代码生成完成..."}
```

**Supported Task Types:**

| Type | Description | Keywords |
|------|-------------|----------|
| `:code-generation` | Code generation | write, create, implement |
| `:code-review` | Code review | review, check, analyze |
| `:code-debug` | Debugging | debug, fix, error |
| `:code-refactor` | Refactoring | refactor, optimize |
| `:web-search` | Web search | search, find |
| `:documentation` | Documentation | document, docs |
| `:data-analysis` | Data analysis | analyze, data |
| `:test-generation` | Test generation | test, spec |
| `:general-chat` | General chat | hello, hi |

## Channel System

### CLI Channel

```clojure
(require '[anima-agent-clj.channel.cli :as cli])

(def cli-ch (cli/create-cli-channel
             {:session-store store
              :bus msg-bus
              :prompt "ai> "}))

(ch/start cli-ch)
```

### RabbitMQ Channel

```clojure
(require '[anima-agent-clj.channel.rabbitmq :as rmq])

(def rmq-ch (rmq/create-rabbitmq-channel
             {:uri "amqp://guest:guest@localhost:5672"
              :exchange "opencode.messages"
              :queue "opencode.inbox"
              :bus msg-bus}))

(ch/start rmq-ch)
```

### Custom Channels

```clojure
(require '[anima-agent-clj.channel :as ch])

(defrecord MyChannel [config running?]
  ch/Channel
  (start [this] ...)
  (stop [this] ...)
  (send-message [this target message opts] ...)
  (channel-name [this] "my-channel")
  (health-check [this] @running?))
```

## Real-time Multi-Agent Demo

The library includes a real-time multi-agent system with non-blocking dialog:

```bash
# Start OpenCode server first (default: http://127.0.0.1:9711)
lein run -m realtime-multi-agent-demo

# Or with custom URL
lein run -m realtime-multi-agent-demo -- --url http://my-server:9711
```

**Example Interaction:**

```
> hi
  🏷️ AI Classification: :simple-chat (confidence: 95%)
💬 Hello! How can I help you today?

> Build a website with React frontend and Node.js backend
  🏷️ AI Classification: :complex-task (confidence: 95%)
🚀 Task started! ID: abc-123
Type 'status' to check progress

> status
📋 Task Status:
🔄 Build a website... - running (35%)
  ⏳ Project Setup - pending
  🔄 Database Design - running
  ...
```

## Core API

### Session Management

```clojure
;; List all sessions
(opencode/list-sessions client)

;; Create new session
(opencode/create-session client {:title "My Coding Session"})

;; Get session details
(opencode/get-session client session-id)

;; Update session
(opencode/update-session client session-id {:title "Updated Title"})

;; Delete session
(opencode/delete-session client session-id)
```

### Messaging

```clojure
;; Send prompt to AI
(opencode/send-prompt client session-id
                     {:text "Help me debug this code"}
                     "user-chat-assistant")

;; List messages in session
(opencode/list-messages client session-id)
```

### File Operations

```clojure
;; List files in project
(opencode/list-files client)

;; Read file content
(opencode/read-file client file-path)

;; Find text in files
(opencode/find-text client search-pattern)
```

## CLI Application

```bash
# Start interactive CLI
lein run -m anima-agent-clj.cli-main

# With custom options
lein run -m anima-agent-clj.cli-main -- --url http://my-server:9711
lein run -m anima-agent-clj.cli-main -- --prompt 'ai> '
```

### CLI Commands

| Command | Description |
|---------|-------------|
| `help` | Show available commands |
| `status` | Show session status |
| `history` | Show conversation history |
| `clear` | Clear conversation history |
| `exit/quit/:q` | Exit the CLI |

## Testing

```bash
lein test
lein test anima-agent-clj.core-test
```

## Building

```bash
lein deps
lein uberjar
```

## Namespace Structure

```
anima-agent-clj.cluster
├── core                    ; Cluster core
├── node                    ; Node management
├── coordinator             ; Cluster coordinator
└── topology                ; Topology management

anima-agent-clj.bus
├── core                    ; Bus core
├── inbound                 ; Inbound bus
├── outbound                ; Outbound bus
├── internal                ; Internal bus
└── control                 ; Control bus

anima-agent-clj.dispatcher
├── core                    ; Dispatcher core
├── router                  ; Routing strategies
├── balancer                ; Load balancing
└── circuit-breaker         ; Circuit breaker

anima-agent-clj.agent
├── core                    ; Agent core
├── core-agent              ; Core Agent
├── worker-agent            ; Worker Agent
├── parallel-pool           ; Parallel Pool
├── specialist-pool         ; Specialist Pool
├── task-classifier         ; Task Classification
├── intelligent-router      ; Intelligent Routing
└── ai-classifier           ; AI-powered Classification

anima-agent-clj.context
├── core                    ; Context core
├── manager                 ; Context Manager
├── storage                 ; Storage layer
└── compression             ; Compression/Summary

anima-agent-clj.cache
├── core                    ; Cache core
├── l1                      ; L1 Cache
├── l2                      ; L2 Cache
└── l3                      ; L3 Cache (Redis)

anima-agent-clj.pipeline
├── core                    ; Pipeline core
├── source                  ; Data source
├── transform               ; Transformer
└── sink                    ; Data sink

anima-agent-clj.channel
├── core                    ; Channel protocol
├── adapter                 ; Adapter
├── cli                     ; CLI Channel
├── rabbitmq                ; RabbitMQ Channel
├── http                    ; HTTP Channel
└── websocket               ; WebSocket Channel
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Run the test suite
6. Submit a pull request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

- Issues: [GitHub Issues](https://github.com/anima-agent-clj/anima-agent-clj/issues)
- Documentation: [API Reference](https://github.com/anima-agent-clj/anima-agent-clj/wiki)
- OpenCode Server: [opencode-server](https://github.com/sst/opencode)
