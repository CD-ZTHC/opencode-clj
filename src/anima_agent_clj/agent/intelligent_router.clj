(ns anima-agent-clj.agent.intelligent-router
  "Intelligent Dialog Router - Automatic task classification and routing.

   This module provides a complete pipeline for:
   1. Receiving messages from channels
   2. Classifying task type automatically
   3. Routing to appropriate specialists
   4. Collecting and returning responses

   Architecture:
   ┌─────────────────────────────────────────────────────────────┐
   │                  Intelligent Dialog Router                    │
   ├─────────────────────────────────────────────────────────────┤
   │  Channel → Classifier → Dispatcher → Specialist → Response  │
   │     ↓          ↓           ↓           ↓           ↓        │
   │  Message   TaskType    Priority    Handler     Result       │
   └─────────────────────────────────────────────────────────────┘

   Usage:
   (def router (create-intelligent-router {:bus bus :dispatcher disp}))
   (start-router! router)
   ;; Messages flow through: channel → classify → route → respond"
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [anima-agent-clj.bus :as bus]
   [anima-agent-clj.agent.task-classifier :as classifier]
   [anima-agent-clj.agent.specialist-pool :as specialist-pool]
   [anima-agent-clj.dispatcher.core :as dispatcher]
   [anima-agent-clj.channel :as channel]
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Router Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord DialogContext
  [session-id      ; Session identifier
   channel         ; Source channel
   sender-id       ; User identifier
   message-history ; atom<vector> - Message history
   task-history    ; atom<vector> - Task history
   created-at      ; Date - Context creation time
   last-active     ; atom<Date> - Last activity
   metadata])      ; Map - Additional context

(defrecord RoutedDialog
  [id              ; UUID - Dialog ID
   trace-id        ; UUID - Trace ID for logging
   context         ; DialogContext
   message         ; Original message
   classification  ; Task classification result
   specialist      ; Assigned specialist
   status          ; :received, :classified, :routed, :processing, :completed
   result          ; Final result
   started-at      ; Date - Processing start
   completed-at    ; Date - Processing end
   metadata])      ; Map - Additional info

(defrecord IntelligentRouter
  [id              ; UUID - Router identifier
   bus             ; Bus instance
   dispatcher      ; EventDispatcher instance
   specialist-pool ; SpecialistPool instance
   contexts        ; atom<map> - session-id -> DialogContext
   config          ; Map - Router configuration
   status          ; atom<keyword> - :stopped, :running
   router-metrics  ; atom<map> - Router metrics
   metrics-collector ; MetricsCollector instance
   processing-loop]) ; atom - Main processing loop

;; ══════════════════════════════════════════════════════════════════════════════
;; Configuration
;; ══════════════════════════════════════════════════════════════════════════════

(def default-config
  {:max-context-age-ms     3600000   ; 1 hour
   :max-message-history    100
   :default-timeout-ms     60000
   :classification-threshold 0.3     ; Min confidence for classification
   :fallback-to-general    true      ; Use general specialist if low confidence
   :enable-parallel-routing true     ; Route multiple tasks in parallel
   :response-channel-size  1000})

;; ══════════════════════════════════════════════════════════════════════════════
;; Context Management
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-context
  "Create a new dialog context."
  [{:keys [session-id channel sender-id metadata]
    :or {session-id (str (UUID/randomUUID))
         channel "unknown"
         sender-id "unknown"
         metadata {}}}]
  (->DialogContext
   session-id
   channel
   sender-id
   (atom [])
   (atom [])
   (Date.)
   (atom (Date.))
   metadata))

(defn get-or-create-context
  "Get existing context or create new one."
  [router session-id channel sender-id]
  (let [contexts (:contexts router)]
    (or (get @contexts session-id)
        (let [ctx (create-context {:session-id session-id
                                   :channel channel
                                   :sender-id sender-id})]
          (swap! contexts assoc session-id ctx)
          ctx))))

(defn update-context-activity!
  "Update context with new message."
  [context message classification]
  (swap! (:message-history context)
         (fn [history]
           (-> history
               (conj {:message message
                      :classification classification
                      :timestamp (Date.)})
               (->> (take-last (:max-message-history default-config))))))
  (reset! (:last-active context) (Date.))
  context)

(defn cleanup-old-contexts!
  "Remove contexts older than max-age."
  [router]
  (let [max-age (:max-context-age-ms default-config)
        now (System/currentTimeMillis)]
    (swap! (:contexts router)
           (fn [ctxs]
             (into {}
                   (filter (fn [[_ ctx]]
                             (< (- now (.getTime @(:last-active ctx))) max-age))
                           ctxs))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Specialist Registration Helpers
;; ══════════════════════════════════════════════════════════════════════════════

(defn register-default-specialists!
  "Register default specialists for common task types."
  [router]
  (let [pool (:specialist-pool router)]

    ;; Code Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "code-specialist"
      :name "Code Specialist"
      :type :code
      :capabilities #{:code-generation :code-review :code-debug :code-refactor
                      :test-generation :api-call}
      :handler (fn [task]
                 {:status :success
                  :result {:type :code-response
                           :task-type (:type task)
                           :payload (:payload task)
                           :message (str "Code specialist processed: "
                                        (-> task :type name))}})})

    ;; Search Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "search-specialist"
      :name "Search Specialist"
      :type :search
      :capabilities #{:web-search}
      :handler (fn [task]
                 {:status :success
                  :result {:type :search-response
                           :query (get-in task [:payload :query])
                           :results ["Search result 1" "Search result 2"]}})})

    ;; Documentation Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "doc-specialist"
      :name "Documentation Specialist"
      :type :documentation
      :capabilities #{:documentation}
      :handler (fn [task]
                 {:status :success
                  :result {:type :doc-response
                           :content "Generated documentation..."}})})

    ;; Data Analysis Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "analysis-specialist"
      :name "Data Analysis Specialist"
      :type :analysis
      :capabilities #{:data-analysis}
      :handler (fn [task]
                 {:status :success
                  :result {:type :analysis-response
                           :analysis "Data analysis results..."}})})

    ;; General Chat Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "chat-specialist"
      :name "Chat Specialist"
      :type :chat
      :capabilities #{:general-chat}
      :handler (fn [task]
                 {:status :success
                  :result {:type :chat-response
                           :message (str "Hello! I received: "
                                        (get-in task [:payload :content]))}})})

    router))

;; ══════════════════════════════════════════════════════════════════════════════
;; Message Processing
;; ══════════════════════════════════════════════════════════════════════════════

(defn classify-message
  "Classify a message and return classification result."
  [message]
  (let [result (classifier/extract-task-context message)]
    result))

(defn route-to-specialist
  "Route classified task to appropriate specialist."
  [router classification context message]
  (let [task-type (:type classification)
        confidence (:confidence classification)
        config (:config router)
        pool (:specialist-pool router)
        mc (:metrics-collector router)]

    (when mc (metrics/counter-inc! mc "tasks_classified"))

    ;; Check confidence threshold
    (if (and (< confidence (:classification-threshold config))
             (not (:fallback-to-general config)))
      {:status :failed
       :error "Classification confidence too low"
       :classification classification}

      ;; Route to specialist
      (let [task-spec {:type task-type
                       :payload {:content message
                                 :context context
                                 :classification classification}
                       :trace-id (str (UUID/randomUUID))
                       :fallback (:fallback-to-general config)}
            result-ch (specialist-pool/route-task! pool task-spec)]
        {:status :routed
         :task-type task-type
         :confidence confidence
         :result-channel result-ch}))))

(defn process-message
  "Process a single message through the pipeline."
  [router channel-message]
  (let [start-time (System/currentTimeMillis)
        mc (:metrics-collector router)
        content (:content channel-message)
        session-id (:session-id channel-message)
        sender-id (:sender channel-message)
        channel-name (:channel channel-message)]

    ;; Get or create context
    (let [context (get-or-create-context router session-id channel-name sender-id)

          ;; Step 1: Classify the message
          classification (classify-message content)

          ;; Update context
          _ (update-context-activity! context content classification)

          ;; Step 2: Route to specialist
          route-result (route-to-specialist router classification context content)]

      (when mc (metrics/counter-inc! mc "messages_processed"))

      (cond
        ;; Routing failed
        (= :failed (:status route-result))
        {:status :error
         :error (:error route-result)
         :classification classification}

        ;; Successfully routed - wait for result
        :else
        (let [result-ch (:result-channel route-result)
              timeout-ms (get-in router [:config :default-timeout-ms] 60000)
              timeout-ch (async/timeout timeout-ms)
              [result port] (async/alts!! [result-ch timeout-ch])]

          (if (= port timeout-ch)
            {:status :timeout
             :error "Task processing timed out"
             :classification classification}

            (let [final-result
                  {:status :success
                   :classification classification
                   :specialist-result result
                   :processing-time-ms (- (System/currentTimeMillis) start-time)}]

              ;; Update task history
              (swap! (:task-history context)
                     conj {:classification classification
                           :result result
                           :timestamp (Date.)})

              (when mc
                (metrics/histogram-record! mc "processing_time"
                                           (:processing-time-ms final-result)))

              final-result)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Batch Processing
;; ══════════════════════════════════════════════════════════════════════════════

(defn process-batch
  "Process multiple messages in parallel."
  [router messages]
  (let [config (:config router)]
    (if (:enable-parallel-routing config)
      ;; Parallel processing
      (let [result-chs (mapv #(async/thread (process-message router %)) messages)
            results (doall (map async/<!! result-chs))]
        results)
      ;; Sequential processing
      (mapv #(process-message router %) messages))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Response Formatting
;; ══════════════════════════════════════════════════════════════════════════════

(defn format-response
  "Format the processing result as a user-friendly response."
  [result original-message]
  (let [classification (:classification result)
        task-type (:type classification)
        confidence (:confidence classification)
        specialist-result (:specialist-result result)]

    (cond
      ;; Error cases
      (= :error (:status result))
      {:success false
       :error (:error result)
       :message (str "处理失败: " (:error result))}

      (= :timeout (:status result))
      {:success false
       :error :timeout
       :message "处理超时，请稍后重试"}

      ;; Success
      :else
      (let [response-content
            (case task-type
              :code-generation
              (format "✨ 代码生成完成 (置信度: %.0f%%)\n%s"
                      (* confidence 100)
                      (get-in specialist-result [:result :message] "代码已生成"))

              :code-review
              (format "🔍 代码审查完成 (置信度: %.0f%%)\n%s"
                      (* confidence 100)
                      (get-in specialist-result [:result :message] "审查完成"))

              :code-debug
              (format "🐛 调试分析完成 (置信度: %.0f%%)\n%s"
                      (* confidence 100)
                      (get-in specialist-result [:result :message] "问题已定位"))

              :web-search
              (format "🔎 搜索完成 (置信度: %.0f%%)\n%s"
                      (* confidence 100)
                      (->> (get-in specialist-result [:result :results] [])
                           (map str)
                           (str/join "\n")))

              :general-chat
              (get-in specialist-result [:result :message] "您好！有什么可以帮您的？")

              ;; Default
              (format "✅ 任务处理完成\n类型: %s\n置信度: %.0f%%\n处理时间: %dms"
                      (name task-type)
                      (* confidence 100)
                      (:processing-time-ms result 0)))]

        {:success true
         :task-type task-type
         :confidence confidence
         :message response-content
         :processing-time-ms (:processing-time-ms result)}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-router!
  "Start the intelligent router."
  [router]
  (when (= :stopped @(:status router))
    (reset! (:status router) :running)

    ;; Start specialist pool
    (specialist-pool/start-specialist-pool! (:specialist-pool router)))

  router)

(defn stop-router!
  "Stop the intelligent router."
  [router]
  (when (= :running @(:status router))
    (reset! (:status router) :stopping)

    ;; Stop processing loop
    (when-let [loop @(:processing-loop router)]
      (async/close! loop))

    ;; Stop specialist pool
    (specialist-pool/stop-specialist-pool! (:specialist-pool router))

    (reset! (:status router) :stopped))
  router)

;; ══════════════════════════════════════════════════════════════════════════════
;; Status & Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn router-status
  "Get router status."
  [router]
  {:id (:id router)
   :status @(:status router)
   :context-count (count @(:contexts router))
   :specialists (specialist-pool/health-check (:specialist-pool router))
   :metrics @(:router-metrics router)})

(defn router-metrics
  "Get router metrics."
  [router]
  (merge
   @(:router-metrics router)
   (specialist-pool/specialist-pool-metrics (:specialist-pool router))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Direct API (for testing and REPL use)
;; ══════════════════════════════════════════════════════════════════════════════

(defn send-message
  "Send a message directly to the router (bypassing bus).
   Returns the response synchronously."
  [router message-text {:keys [session-id sender channel]
                        :or {session-id (str (UUID/randomUUID))
                             sender "user"
                             channel "direct"}}]
  (let [msg (channel/create-message
             {:session-id session-id
              :sender sender
              :channel channel
              :content message-text})
        result (process-message router msg)]
    (format-response result msg)))

(defn send-message-async
  "Send a message asynchronously.
   Returns a channel that will receive the response."
  [router message-text opts]
  (async/go
    (send-message router message-text opts)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-intelligent-router
  "Create a new IntelligentRouter.

   Options:
     :bus                  - Bus instance (required)
     :dispatcher           - EventDispatcher instance (optional, will create if not provided)
     :specialist-pool      - SpecialistPool instance (optional, will create if not provided)
     :register-defaults    - Register default specialists (default: true)
     :metrics              - MetricsCollector instance
     :classification-threshold - Min confidence for classification (default: 0.3)
     :default-timeout-ms   - Default processing timeout (default: 60000)

   Example:
   (create-intelligent-router {:bus bus :register-defaults true})"
  [{:keys [bus dispatcher specialist-pool register-defaults metrics
           classification-threshold default-timeout-ms]
    :or {register-defaults true
         classification-threshold 0.3
         default-timeout-ms 60000}}]

  (when (nil? bus)
    (throw (IllegalArgumentException. "Bus instance is required")))

  (let [sp (or specialist-pool
               (specialist-pool/create-specialist-pool
                {:load-balance-strategy :least-loaded
                 :metrics-collector metrics}))
        disp (or dispatcher
                 (dispatcher/create-dispatcher
                  {:bus bus
                   :metrics metrics}))
        router (->IntelligentRouter
                (str (UUID/randomUUID))
                bus
                disp
                sp
                (atom {})
                (merge default-config
                       {:classification-threshold classification-threshold
                        :default-timeout-ms default-timeout-ms})
                (atom :stopped)
                (atom {:messages-processed 0
                       :tasks-routed 0
                       :errors 0})
                metrics
                (atom nil))]

    ;; Register default specialists if requested
    (when register-defaults
      (register-default-specialists! router))

    router))
