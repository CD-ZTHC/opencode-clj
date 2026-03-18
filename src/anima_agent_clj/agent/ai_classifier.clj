(ns anima-agent-clj.agent.ai-classifier
  "AI-powered Task Classifier for intelligent task routing.

   Uses fast AI models (e.g., Haiku) to classify user messages and determine
   whether they should be handled as:
   - Simple chat (direct response)
   - Complex task (background orchestration)

   Architecture:
   ┌────────────────────────────────────────────────────────────────────────┐
   │                         User Message                                    │
   │                              ↓                                          │
   │                      Quick Pattern Check                                │
   │                              ↓ (no match)                               │
   │                      Keyword Detection                                  │
   │                              ↓ (no clear match)                         │
   │                      AI Classifier Agent                                │
   │                   (Fast Model - Haiku)                                  │
   │                              ↓                                          │
   │            ┌─────────────────┴─────────────────┐                       │
   │            ↓                                   ↓                        │
   │     :simple-chat                        :complex-task                  │
   │     (Direct Response)                   (Background Task)              │
   └────────────────────────────────────────────────────────────────────────┘

   Usage:
   (def classifier (create-ai-classifier {:client opencode-client}))
   (classify-task classifier \"What can you do?\")
   => {:type :simple-chat :confidence 0.95 :reasoning \"...\"}

   Configuration:
   - :client        - OpenCode client (required)
   - :model         - Model to use (default: zhipuai-coding-plan/glm-4.7-flashx)
   - :cache-results - Cache classifications (default: true)"
  (:require
   [clojure.string :as str]
   [clojure.core.async :as async]
   [cheshire.core :as json]
   [anima-agent-clj.core :as opencode]
   [anima-agent-clj.sessions :as sessions]
   [anima-agent-clj.messages :as messages])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Classification Types
;; ══════════════════════════════════════════════════════════════════════════════

(def classification-types
  "Supported classification types."
  {:simple-chat
   {:description "General conversation, greetings, questions about capabilities"
    :handler :dialog-agent
    :priority 1}

   :complex-task
   {:description "Tasks requiring code generation, file operations, multi-step work"
    :handler :orchestrator
    :priority 2}

   :status-query
   {:description "Queries about task status or system state"
    :handler :status-handler
    :priority 0}

   :system-command
   {:description "System commands like exit, quit, help"
    :handler :system-handler
    :priority 0}})

;; ══════════════════════════════════════════════════════════════════════════════
;; AI Classifier Configuration
;; ══════════════════════════════════════════════════════════════════════════════

(def default-classification-prompt
  "You are a task classifier. Analyze the user's message and classify it.

RESPOND WITH ONLY A JSON OBJECT, NO OTHER TEXT.

Classification types:
1. \"simple-chat\" - General conversation, greetings, simple questions, asking about capabilities
2. \"complex-task\" - Requests to write code, create files, build features, fix bugs, implement things

Examples:
- \"hi\" → {\"type\": \"simple-chat\", \"confidence\": 0.95, \"reasoning\": \"greeting\"}
- \"What can you do?\" → {\"type\": \"simple-chat\", \"confidence\": 0.9, \"reasoning\": \"asking about capabilities\"}
- \"你好\" → {\"type\": \"simple-chat\", \"confidence\": 0.95, \"reasoning\": \"greeting in Chinese\"}
- \"你能做什么?\" → {\"type\": \"simple-chat\", \"confidence\": 0.9, \"reasoning\": \"asking about capabilities in Chinese\"}
- \"Write a function\" → {\"type\": \"complex-task\", \"confidence\": 0.95, \"reasoning\": \"code generation request\"}
- \"Create a REST API\" → {\"type\": \"complex-task\", \"confidence\": 0.95, \"reasoning\": \"implementation task\"}
- \"帮我写个函数\" → {\"type\": \"complex-task\", \"confidence\": 0.9, \"reasoning\": \"code generation request in Chinese\"}
- \"Build a website\" → {\"type\": \"complex-task\", \"confidence\": 0.95, \"reasoning\": \"multi-step implementation\"}
- \"请帮我创建一个网站\" → {\"type\": \"complex-task\", \"confidence\": 0.95, \"reasoning\": \"implementation request in Chinese\"}

User message: ")

(defrecord AIClassifier
           [id              ; UUID
            client          ; OpenCode client
            session-id      ; Session ID for classification
            model           ; Model to use
            prompt-template ; Classification prompt
            cache           ; atom<map> - cache for classifications
            status          ; atom<keyword> - :ready, :busy
            config])        ; Additional configuration

;; ══════════════════════════════════════════════════════════════════════════════
;; Cache Management
;; ══════════════════════════════════════════════════════════════════════════════

(defn- make-cache-key
  "Create a cache key from message."
  [message]
  (-> message
      (str/lower-case)
      (str/trim)
      (hash)))

(defn- get-cached-classification
  "Get cached classification if available."
  [classifier message]
  (when (get-in classifier [:config :cache-results true])
    (let [cache (:cache classifier)
          key (make-cache-key message)]
      (get @cache key))))

(defn- cache-classification!
  "Cache a classification result."
  [classifier message result]
  (when (get-in classifier [:config :cache-results true])
    (let [cache (:cache classifier)
          key (make-cache-key message)]
      (swap! cache assoc key result))))

;; ══════════════════════════════════════════════════════════════════════════════
;; AI Classification
;; ══════════════════════════════════════════════════════════════════════════════

(defn- parse-classification-response
  "Parse AI response into classification result."
  [response-text]
  (try
    ;; Try to extract JSON from response
    (let [;; Find JSON object in response
          json-match (re-find #"\{[^{}]*\}" response-text)
          parsed (when json-match
                   (json/parse-string json-match true))]
      (if (and parsed (:type parsed))
        (let [type-key (keyword (:type parsed))
              valid-type? (contains? classification-types type-key)]
          {:type (if valid-type? type-key :simple-chat)
           :confidence (or (:confidence parsed) 0.5)
           :reasoning (or (:reasoning parsed) "AI classification")})
        ;; Fallback: simple-chat if parsing fails
        {:type :simple-chat
         :confidence 0.6
         :reasoning "Failed to parse AI response, defaulting to chat"}))
    (catch Exception e
      {:type :simple-chat
       :confidence 0.5
       :reasoning (str "Parse error: " (.getMessage e))})))

(defn- extract-response-text
  "Extract text from OpenCode message response."
  [response]
  (cond
    ;; Extract from parts
    (:parts response)
    (let [parts (:parts response)
          text-parts (filter #(= "text" (:type %)) parts)]
      (str/join "\n" (map :text text-parts)))

    ;; Direct content
    (:content response)
    (:content response)

    ;; Fallback
    :else (str response)))

(defn- get-last-assistant-message
  "Get the last assistant message from messages list."
  [client session-id]
  (try
    (let [messages (opencode/list-messages client session-id)
          assistant-msgs (filter #(= "assistant" (get-in % [:info :role])) messages)]
      (last assistant-msgs))
    (catch Exception _
      nil)))

(defn- wait-for-response
  "Wait for AI response - no timeout, waits indefinitely."
  [client session-id]
  (loop []
    (Thread/sleep 500)
    (if-let [msg (get-last-assistant-message client session-id)]
      {:status :success :message msg}
      (recur))))

(defn classify-task-async
  "Classify a task using AI asynchronously.
   Returns a channel that will receive the classification result.
   No client-side timeout - waits for server response."
  [classifier message]
  (async/go
    ;; Check cache first
    (if-let [cached (get-cached-classification classifier message)]
      cached
      ;; Perform AI classification
      (try
        (let [{:keys [client session-id prompt-template model]} classifier
              full-prompt (str prompt-template (pr-str message))

              ;; Send prompt to OpenCode with fast model
              _ (messages/send-prompt client session-id
                                      {:parts [{:type "text" :text full-prompt}]}
                                      {:model (or model "zhipuai-coding-plan/glm-4.7-flashx")})

              ;; Wait for response - no timeout
              result (wait-for-response client session-id)]

          (let [response-text (extract-response-text (:message result))
                classification (parse-classification-response response-text)]
            ;; Cache the result
            (cache-classification! classifier message classification)
            classification))

        (catch Exception e
          {:type :simple-chat
           :confidence 0.5
           :reasoning (str "Classification error: " (.getMessage e))
           :error? true})))))

(defn classify-task
  "Classify a task using AI synchronously.
   Blocks until classification is complete."
  [classifier message]
  (let [result-ch (classify-task-async classifier message)
        result (async/<!! result-ch)]
    result))

;; ══════════════════════════════════════════════════════════════════════════════
;; Quick Pattern-based Classification
;; ══════════════════════════════════════════════════════════════════════════════

(def quick-patterns
  "Quick patterns for immediate classification without AI."
  {:simple-chat
   [#"(?i)^(hi|hello|hey|good\s+(morning|afternoon|evening))$"
    #"^(你好|您好|嗨)$"
    #"(?i)^(thanks?|thank\s+you)$"
    #"^(谢谢|多谢)$"]

   :status-query
   [#"(?i)^status$"
    #"^(任务状态|进度|查询)$"]

   :system-command
   [#"(?i)^(exit|quit|bye)$"
    #"^(退出|再见)$"]

   ;; Complex task patterns - keywords that clearly indicate implementation work
   :complex-task
   [#"(?i).*(write|create|build|implement|develop|generate|make|design)\s+(a|an|the|some)?\s*(function|class|module|component|api|website|app|system|service|feature)"
    #"(?i).*(help|please)\s+(me\s+)?(write|create|build|implement|develop|generate|make)"
    #"(?i).*(fix|debug|solve|resolve)\s+(the\s+)?(bug|error|issue|problem)"
    #"(?i).*(refactor|optimize|improve|update)\s+(the\s+)?(code|function|class)"
    #".*(写|创建|建|实现|开发|生成|制作|设计).*(功能|函数|类|模块|组件|网站|应用|系统|服务|API)"
    #".*(帮我|请).*(写|创建|建|实现|开发|生成|制作|设计)"
    #".*(修复|调试|解决).*(bug|错误|问题)"
    #".*(重构|优化|改进).*(代码)"]})

(defn quick-classify
  "Perform quick pattern-based classification.
   Returns classification map or nil if no pattern matches."
  [message]
  (let [trimmed (str/trim message)]
    (some (fn [[type patterns]]
            (when (some #(re-find % trimmed) patterns)
              {:type type
               :confidence 1.0
               :reasoning "Pattern match"
               :quick? true}))
          quick-patterns)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Combined Classification
;; ══════════════════════════════════════════════════════════════════════════════

(defn smart-classify
  "Smart classification: quick patterns first, then AI if needed.
   This is the recommended entry point for classification."
  ([classifier message]
   (smart-classify classifier message nil))
  ([classifier message opts]
   (or ;; 1. Try quick patterns first (includes complex-task patterns)
    (quick-classify message)
       ;; 2. Use AI classification
    (if (:skip-ai? opts)
      {:type :simple-chat
       :confidence 0.5
       :reasoning "AI skipped"}
      (if classifier
        (classify-task classifier message)
        {:type :simple-chat
         :confidence 0.5
         :reasoning "No classifier available"})))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Session Management
;; ══════════════════════════════════════════════════════════════════════════════

(defn- ensure-session
  "Ensure classifier has a valid session."
  [classifier]
  (if (:session-id classifier)
    classifier
    (try
      (let [session (sessions/create-session (:client classifier))
            session-id (:id session)]
        (assoc classifier :session-id session-id))
      (catch Exception e
        (println "Warning: Failed to create session for classifier:" (.getMessage e))
        classifier))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-ai-classifier
  "Create an AI-powered task classifier.

   Options:
     :client          - OpenCode client (required)
     :model           - Model to use (default: zhipuai-coding-plan/glm-4.7-flashx)
     :prompt-template - Custom classification prompt
     :cache-results   - Enable result caching (default: true)

   Example:
   (def classifier (create-ai-classifier
                     {:client (opencode/client \"http://127.0.0.1:9711\")
                      :model \"zhipuai-coding-plan/glm-4.7-flashx\"}))"
  [{:keys [client model prompt-template cache-results]
    :or {model "zhipuai-coding-plan/glm-4.7-flashx"
         prompt-template default-classification-prompt
         cache-results true}}]
  (when-not client
    (throw (ex-info "OpenCode client is required" {})))
  (let [classifier (->AIClassifier
                    (str (UUID/randomUUID))
                    client
                    nil  ; session-id will be created on first use
                    model
                    prompt-template
                    (atom {})
                    (atom :ready)
                    {:cache-results cache-results})]
    (ensure-session classifier)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Utility Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn clear-cache!
  "Clear the classification cache."
  [classifier]
  (reset! (:cache classifier) {}))

(defn classifier-status
  "Get classifier status."
  [classifier]
  {:id (:id classifier)
   :status @(:status classifier)
   :session-id (:session-id classifier)
   :model (:model classifier)
   :cache-size (count @(:cache classifier))})
