(ns realtime-multi-agent-demo
  "Real-time Multi-Agent Demo with OpenCode Server Integration.

   Architecture:
   ┌──────────────────────────────────────────────────────────────────────────┐
   │                          User Interface (CLI)                            │
   │                                 ↓                                        │
   │                        Dialog Agent (Main)                               │
   │                    ┌────────────────────────┐                            │
   │                    │  - Handles user chat    │                            │
   │                    │  - Queries task status  │                            │
   │                    │  - Non-blocking I/O     │                            │
   │                    └────────────────────────┘                            │
   │                                 ↓                                        │
   │                    Task Orchestrator (Background)                        │
   │                    ┌────────────────────────┐                            │
   │                    │  - Task decomposition   │                            │
   │                    │  - Parallel execution   │                            │
   │                    │  - Status tracking      │                            │
   │                    └────────────────────────┘                            │
   │                                 ↓                                        │
   │                    Specialist Pool (OpenCode API)                        │
   │            ┌──────────┬──────────┬──────────┬──────────┐                │
   │            │ Frontend │ Backend  │ Database │  Testing │                │
   │            │ Agent    │ Agent    │ Agent    │  Agent   │                │
   │            └──────────┴──────────┴──────────┴──────────┘                │
   │                                 ↓                                        │
   │                      OpenCode Server API                                 │
   └──────────────────────────────────────────────────────────────────────────┘

   Key Features:
   - Dialog agent remains responsive while background agents work
   - User can query task status at any time
   - Real OpenCode API calls for each specialist
   - Parallel task execution

   Run: lein run -m realtime-multi-agent-demo"
  (:require
   [clojure.core.async :as async]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [anima-agent-clj.core :as opencode]
   [anima-agent-clj.bus :as bus]
   [anima-agent-clj.channel :as channel]
   [anima-agent-clj.agent.orchestrator :as orchestrator]
   [anima-agent-clj.agent.specialist-pool :as specialist-pool]
   [anima-agent-clj.agent.ai-classifier :as ai-classifier]
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]
           [java.text SimpleDateFormat]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Logging Utilities
;; ══════════════════════════════════════════════════════════════════════════════

(defn get-timestamp []
  (let [df (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss.SSS")]
    (.format df (Date.))))

(def ^:dynamic *log-level* :info)
(def log-levels {:debug 0, :info 1, :warn 2, :error 3})

(defn log [level module msg & [ex]]
  (when (>= (get log-levels level 1) (get log-levels *log-level* 1))
    (let [ts (get-timestamp)
          level-str (str/upper-case (name level))]
      (if ex
        (println (format "[%s] [%-5s] [%s] %s\nException: %s" ts level-str module msg (.getMessage ex)))
        (println (format "[%s] [%-5s] [%s] %s" ts level-str module msg))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; TTL bounded cache
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-ttl-cache
  "Creates an atom-based cache with TTL and max-size constraints."
  [config]
  (let [{:keys [max-size ttl-ms] :or {max-size 1000 ttl-ms (* 60 60 1000)}} config]
    (atom {:max-size max-size
           :ttl-ms ttl-ms
           :entries {}})))

(defn cleanup-cache! [cache-atom]
  (let [now (System/currentTimeMillis)
        state @cache-atom
        ttl (:ttl-ms state)
        max-s (:max-size state)
        entries (:entries state)
        valid-entries (into {} (filter (fn [[_ v]] (> (+ (:timestamp v) ttl) now)) entries))
        pruned-entries (if (> (count valid-entries) max-s)
                         (into {} (take max-s (sort-by #(-> % val :timestamp) > valid-entries)))
                         valid-entries)]
    (swap! cache-atom assoc :entries pruned-entries)
    cache-atom))

(defn cache-put! [cache-atom k v]
  (swap! cache-atom assoc-in [:entries k] {:val v :timestamp (System/currentTimeMillis)})
  (cleanup-cache! cache-atom)
  v)

(defn cache-get [cache-atom k]
  (get-in @cache-atom [:entries k :val]))

(defn cache-vals [cache-atom]
  (map :val (vals (:entries @cache-atom))))

(defn clear-cache! [cache-atom]
  (swap! cache-atom assoc :entries {}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Status Store - Global task tracking for status queries
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord TaskStatus
           [id ; UUID
            name ; Task name
            type ; Task type
            status ; :pending, :running, :completed, :failed
            progress ; 0-100
            started-at ; Date
            completed-at ; Date
            result ; Result data
            error ; Error message
            subtasks]) ; Subtask status list

(defonce task-status-store (create-ttl-cache {:max-size 100 :ttl-ms (* 24 60 60 1000)}))

(defn create-task-status
  "Create a new task status entry."
  [{:keys [id name type subtasks]
    :or {id (str (UUID/randomUUID))}}]
  (let [status (->TaskStatus
                id
                name
                type
                :pending
                0
                (Date.)
                nil
                nil
                nil
                (or subtasks []))]
    (cache-put! task-status-store id status)
    status))

(defn update-task-status!
  "Update task status."
  [task-id updates]
  (let [task (cache-get task-status-store task-id)]
    (when task
      (cache-put! task-status-store task-id (merge task updates)))))

(defn get-task-status
  "Get task status by ID."
  [task-id]
  (cache-get task-status-store task-id))

(defn list-active-tasks
  "List all active (pending or running) tasks."
  []
  (filter #(#{:pending :running} (:status %))
          (cache-vals task-status-store)))

(defn list-all-tasks
  "List all tasks."
  []
  (cache-vals task-status-store))

(defn clear-task-store!
  "Clear all tasks from the store."
  []
  (clear-cache! task-status-store))

;; ══════════════════════════════════════════════════════════════════════════════
;; OpenCode API Client Wrapper
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-opencode-client
  "Create OpenCode client from configuration."
  [{:keys [base-url]
    :or {base-url "http://127.0.0.1:9711"}}]
  (opencode/client base-url))

(defn make-session-id
  "Create a valid session ID (must start with 'ses')."
  ([] (make-session-id nil))
  ([suffix]
   (str "ses" (UUID/randomUUID) (when suffix (str "-" suffix)))))

(defonce session-cache (create-ttl-cache {:max-size 500 :ttl-ms (* 12 60 60 1000)}))

(defn ensure-session
  "Ensure a session exists, create if needed.
   Returns the actual session ID (may be different from requested if created new).
   Caches sessions to avoid repeated lookups."
  [client requested-session-id]
  (if-let [cached-id (cache-get session-cache requested-session-id)]
    cached-id
    ;; Session doesn't exist in cache, create a new one
    ;; (OpenCode server generates the ID)
    (let [new-session (opencode/create-session client {})
          actual-id (:id new-session)]
      (cache-put! session-cache requested-session-id actual-id)
      actual-id)))

(defn send-opencode-prompt
  "Send a prompt to OpenCode server using the SDK.
   After sending, polls for the AI response with global timeout."
  [client session-id prompt _agent-name & [{:keys [timeout-ms poll-ms] :or {timeout-ms 60000 poll-ms 1000}}]]
  (try
    (let [actual-session-id (ensure-session client session-id)
          start-time (System/currentTimeMillis)]
      (log :info "opencode" (str "📤 发送prompt到session: " actual-session-id "\n📦 消息: " prompt))
      (opencode/send-prompt client actual-session-id {:text prompt} nil)
      (log :info "opencode" "⏳ 等待AI响应...")
      (Thread/sleep 500)
      (loop []
        (let [now (System/currentTimeMillis)]
          (if (> (- now start-time) timeout-ms)
            (do
              (log :error "opencode" "❌ AI响应超时 (Timeout)" nil)
              {:status :error :error "AI response timeout"})
            (let [msgs (try
                         (opencode/list-messages client actual-session-id)
                         (catch Exception e
                           (log :warn "opencode" "获取消息失败" e)
                           []))
                  last-assistant (some #(when (= "assistant" (get-in % [:info :role])) %) (reverse msgs))]
              (if last-assistant
                (do
                  (log :info "opencode" (str "📥 收到 " (count msgs) " 条消息"))
                  (let [text-parts (filter #(= "text" (:type %)) (:parts last-assistant))
                        response-text (str/join "\n" (map :text text-parts))]
                    (log :info "opencode" (str "✅ AI响应: " (or response-text "(无文本内容)")))
                    {:status :success :result last-assistant}))
                (do
                  (Thread/sleep poll-ms)
                  (recur))))))))
    (catch Exception e
      (log :error "opencode" "❌ send-opencode-prompt 错误" e)
      {:status :error :error (.getMessage e)})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Real OpenCode Specialists
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-opencode-specialist-handler
  "Create a specialist handler that calls OpenCode server.

   The handler will:
   1. Create or use existing session
   2. Send the task prompt to OpenCode
   3. Return the result"
  [client session-id-base specialist-name prompt-template]
  (fn [task]
    (let [session-id (str session-id-base "-" (name specialist-name))
          description (get-in task [:payload :description] (get-in task [:payload :name] "Task"))
          prompt (format prompt-template description)]
      (println (str "  🔄 " specialist-name " 正在处理: " description))
      (let [result (send-opencode-prompt client session-id prompt (str specialist-name))]
        (if (= :success (:status result))
          (do
            (println (str "  ✅ " specialist-name " 完成"))
            {:status :success
             :result {:specialist specialist-name
                      :response (:result result)
                      :session-id session-id}})
          (do
            (println (str "  ❌ " specialist-name " 失败: " (:error result)))
            {:status :error
             :error (:error result)}))))))

(defn register-opencode-specialists!
  "Register specialists that call real OpenCode server."
  [orchestrator client base-session-id]
  (let [pool (:specialist-pool orchestrator)]

    ;; Project Setup Specialist
    ;; Note: :project-specialist capability matches the specialist-type in orchestrator rules
    (specialist-pool/register-specialist!
     pool
     {:id "project-setup-specialist"
      :name "Project Setup Specialist"
      :type :project-specialist
      :capabilities #{:project-specialist :setup :project-init :configuration}
      :handler (create-opencode-specialist-handler
                client base-session-id
                "Project Setup"
                "Initialize a new project with the following requirements: %s.
                 Create project structure, configuration files, and basic setup.")})

    ;; Database Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "database-specialist"
      :name "Database Specialist"
      :type :database-specialist
      :capabilities #{:database-specialist :database :schema-design :migrations}
      :handler (create-opencode-specialist-handler
                client base-session-id
                "Database"
                "Design database schema for: %s.
                 Create tables, relationships, and migration files.")})

    ;; Backend Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "backend-specialist"
      :name "Backend Specialist"
      :type :backend-specialist
      :capabilities #{:backend-specialist :backend :api :server}
      :handler (create-opencode-specialist-handler
                client base-session-id
                "Backend"
                "Implement backend API for: %s.
                 Create endpoints, business logic, and data models.")})

    ;; Frontend Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "frontend-specialist"
      :name "Frontend Specialist"
      :type :frontend-specialist
      :capabilities #{:frontend-specialist :frontend :ui :react}
      :handler (create-opencode-specialist-handler
                client base-session-id
                "Frontend"
                "Build frontend UI components for: %s.
                 Create React components, styles, and user interactions.")})

    ;; Integration Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "integration-specialist"
      :name "Integration Specialist"
      :type :integration-specialist
      :capabilities #{:integration-specialist :integration :api-client}
      :handler (create-opencode-specialist-handler
                client base-session-id
                "Integration"
                "Integrate frontend with backend for: %s.
                 Create API clients, data hooks, and state management.")})

    ;; Test Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "test-specialist"
      :name "Test Specialist"
      :type :test-specialist
      :capabilities #{:test-specialist :testing :unit-test :integration-test}
      :handler (create-opencode-specialist-handler
                client base-session-id
                "Testing"
                "Write tests for: %s.
                 Create unit tests and integration tests.")})

    orchestrator))

;; ══════════════════════════════════════════════════════════════════════════════
;; Dialog Agent - Non-blocking user interaction
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord DialogAgent
           [id ; Agent ID
            client ; OpenCode client
            session-id ; Dialog session ID
            orchestrator ; Task orchestrator
            ai-classifier ; AI-powered task classifier
            status ; atom<keyword>
            message-loop ; atom - input message loop
            input-ch ; User input channel
            output-ch ; Response output channel
            background-tasks]) ; atom<map> - task-id -> channel

(def status-commands
  #{"status" "任务状态" "task status" "进度" "progress" "list tasks"})

(defn is-status-query?
  "Check if the message is a status query."
  [message]
  (some #(str/includes? (str/lower-case message) %) status-commands))

(defn format-subtask-status
  "Format subtask status for display."
  [subtask]
  (let [status-icon (case (:status subtask)
                      :pending "⏳"
                      :running "🔄"
                      :completed "✅"
                      :failed "❌"
                      "❓")]
    (format "    %s %s (%s)"
            status-icon
            (:name subtask)
            (name (:status subtask)))))

(defn format-task-status
  "Format task status for display."
  [task]
  (let [status-icon (case (:status task)
                      :pending "⏳"
                      :running "🔄"
                      :completed "✅"
                      :failed "❌"
                      "❓")
        progress (if (= :completed (:status task))
                   "100%"
                   (str (:progress task 0) "%"))
        subtasks (:subtasks task)
        subtask-info (when (and subtasks (seq subtasks))
                       (str "\n" (str/join "\n" (map format-subtask-status subtasks))))]
    (str (format "%s %s - %s (%s)"
                status-icon
                (:name task)
                (name (:status task))
                progress)
        (or subtask-info ""))))

(defn handle-status-query
  "Handle a status query from user."
  []
  (let [tasks (list-all-tasks)]
    (if (empty? tasks)
      "📋 当前没有任务"
      (str "📋 任务状态:\n"
           (str/join "\n" (map format-task-status tasks))))))

(defn update-subtask-status!
  "Update status of a specific subtask."
  [task-id subtask-name new-status]
  (let [task (cache-get task-status-store task-id)]
    (when task
      (let [updated-subtasks (map (fn [st]
                                    (if (= (:name st) subtask-name)
                                      (assoc st :status new-status)
                                      st))
                                  (:subtasks task))]
        (cache-put! task-status-store task-id (assoc task :subtasks updated-subtasks))))))

(defn execute-background-task
  "Execute a task in the background, updating status as it progresses.
   Returns a channel that will receive the final result."
  [orchestrator message task-id]
  (async/go
    (try
      ;; Update progress
      (update-task-status! task-id {:status :running :progress 5})
      (println (str "  📋 分解任务: " message))

      ;; Decompose task
      (let [trace-id (str (UUID/randomUUID))
            plan (orchestrator/decompose-task message trace-id)
            subtask-count (count (:subtasks plan))]

        (println (str "  📊 创建了 " subtask-count " 个子任务: "
                  (str/join ", " (map #(:name (second %)) (:subtasks plan)))))

        ;; Update with subtask info
        (update-task-status! task-id
                             {:progress 10
                              :subtasks (map (fn [[_ st]]
                                               {:name (:name st)
                                                :type (:type st)
                                                :specialist (:specialist-type st)
                                                :status :pending})
                                             (:subtasks plan))})

        ;; Execute plan
        (let [exec-ch (orchestrator/execute-plan! orchestrator plan)]

          ;; Poll for completion with progress updates
          (loop []
            (let [timeout-ch (async/timeout 2000)
                  [result port] (async/alts! [exec-ch timeout-ch])]
              (if (= port timeout-ch)
                ;; Timeout - update progress and subtask statuses
                (let [progress-map @(:progress plan)
                      completed (:completed progress-map 0)
                      total (:total progress-map 1)
                      running (:running progress-map 0)
                      pct (int (+ 10 (* 80 (/ completed total))))]

                  ;; Update subtask statuses from plan
                  (doseq [[_ st] (:subtasks plan)]
                    (let [st-status @(:status st)]
                      (update-subtask-status! task-id (:name st) st-status)))

                  (update-task-status! task-id {:progress pct})
                  (when (> running 0)
                    (println (str "  🔄 执行中... " completed "/" total " 完成")))
                  (recur))

                ;; Got result - task completed
                (do
                  ;; Final subtask status update
                  (doseq [[_ st] (:subtasks plan)]
                    (let [st-status @(:status st)]
                      (update-subtask-status! task-id (:name st) st-status)))

                  (update-task-status! task-id
                                       {:status :completed
                                        :progress 100
                                        :completed-at (Date.)
                                        :result result})
                  (println (str "  ✅ 任务完成! " (count (:subtasks plan)) " 个子任务已执行"))
                  (when-let [results (:results result)]
                    (doseq [[name res] results]
                      (println (str "    📦 " name ": "
                                  (if (= :success (:status res)) "成功" "失败")))))
                  result))))))

      (catch Exception e
        (update-task-status! task-id
                             {:status :failed
                              :error (.getMessage e)})
        (println (str "  ❌ 任务失败: " (.getMessage e)))
        {:status :error
         :error (.getMessage e)}))))

(defn handle-background-task
  "Start a background task and return immediately with task ID."
  [dialog-agent message]
  (let [orchestrator (:orchestrator dialog-agent)
        ;; Create task status
        task-status (create-task-status
                     {:name message
                      :type :complex-task})
        task-id (:id task-status)]

    ;; Start background execution
    (let [result-ch (execute-background-task orchestrator message task-id)]
      ;; Store the background task channel
      (swap! (:background-tasks dialog-agent) assoc task-id result-ch)

      (format "🚀 任务已开始执行! ID: %s\n输入 'status' 查看进度" task-id))))

(defn handle-dialog-message
  "Handle a user message through the dialog agent."
  [dialog-agent message]
  (let [message (str/trim message)
        classifier (:ai-classifier dialog-agent)]

    (cond
      ;; Status query - quick check first
      (is-status-query? message)
      (handle-status-query)

      ;; Exit command - quick check first
      (or (= "exit" (str/lower-case message))
          (= "quit" (str/lower-case message))
          (= "退出" message))
      {:exit true :message "再见!"}

      ;; Use AI classifier for intelligent routing
      :else
      (if classifier
        ;; Use AI classification
        (let [classification (ai-classifier/smart-classify classifier message)
              task-type (:type classification)
              confidence (:confidence classification)]
          (println (str "  🏷️ AI分类: " task-type " (置信度: " (int (* 100 confidence)) "%)"))
          (if (and (= :complex-task task-type)
                   (> confidence 0.7))
            ;; Complex task with high confidence -> background
            (handle-background-task dialog-agent message)
            ;; Everything else -> direct chat
            (let [result (send-opencode-prompt
                          (:client dialog-agent)
                          (:session-id dialog-agent)
                          message
                          "dialog-agent")]
              (if (= :success (:status result))
                (let [msg-data (:result result)
                      text-parts (filter #(= "text" (:type %)) (:parts msg-data))
                      response-text (str/join "\n" (map :text text-parts))]
                  (str "💬 " (or response-text "处理完成")))
                (str "❌ 错误: " (:error result))))))

        ;; Fallback: no classifier available, use direct chat
        (let [result (send-opencode-prompt
                      (:client dialog-agent)
                      (:session-id dialog-agent)
                      message
                      "dialog-agent")]
          (if (= :success (:status result))
            (let [msg-data (:result result)
                  text-parts (filter #(= "text" (:type %)) (:parts msg-data))
                  response-text (str/join "\n" (map :text text-parts))]
              (str "💬 " (or response-text "处理完成")))
            (str "❌ 错误: " (:error result))))))))

(defn start-dialog-agent!
  "Start the dialog agent."
  [dialog-agent]
  (when (= :stopped @(:status dialog-agent))
    (reset! (:status dialog-agent) :running)

    ;; Start message processing loop
    (reset! (:message-loop dialog-agent)
            (async/go-loop []
              (when (= :running @(:status dialog-agent))
                (if-let [message (async/<! (:input-ch dialog-agent))]
                  (let [response (handle-dialog-message dialog-agent message)]
                    (if (:exit response)
                      (do
                        (async/>! (:output-ch dialog-agent) (:message response))
                        (reset! (:status dialog-agent) :stopped))
                      (do
                        (async/>! (:output-ch dialog-agent)
                                  (if (string? response) response (str response)))
                        (recur))))
                  (recur))))))

  dialog-agent)

(defn stop-dialog-agent!
  "Stop the dialog agent."
  [dialog-agent]
  (when (= :running @(:status dialog-agent))
    (reset! (:status dialog-agent) :stopping)
    (when-let [loop @(:message-loop dialog-agent)]
      (async/close! loop))
    (reset! (:status dialog-agent) :stopped))
  dialog-agent)

(defn create-dialog-agent
  "Create a new dialog agent."
  [{:keys [client session-id orchestrator ai-classifier input-ch output-ch]
    :or {session-id (make-session-id "dialog")
         input-ch (async/chan 100)
         output-ch (async/chan 100)}}]
  (->DialogAgent
   (str (UUID/randomUUID))
   client
   session-id
   orchestrator
   ai-classifier
   (atom :stopped)
   (atom nil)
   input-ch
   output-ch
   (atom {})))

;; ══════════════════════════════════════════════════════════════════════════════
;; CLI Channel - Direct user interaction
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord CLIChannel
           [dialog-agent
            status
            reader-loop
            writer-loop])

(defn start-cli-channel!
  "Start the CLI channel for user interaction."
  [cli-channel]
  (when (= :stopped @(:status cli-channel))
    (reset! (:status cli-channel) :running)

    (let [dialog-agent (:dialog-agent cli-channel)
          input-ch (:input-ch dialog-agent)
          output-ch (:output-ch dialog-agent)]

      ;; Start reader loop (user input -> input channel)
      (reset! (:reader-loop cli-channel)
              (async/go-loop []
                (when (= :running @(:status cli-channel))
                  (print "\n> ")
                  (flush)
                  (when-let [line (read-line)]
                    (async/>! input-ch line)
                    (recur)))))

      ;; Start writer loop (output channel -> display)
      (reset! (:writer-loop cli-channel)
              (async/go-loop []
                (when (= :running @(:status cli-channel))
                  (when-let [response (async/<! output-ch)]
                    (println (str "\n" response))
                    (recur)))))))

  cli-channel)

(defn stop-cli-channel!
  "Stop the CLI channel."
  [cli-channel]
  (when (= :running @(:status cli-channel))
    (reset! (:status cli-channel) :stopping)
    (when-let [loop @(:reader-loop cli-channel)]
      (async/close! loop))
    (when-let [loop @(:writer-loop cli-channel)]
      (async/close! loop))
    (reset! (:status cli-channel) :stopped))
  cli-channel)

(defn create-cli-channel
  "Create a CLI channel."
  [dialog-agent]
  (->CLIChannel
   dialog-agent
   (atom :stopped)
   (atom nil)
   (atom nil)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo System
;; ══════════════════════════════════════════════════════════════════════════════

(defn print-banner []
  (println (str "\n" (apply str (repeat 70 "═"))))
  (println "       实时多Agent协作系统 - OpenCode Server 集成")
  (println "       Real-time Multi-Agent System with OpenCode Integration")
  (println (apply str (repeat 70 "═")))
  (println "\n  系统架构:")
  (println "    - Dialog Agent: 前台对话，不阻塞")
  (println "    - Background Orchestrator: 后台任务编排")
  (println "    - Specialist Pool: 专家池，调用OpenCode API")
  (println "    - Status Store: 任务状态追踪")
  (println "\n  可用命令:")
  (println "    - 输入任意消息进行对话")
  (println "    - 输入 'status' 查看后台任务状态")
  (println "    - 输入 'exit' 或 'quit' 退出")
  (println "\n  示例任务:")
  (println "    - 'Build a website with React frontend and Node.js backend'")
  (println "    - 'Create a REST API for a blog system'")
  (println "    - '帮我写一个用户管理系统'")
  (println (apply str (repeat 70 "═"))))

(defn run-demo
  "Run the real-time multi-agent demo."
  [{:keys [opencode-url classifier-model]
    :or {opencode-url "http://127.0.0.1:9711"
         classifier-model "claude-3-5-haiku-latest"}}]
  (print-banner)

  ;; Check OpenCode server connection
  (println "\n📡 连接 OpenCode Server...")
  (let [client (create-opencode-client {:base-url opencode-url})]

    ;; Try to list sessions to verify connection
    (try
      (opencode/list-sessions client)
      (println "✅ OpenCode Server 连接成功")
      (catch Exception e
        (println (str "❌ 无法连接到 OpenCode Server: " opencode-url))
        (println (str "   错误: " (.getMessage e)))
        (println "\n请确保 OpenCode Server 正在运行，或使用 --url 参数指定地址")
        (System/exit 1)))

    ;; Create infrastructure
    (println "\n🔧 初始化系统组件...")

    (let [metrics-collector (metrics/create-collector {:id "realtime-demo"})
          base-session-id (make-session-id "demo")

          ;; Create AI classifier for intelligent task routing
          _ (println "   🤖 创建AI分类器 (模型: " classifier-model ")...")
          ai-classifier (try
                          (ai-classifier/create-ai-classifier
                           {:client client
                            :model classifier-model
                            :timeout-ms 5000})
                          (catch Exception e
                            (println (str "   ⚠️  AI分类器创建失败: " (.getMessage e)))
                            (println "   ℹ️  将使用直接对话模式")
                            nil))

          ;; Create orchestrator
          orchestrator (-> (orchestrator/create-orchestrator
                            {:metrics metrics-collector})
                           (register-opencode-specialists! client base-session-id)
                           (orchestrator/start-orchestrator!))

          ;; Create dialog agent with AI classifier
          dialog-agent (-> (create-dialog-agent
                            {:client client
                             :session-id (str base-session-id "-dialog")
                             :orchestrator orchestrator
                             :ai-classifier ai-classifier})
                           (start-dialog-agent!))

          ;; Create CLI channel
          cli-channel (-> (create-cli-channel dialog-agent)
                          (start-cli-channel!))]

      (println "✅ 系统初始化完成")
      (println "\n💬 开始对话 (输入 'exit' 退出):\n")

      ;; Graceful Shutdown Hook
      (.addShutdownHook (Runtime/getRuntime)
        (Thread. (fn []
                   (println "\n⚠️ 收到中断信号，执行优雅停机...")
                   (stop-cli-channel! cli-channel)
                   (stop-dialog-agent! dialog-agent)
                   (orchestrator/stop-orchestrator! orchestrator)
                   (println "✅ 系统已安全停止"))))

      ;; Wait for dialog agent to stop
      (loop []
        (when (= :running @(:status dialog-agent))
          (Thread/sleep 100)
          (recur)))

      ;; Cleanup (for normal exit)
      (println "\n\n🛑 正在停止系统...")
      (stop-cli-channel! cli-channel)
      (stop-dialog-agent! dialog-agent)
      (orchestrator/stop-orchestrator! orchestrator)

      (println "✅ 系统已停止")
      (println (apply str (repeat 70 "═"))))))

(defn -main
  "Main entry point."
  [& args]
  (let [args-map (apply hash-map (if (even? (count args)) args (concat args [""])))
        opencode-url (get args-map "--url" "http://127.0.0.1:9711")
        classifier-model (get args-map "--model" "claude-3-5-haiku-latest")]
    (run-demo {:opencode-url opencode-url
               :classifier-model classifier-model})))

(comment
  ;; REPL Usage
  (run-demo {:opencode-url "http://127.0.0.1:9711"})

  ;; Test OpenCode connection
  (def client (create-opencode-client {:base-url "http://127.0.0.1:9711"}))
  (opencode/list-sessions client)

  ;; Test task status store
  (clear-task-store!)
  (def ts (create-task-status {:name "Test Task" :type :test}))
  (get-task-status (:id ts))
  (update-task-status! (:id ts) {:status :running :progress 50})
  (list-all-tasks))
