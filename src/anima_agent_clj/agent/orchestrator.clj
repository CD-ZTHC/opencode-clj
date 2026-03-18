(ns anima-agent-clj.agent.orchestrator
  "Task Orchestrator - Core Agent component for complex task decomposition.

   This module implements the 'CPU 大核' functionality:
   - Complex reasoning and task decomposition
   - Multi-agent coordination
   - Subtask parallelization
   - Result aggregation

   Architecture (from agent-cluster-architecture.md):
   ┌─────────────────────────────────────────────────┐
   │                   Core Agent                     │
   ├─────────────────────────────────────────────────┤
   │  ┌────────────┐  ┌────────────┐  ┌────────────┐ │
   │  │  Reasoner  │  │ Orchestrat │  │  Memory    │ │
   │  │  (推理器)   │  │ (编排器)   │  │  (记忆)    │ │
   │  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘ │
   │        │               │               │        │
   │        └───────────────┼───────────────┘        │
   │                        ↓                        │
   │              Task Queue & Coordination          │
   └─────────────────────────────────────────────────┘

   Usage:
   (def orchestrator (create-orchestrator {:bus bus :specialists specialists}))
   (orchestrate! orchestrator \"Build a website with frontend and backend\")"
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [anima-agent-clj.agent.task-classifier :as classifier]
   [anima-agent-clj.agent.specialist-pool :as specialist-pool]
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Decomposition Types
;; ══════════════════════════════════════════════════════════════════════════════

(defrecord SubTask
  [id              ; UUID - Subtask ID
   parent-id       ; UUID - Parent task ID
   trace-id        ; UUID - Trace chain ID
   name            ; String - Human-readable name
   type            ; Keyword - :frontend, :backend, :database, :api, :test, etc.
   description     ; String - Detailed description
   dependencies    ; Set<keyword> - Types of subtasks this depends on
   priority        ; Integer - 0-9, lower is higher priority
   specialist-type ; Keyword - Which specialist should handle this
   payload         ; Map - Task-specific data
   status          ; atom<keyword> - :pending, :running, :completed, :failed
   assigned-to     ; atom<String> - Agent/Specialist ID
   result          ; atom<Map> - Execution result
   started-at      ; atom<Date>
   completed-at    ; atom<Date>
   metadata])      ; Map - Additional info

(defrecord OrchestrationPlan
  [id              ; UUID - Plan ID
   trace-id        ; UUID - Trace ID
   original-request ; String - Original user request
   subtasks        ; Map<UUID, SubTask> - All subtasks
   execution-order ; Vector<UUID> - Order of execution (topologically sorted)
   parallel-groups ; Vector<Set<UUID>> - Groups that can run in parallel
   status          ; atom<keyword> - :planning, :ready, :executing, :completed, :failed
   progress        ; atom<Map> - {total, completed, running, failed}
   created-at      ; Date
   metadata])      ; Map

(defrecord Orchestrator
  [id              ; UUID - Orchestrator ID
   bus             ; Bus instance
   dispatcher      ; EventDispatcher
   specialist-pool ; SpecialistPool
   active-plans    ; atom<Map<UUID, OrchestrationPlan>>
   config          ; Map
   status          ; atom<keyword>
   orchestrator-metrics ; atom<Map>
   metrics-collector]) ; MetricsCollector

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Decomposition Rules
;; ══════════════════════════════════════════════════════════════════════════════

(def task-decomposition-rules
  "Rules for decomposing complex tasks into subtasks."
  [{:trigger #"(?i)build.*website|create.*website|开发.*网站|创建.*网站|编写.*网站|图.*馆|图.*展|图.*库|画廊|相册"
    :category :web-application
    :subtasks
    [{:name "Project Setup"
      :type :setup
      :specialist :project-specialist
      :description "Initialize project structure and configuration"
      :priority 0
      :dependencies #{}}
     {:name "Database Design"
      :type :database
      :specialist :database-specialist
      :description "Design database schema and models"
      :priority 1
      :dependencies #{:setup}}
     {:name "Backend API"
      :type :backend
      :specialist :backend-specialist
      :description "Implement backend API endpoints and business logic"
      :priority 2
      :dependencies #{:database}}
     {:name "Frontend UI"
      :type :frontend
      :specialist :frontend-specialist
      :description "Build frontend user interface components"
      :priority 2
      :dependencies #{:setup}}
     {:name "API Integration"
      :type :integration
      :specialist :integration-specialist
      :description "Connect frontend with backend API"
      :priority 3
      :dependencies #{:frontend :backend}}
     {:name "Testing"
      :type :testing
      :specialist :test-specialist
      :description "Write unit and integration tests"
      :priority 4
      :dependencies #{:integration}}]}

   {:trigger #"(?i)build.*api|create.*api|开发.*api|创建.*api|rest.*api"
    :category :api-development
    :subtasks
    [{:name "API Design"
      :type :design
      :specialist :api-specialist
      :description "Design API endpoints and data models"
      :priority 0
      :dependencies #{}}
     {:name "Database Layer"
      :type :database
      :specialist :database-specialist
      :description "Implement database access layer"
      :priority 1
      :dependencies #{:design}}
     {:name "Core API Implementation"
      :type :backend
      :specialist :backend-specialist
      :description "Implement API endpoints"
      :priority 2
      :dependencies #{:database}}
     {:name "Authentication"
      :type :auth
      :specialist :security-specialist
      :description "Implement authentication and authorization"
      :priority 2
      :dependencies #{:design}}
     {:name "API Documentation"
      :type :documentation
      :specialist :doc-specialist
      :description "Generate API documentation"
      :priority 3
      :dependencies #{:backend :auth}}]}

   {:trigger #"(?i)analyze.*data|data.*analysis|分析.*数据|数据分析"
    :category :data-analysis
    :subtasks
    [{:name "Data Collection"
      :type :collection
      :specialist :data-specialist
      :description "Collect and prepare data"
      :priority 0
      :dependencies #{}}
     {:name "Data Cleaning"
      :type :cleaning
      :specialist :data-specialist
      :description "Clean and preprocess data"
      :priority 1
      :dependencies #{:collection}}
     {:name "Statistical Analysis"
      :type :analysis
      :specialist :analysis-specialist
      :description "Perform statistical analysis"
      :priority 2
      :dependencies #{:cleaning}}
     {:name "Visualization"
      :type :visualization
      :specialist :visualization-specialist
      :description "Create data visualizations"
      :priority 3
      :dependencies #{:analysis}}]}

   {:trigger #"(?i)refactor.*code|code.*refactor|重构.*代码"
    :category :refactoring
    :subtasks
    [{:name "Code Analysis"
      :type :analysis
      :specialist :code-specialist
      :description "Analyze current code structure"
      :priority 0
      :dependencies #{}}
     {:name "Refactoring Plan"
      :type :planning
      :specialist :architect-specialist
      :description "Create refactoring plan"
      :priority 1
      :dependencies #{:analysis}}
     {:name "Implementation"
      :type :implementation
      :specialist :code-specialist
      :description "Implement refactoring changes"
      :priority 2
      :dependencies #{:planning}}
     {:name "Testing"
      :type :testing
      :specialist :test-specialist
      :description "Verify refactoring didn't break functionality"
      :priority 3
      :dependencies #{:implementation}}]}])

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Decomposition
;; ══════════════════════════════════════════════════════════════════════════════

(defn- match-decomposition-rule
  "Find a matching decomposition rule for the request."
  [request]
  (first (filter (fn [rule]
                   (re-find (:trigger rule) request))
                 task-decomposition-rules)))

(defn- create-subtask
  "Create a SubTask from a template."
  [template parent-id trace-id]
  (->SubTask
   (str (UUID/randomUUID))
   parent-id
   trace-id
   (:name template)
   (:type template)
   (:description template)
   (:dependencies template)
   (:priority template 5)
   (:specialist template)
   nil
   (atom :pending)
   (atom nil)
   (atom nil)
   (atom nil)
   (atom nil)
   {}))

(defn decompose-task
  "Decompose a complex task into subtasks.

   Returns an OrchestrationPlan with:
   - subtasks: Map of ID -> SubTask
   - execution-order: Topologically sorted execution order
   - parallel-groups: Groups of tasks that can run in parallel"
  [request trace-id]
  (if-let [rule (match-decomposition-rule request)]
    (let [parent-id (str (UUID/randomUUID))
          ;; Create all subtasks
          subtasks-vec (map (fn [template]
                              (create-subtask template parent-id trace-id))
                            (:subtasks rule))
          subtasks (into {} (map (fn [st] [(:id st) st]) subtasks-vec))
          ;; Build type -> id mapping
          type-to-id (into {} (map (fn [st] [(:type st) (:id st)]) subtasks-vec))
          ;; Calculate execution order using topological sort
          execution-order (let [sorted (atom [])
                                visited (atom #{})]
                            (loop []
                              (let [ready (filter (fn [st]
                                                    (and (not (@visited (:id st)))
                                                         (every? @visited
                                                                 (map #(get type-to-id %)
                                                                      (:dependencies st)))))
                                                  (vals subtasks))]
                                (when (seq ready)
                                  (doseq [st ready]
                                    (swap! visited conj (:id st))
                                    (swap! sorted conj (:id st)))
                                  (recur))))
                            @sorted)
          ;; Group tasks by their dependency level for parallel execution
          parallel-groups (let [level-map (atom {})]
                            (doseq [id execution-order]
                              (let [st (get subtasks id)
                                    deps (:dependencies st)
                                    max-dep-level (if (seq deps)
                                                    (apply max -1
                                                           (keep #(get @level-map
                                                                       (get type-to-id %))
                                                                 deps))
                                                    -1)
                                    level (inc max-dep-level)]
                                (swap! level-map assoc id level)))
                            (let [max-level (apply max (vals @level-map))]
                              (for [level (range (inc max-level))]
                                (set (for [[id lvl] @level-map
                                           :when (= lvl level)]
                                       id)))))]

      (->OrchestrationPlan
       parent-id
       trace-id
       request
       subtasks
       execution-order
       parallel-groups
       (atom :ready)
       (atom {:total (count subtasks)
              :completed 0
              :running 0
              :failed 0})
       (Date.)
       {:category (:category rule)}))

    ;; No decomposition rule matched - return single task
    (let [id (str (UUID/randomUUID))]
      (->OrchestrationPlan
       id
       trace-id
       request
       {id (->SubTask id nil trace-id
                      "Main Task" :general request #{}
                      5 :general nil
                      (atom :pending) (atom nil) (atom nil)
                      (atom nil) (atom nil) {})}
       [id]
       [#{id}]
       (atom :ready)
       (atom {:total 1 :completed 0 :running 0 :failed 0})
       (Date.)
       {}))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Plan Execution
;; ══════════════════════════════════════════════════════════════════════════════

(defn- execute-subtask
  "Execute a single subtask by routing to appropriate specialist."
  [orchestrator subtask]
  (let [pool (:specialist-pool orchestrator)
        mc (:metrics-collector orchestrator)
        task-spec {:type (:specialist-type subtask)
                   :payload {:description (:description subtask)
                             :type (:type subtask)
                             :name (:name subtask)}
                   :trace-id (:trace-id subtask)
                   :subtask-id (:id subtask)}]

    (when mc (metrics/counter-inc! mc "subtasks_started"))
    (println (str "    🔧 执行子任务: " (:name subtask)
                  " (specialist: " (:specialist-type subtask) ")"))

    ;; Route to specialist pool
    (specialist-pool/route-task! pool task-spec)))

(defn- update-plan-progress!
  "Update plan progress after a subtask completes."
  [plan subtask-id status result]
  (let [subtask (get (:subtasks plan) subtask-id)]
    (when subtask
      (reset! (:status subtask) status)
      (reset! (:result subtask) result)
      (reset! (:completed-at subtask) (Date.))
      (swap! (:progress plan)
             (fn [p]
               (-> p
                   (update :running dec)
                   (update (if (= :completed status) :completed :failed) inc)))))))

(defn execute-plan!
  "Execute an orchestration plan with parallel execution of independent tasks.

   Returns a channel that will receive the final result.

   Execution strategy:
   1. Group subtasks by dependency level
   2. Execute each level in parallel
   3. Wait for all tasks in a level before proceeding to next
   4. Aggregate results"
  [orchestrator plan]
  (let [result-ch (async/promise-chan)
        mc (:metrics-collector orchestrator)]

    ;; Store plan
    (swap! (:active-plans orchestrator) assoc (:id plan) plan)

    (async/go
      (try
        (reset! (:status plan) :executing)

        ;; Process each parallel group sequentially
        (loop [groups (:parallel-groups plan)
               results {}]
          (if (empty? groups)
            ;; All done - aggregate results
            (do
              (reset! (:status plan) :completed)
              (when mc (metrics/counter-inc! mc "plans_completed"))
              (async/>! result-ch
                        {:status :success
                         :plan-id (:id plan)
                         :category (get-in plan [:metadata :category])
                         :subtask-count (count (:subtasks plan))
                         :completed-count (get @(:progress plan) :completed)
                         :results (into {}
                                        (for [[id st] (:subtasks plan)]
                                          [(:name st) @(:result st)]))
                         :plan plan}))

            ;; Execute current group in parallel
            (let [current-group (first groups)
                  group-count (count current-group)]

              (println (str "\n🔄 执行并行组: " group-count " 个任务"))

              ;; Mark all as running
              (doseq [task-id current-group]
                (let [st (get (:subtasks plan) task-id)]
                  (reset! (:status st) :running)
                  (reset! (:started-at st) (Date.))
                  (swap! (:progress plan) update :running inc)))

              ;; Execute all in parallel
              (let [task-channels (into {}
                                        (for [task-id current-group]
                                          (let [subtask (get (:subtasks plan) task-id)]
                                            [task-id (execute-subtask orchestrator subtask)])))
                    ;; Wait for all results
                    group-results (loop [remaining (keys task-channels)
                                         acc {}]
                                    (if (empty? remaining)
                                      acc
                                      (let [task-id (first remaining)
                                            ch (get task-channels task-id)
                                            result (async/<! ch)]
                                        (recur (rest remaining)
                                               (assoc acc task-id result)))))]

                ;; Update plan with results
                (doseq [[task-id result] group-results]
                  (let [status (if (= :success (:status result)) :completed :failed)]
                    (update-plan-progress! plan task-id status result)))

                ;; Continue to next group
                (recur (rest groups)
                       (merge results group-results))))))

        (catch Exception e
          (reset! (:status plan) :failed)
          (when mc (metrics/counter-inc! mc "plans_failed"))
          (async/>! result-ch
                    {:status :error
                     :error (.getMessage e)
                     :plan-id (:id plan)}))

        (finally
          ;; Cleanup
          (swap! (:active-plans orchestrator) dissoc (:id plan)))))

    result-ch))

;; ══════════════════════════════════════════════════════════════════════════════
;; Main Orchestration API
;; ══════════════════════════════════════════════════════════════════════════════

(defn orchestrate!
  "Main entry point for orchestrating complex tasks.

   1. Classifies the task
   2. Decomposes into subtasks if complex
   3. Executes in parallel where possible
   4. Returns aggregated results

   Returns a channel that receives the orchestration result.

   Example:
   (orchestrate! orchestrator \"Build a website with React frontend and Node backend\")"
  [orchestrator request]
  (let [trace-id (str (UUID/randomUUID))
        mc (:metrics-collector orchestrator)]

    (when mc (metrics/counter-inc! mc "requests_received"))

    ;; First, classify the request
    (let [classification (classifier/classify-task request)
          task-type (:type classification)]

      (when mc (metrics/counter-inc! mc "requests_classified"))

      (async/go
        (try
          ;; Check if this is a complex task that needs decomposition
          (if (#{:general-chat} task-type)
            ;; Simple chat task - route directly
            (let [result-ch (specialist-pool/route-task!
                             (:specialist-pool orchestrator)
                             {:type :chat-specialist
                              :payload {:content request}
                              :trace-id trace-id})]
              (async/<! result-ch))

            ;; Complex task - decompose and orchestrate
            (let [plan (decompose-task request trace-id)
                  _ (println (str "\n📋 创建编排计划: "
                                (count (:subtasks plan)) " 个子任务"))
                  result-ch (execute-plan! orchestrator plan)
                  result (async/<! result-ch)]
              result))

          (catch Exception e
            {:status :error
             :error (.getMessage e)}))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Orchestrator Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-orchestrator!
  "Start the orchestrator."
  [orchestrator]
  (when (= :stopped @(:status orchestrator))
    (reset! (:status orchestrator) :running)
    (specialist-pool/start-specialist-pool! (:specialist-pool orchestrator)))
  orchestrator)

(defn stop-orchestrator!
  "Stop the orchestrator."
  [orchestrator]
  (when (= :running @(:status orchestrator))
    (reset! (:status orchestrator) :stopped)
    (specialist-pool/stop-specialist-pool! (:specialist-pool orchestrator)))
  orchestrator)

;; ══════════════════════════════════════════════════════════════════════════════
;; Status & Metrics
;; ══════════════════════════════════════════════════════════════════════════════

(defn orchestrator-status
  "Get orchestrator status."
  [orchestrator]
  {:id (:id orchestrator)
   :status @(:status orchestrator)
   :active-plans (count @(:active-plans orchestrator))
   :specialists (specialist-pool/health-check (:specialist-pool orchestrator))
   :metrics @(:orchestrator-metrics orchestrator)})

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-orchestrator
  "Create a new Orchestrator.

   Options:
     :bus - Bus instance
     :specialist-pool - SpecialistPool instance (optional)
     :metrics - MetricsCollector instance"
  [{:keys [bus specialist-pool metrics]
    :as opts}]
  (let [sp (or specialist-pool
               (specialist-pool/create-specialist-pool
                {:load-balance-strategy :least-loaded
                 :metrics-collector metrics}))]
    (->Orchestrator
     (str (UUID/randomUUID))
     bus
     nil
     sp
     (atom {})
     opts
     (atom :stopped)
     (atom {:plans-created 0
            :plans-completed 0
            :plans-failed 0
            :subtasks-executed 0})
     metrics)))
