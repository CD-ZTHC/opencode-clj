# OpenCode Clojure Client

A comprehensive Clojure client library for interacting with the [opencode-server](https://github.com/sst/opencode) REST API. This library provides idiomatic Clojure wrappers for all opencode-server functionality, making it easy to integrate AI-powered coding assistance into your Clojure applications.

## Features

- **Full API Coverage**: Complete implementation of all opencode-server endpoints
- **Idiomatic Clojure**: Clean, functional API design following Clojure best practices
- **Macro Support**: Convenient macros for common operations
- **Session Management**: Create, manage, and interact with coding sessions
- **Message Handling**: Send prompts and receive AI responses
- **File Operations**: Read, write, and manage project files
- **Configuration**: Dynamic configuration management
- **Async Support**: Asynchronous operations for better performance

## Installation

Add the following dependency to your `project.clj`:

```clojure
[opencode-clj "0.1.0-SNAPSHOT"]
```

Or in your `deps.edn`:

```clojure
opencode-clj {:mvn/version "0.1.0-SNAPSHOT"}
```

## Quick Start

### 1. Create a Client

```clojure
(ns my-app.core
  (:require [opencode-clj.core :as opencode]))

;; Create a client connected to your opencode-server
(def client (opencode/client "http://127.0.0.1:9711"))
```

### 2. Using Macros for Convenience

```clojure
(ns my-app.core
  (:require [opencode-clj.macros.core :as macros]))

;; Define a client using the macro
(macros/defopencode my-client "http://127.0.0.1:9711")
```

### 3. Basic Conversation Example

```clojure
(ns my-app.core
  (:require [opencode-clj.core :as opencode]
            [opencode-clj.macros.core :as macros]))

(macros/defopencode test-client "http://127.0.0.1:9711")

(defn test-basic-conversation []
  ;; Create a session
  (let [session (opencode/create-session test-client {:title "Test Conversation"})]
    (println "Created session:" (:id session))

    ;; Send a prompt
    (let [response (opencode/send-prompt test-client
                                        (:id session)
                                        {:text "Hello, can you help me write a Python hello world function?"}
                                        "user-chat-assistant")]
      (println "Response:" response))

    ;; Get message history
    (let [messages (opencode/list-messages test-client (:id session))]
      (println "Message count:" (count messages)))

    ;; Clean up
    (opencode/delete-session test-client (:id session))))
```

## Core API

### Client Management

```clojure
;; Create client with options
(def client (opencode/client "http://127.0.0.1:9711"
                            {:directory "/path/to/project"
                             :http-opts {:timeout 5000}}))
```

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

;; Fork session
(opencode/fork-session client session-id)

;; Share session
(opencode/share-session client session-id)
```

### Messaging

```clojure
;; Send prompt to AI
(opencode/send-prompt client session-id
                     {:text "Help me debug this code"}
                     "user-chat-assistant")

;; List messages in session
(opencode/list-messages client session-id)

;; Execute command
(opencode/execute-command client session-id command)

;; Run shell command
(opencode/run-shell-command client session-id command)
```

### File Operations

```clojure
;; List files in project
(opencode/list-files client)

;; Read file content
(opencode/read-file client file-path)

;; Find text in files
(opencode/find-text client search-pattern)

;; Find files by pattern
(opencode/find-files client file-pattern)

;; Find symbols
(opencode/find-symbols client symbol-pattern)
```

### Configuration

```clojure
;; Get current configuration
(opencode/get-config client)

;; Update configuration
(opencode/update-config client new-config)

;; List available providers
(opencode/list-providers client)

;; List available commands
(opencode/list-commands client)

;; List available agents
(opencode/list-agents client)
```

## Advanced Usage

### Using Chatbot Macros

```clojure
(ns my-app.core
  (:require [opencode-clj.macros.chatbot :as chatbot]))

;; Define a chatbot with custom behavior
(chatbot/defchatbot my-bot "http://127.0.0.1:9711"
  :system-prompt "You are a helpful coding assistant specialized in Clojure."
  :temperature 0.7)
```

### Async Operations

```clojure
(ns my-app.core
  (:require [opencode-clj.macros.async :as async]
            [clojure.core.async :refer [<!!]]))

;; Perform async operations
(let [result-chan (async/send-prompt-async client session-id prompt)]
  (println "Response:" (<!! result-chan)))
```

### DSL for Complex Workflows

```clojure
(ns my-app.core
  (:require [opencode-clj.macros.dsl :as dsl]))

;; Define complex workflows
(dsl/defworkflow code-review-workflow
  [client session-id file-path]
  (dsl/send-prompt "Please review this code for potential issues")
  (dsl/wait-for-response)
  (dsl/send-prompt "Can you suggest improvements?")
  (dsl/wait-for-response))
```

## Testing

Run the test suite:

```bash
lein test
```

Run specific tests:

```bash
lein test opencode-clj.core-test
lein test :only opencode-clj.core-test/test-client-creation
```

## Building

Build the project:

```bash
lein deps
lein uberjar
```

## Configuration

The library supports various configuration options:

- `:base-url` - OpenCode server URL (required)
- `:directory` - Project directory path
- `:http-opts` - HTTP client options (timeout, headers, etc.)

## Error Handling

All functions return either success maps or throw exceptions:

```clojure
(try
  (let [response (opencode/send-prompt client session-id prompt)]
    (if (:success response)
      (println "Success:" response)
      (println "Error:" (:error response))))
  (catch Exception e
    (println "Exception:" (.getMessage e))))
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

- Issues: [GitHub Issues](https://github.com/CD-ZTHC/opencode-clj/issues)
- Documentation: [API Reference](https://github.com/CD-ZTHC/opencode-clj/wiki)
- OpenCode Server: [opencode-server](https://github.com/sst/opencode)
