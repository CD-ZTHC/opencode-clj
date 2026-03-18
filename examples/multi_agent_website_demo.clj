(ns multi-agent-website-demo
  "Multi-Agent Website Building Demo.

   Demonstrates true multi-agent parallel collaboration as described in
   agent-cluster-architecture.md:

   User Request: 'Build a website'
   ↓
   Core Agent (Orchestrator)
   ├── Analyzes request → Decomposes into subtasks
   └── Creates OrchestrationPlan

   Parallel Execution:
   Level 0: [Project Setup]          (1 task)
   Level 1: [Database Design]        (1 task, depends on Setup)
   Level 2: [Backend API] [Frontend UI]  (2 tasks in PARALLEL!)
   Level 3: [API Integration]        (1 task, depends on both)
   Level 4: [Testing]                (1 task)

   Result: Complete website with frontend, backend, and tests

   Run: lein run -m multi-agent-website-demo"
  (:require
   [clojure.core.async :as async]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [anima-agent-clj.bus :as bus]
   [anima-agent-clj.agent.orchestrator :as orchestrator]
   [anima-agent-clj.agent.specialist-pool :as specialist-pool]
   [anima-agent-clj.dispatcher.core :as dispatcher]
   [anima-agent-clj.dispatcher.balancer :as balancer]
   [anima-agent-clj.dispatcher.circuit-breaker :as cb]
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo Utilities
;; ══════════════════════════════════════════════════════════════════════════════

(defn section [title]
  (println (str "\n" (apply str (repeat 70 "═"))))
  (println title)
  (println (apply str (repeat 70 "═"))))

(defn subsection [title]
  (println (str "\n─── " title " ───")))

(defn info [& args]
  (apply println "  " args))

(defn success [msg]
  (println (str "  ✅ " msg)))

(defn task-info [st]
  (println (str "    📌 " (:name st)
                " (类型: " (name (:type st)) ")"
                " [优先级: " (:priority st) "]")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Register Real Specialists
;; ══════════════════════════════════════════════════════════════════════════════

(defn register-website-specialists!
  "Register specialists for website building tasks."
  [orchestrator]
  (let [pool (:specialist-pool orchestrator)]

    ;; Project Setup Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "project-setup-specialist"
      :name "Project Setup Specialist"
      :type :project-specialist
      :capabilities #{:setup :project-init :configuration}
      :handler (fn [task]
                 (info "🏗️  Project Setup Specialist 正在工作...")
                 (Thread/sleep 500)  ; Simulate work
                 {:status :success
                  :result {:type :project-structure
                           :files ["package.json" "config/" "src/"]
                           :message "项目结构初始化完成"}})})

    ;; Database Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "database-specialist"
      :name "Database Specialist"
      :type :database-specialist
      :capabilities #{:database :schema-design :migrations}
      :handler (fn [task]
                 (info "🗄️  Database Specialist 正在工作...")
                 (Thread/sleep 800)
                 {:status :success
                  :result {:type :database-schema
                           :tables ["users" "posts" "comments"]
                           :migrations ["001_create_users.sql"
                                        "002_create_posts.sql"]
                           :message "数据库设计完成"}})})

    ;; Backend Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "backend-specialist"
      :name "Backend Specialist"
      :type :backend-specialist
      :capabilities #{:backend :api :server}
      :handler (fn [task]
                 (info "⚙️  Backend Specialist 正在工作...")
                 (Thread/sleep 1200)
                 {:status :success
                  :result {:type :backend-code
                           :endpoints [{:path "/api/users" :method :GET}
                                      {:path "/api/posts" :method :GET}
                                      {:path "/api/posts" :method :POST}]
                           :files ["server.js" "routes/" "models/"]
                           :message "后端 API 实现完成"}})})

    ;; Frontend Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "frontend-specialist"
      :name "Frontend Specialist"
      :type :frontend-specialist
      :capabilities #{:frontend :ui :react}
      :handler (fn [task]
                 (info "🎨 Frontend Specialist 正在工作...")
                 (Thread/sleep 1000)
                 {:status :success
                  :result {:type :frontend-code
                           :components ["App.jsx" "Header.jsx" "PostList.jsx"
                                        "UserCard.jsx"]
                           :pages ["Home" "About" "Contact"]
                           :styles ["main.css" "components.css"]
                           :message "前端 UI 组件完成"}})})

    ;; Integration Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "integration-specialist"
      :name "Integration Specialist"
      :type :integration-specialist
      :capabilities #{:integration :api-client}
      :handler (fn [task]
                 (info "🔗 Integration Specialist 正在工作...")
                 (Thread/sleep 600)
                 {:status :success
                  :result {:type :integration-code
                           :api-client ["api/users.js" "api/posts.js"]
                           :hooks ["useUsers" "usePosts"]
                           :message "前后端集成完成"}})})

    ;; Test Specialist
    (specialist-pool/register-specialist!
     pool
     {:id "test-specialist"
      :name "Test Specialist"
      :type :test-specialist
      :capabilities #{:testing :unit-test :integration-test}
      :handler (fn [task]
                 (info "🧪 Test Specialist 正在工作...")
                 (Thread/sleep 700)
                 {:status :success
                  :result {:type :test-code
                           :tests ["unit/api.test.js"
                                   "unit/components.test.js"
                                   "integration/app.test.js"]
                           :coverage "85%"
                           :message "测试套件完成"}})})

    orchestrator))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 1: Website Building with Multi-Agent Collaboration
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-website-building
  "Demonstrate full multi-agent website building."
  []
  (section "Demo: 多Agent协作构建网站")

  (let [;; Create infrastructure
        metrics-collector (metrics/create-collector {:id "website-demo"})

        orchestrator (-> (orchestrator/create-orchestrator
                          {:metrics metrics-collector})
                         (register-website-specialists!)
                         (orchestrator/start-orchestrator!))]

    (info "🚀 编排器已启动")
    (info "注册专家数:" (count (specialist-pool/list-specialists
                             (:specialist-pool orchestrator))))

    (subsection "用户请求")
    (info "\"Build a website with React frontend and Node.js backend\"")

    (subsection "Step 1: 任务分解")
    (let [plan (orchestrator/decompose-task
                "Build a website with React frontend and Node.js backend"
                (str (UUID/randomUUID)))]

      (info "\n📋 编排计划:")
      (info "  任务总数:" (count (:subtasks plan)))
      (info "  并行层级:" (count (:parallel-groups plan)))

      (info "\n📊 执行层级:")
      (doseq [[idx group] (map-indexed vector (:parallel-groups plan))]
        (println (str "\n  层级 " idx " (并行执行 " (count group) " 个任务):"))
        (doseq [task-id group]
          (task-info (get (:subtasks plan) task-id))))

      (subsection "Step 2: 并行执行")
      (info "开始执行编排计划...")
      (info "注意: Backend 和 Frontend 将并行执行!\n")

      ;; Execute the plan
      (let [result-ch (orchestrator/execute-plan! orchestrator plan)
            result (async/<!! result-ch)]

        (subsection "Step 3: 结果汇总")

        (if (= :success (:status result))
          (do
            (info "\n✨ 网站构建完成!")
            (info "  总任务数:" (:subtask-count result))
            (info "  完成数:" (:completed-count result))
            (info "  类别:" (name (:category result)))

            (info "\n📦 各模块产出:")
            (doseq [[task-name task-result] (:results result)]
              (println (str "\n  【" task-name "】"))
              (when-let [files (get-in task-result [:result :files])]
                (info "    文件:" (str/join ", " files)))
              (when-let [components (get-in task-result [:result :components])]
                (info "    组件:" (str/join ", " components)))
              (when-let [endpoints (get-in task-result [:result :endpoints])]
                (info "    端点:" (count endpoints) "个"))
              (when-let [tests (get-in task-result [:result :tests])]
                (info "    测试:" (str/join ", " tests)))))

          (do
            (info "❌ 执行失败:" (:error result)))))

      ;; Stop orchestrator
      (orchestrator/stop-orchestrator! orchestrator)

      (success "多Agent协作演示完成"))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 2: API Development
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-api-development
  "Demonstrate API development with parallel tasks."
  []
  (section "Demo: API 开发多Agent协作")

  (let [metrics-collector (metrics/create-collector {:id "api-demo"})
        orchestrator (-> (orchestrator/create-orchestrator
                          {:metrics metrics-collector})
                         (register-website-specialists!)
                         (orchestrator/start-orchestrator!))]

    (subsection "用户请求")
    (info "\"Build a REST API for a blog system\"")

    (let [plan (orchestrator/decompose-task
                "Build a REST API for a blog system"
                (str (UUID/randomUUID)))]

      (info "\n📋 编排计划:")
      (doseq [[idx group] (map-indexed vector (:parallel-groups plan))]
        (println (str "\n  层级 " idx ":"))
        (doseq [task-id group]
          (task-info (get (:subtasks plan) task-id))))

      (info "\n执行中...")
      (let [result-ch (orchestrator/execute-plan! orchestrator plan)
            result (async/<!! result-ch)]

        (if (= :success (:status result))
          (info "✅ API 开发完成 - 任务数:" (:subtask-count result))
          (info "❌ 失败:" (:error result)))))

    (orchestrator/stop-orchestrator! orchestrator)
    (success "API开发演示完成")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 3: Show Parallel Execution Timing
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-parallel-timing
  "Demonstrate parallel execution timing benefits."
  []
  (section "Demo: 并行执行性能对比")

  (let [metrics-collector (metrics/create-collector {:id "timing-demo"})
        orchestrator (-> (orchestrator/create-orchestrator
                          {:metrics metrics-collector})
                         (register-website-specialists!)
                         (orchestrator/start-orchestrator!))

        plan (orchestrator/decompose-task
              "Build a website"
              (str (UUID/randomUUID)))]

    (info "\n📊 并行层级分析:")
    (info "  总任务数:" (count (:subtasks plan)))
    (info "  串行执行需要: 6 个层级")
    (info "  并行执行需要:" (count (:parallel-groups plan)) "个层级")

    (info "\n⏱️  并行执行演示:")
    (let [start-time (System/currentTimeMillis)
          result-ch (orchestrator/execute-plan! orchestrator plan)
          result (async/<!! result-ch)
          end-time (System/currentTimeMillis)
          parallel-time (- end-time start-time)]

      (info "  并行执行时间:" parallel-time "ms")
      (info "  并行层级数:" (count (:parallel-groups plan)))
      (when (pos? parallel-time)
        (info "  (如果串行执行，预计需要: ~" (* parallel-time 2) "ms)")
        (info "  性能提升: ~" (int (* 100 (/ (- (* parallel-time 2) parallel-time)
                                           (* parallel-time 2)))) "%")))

    (orchestrator/stop-orchestrator! orchestrator)
    (success "并行性能演示完成")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Main Entry Point
;; ══════════════════════════════════════════════════════════════════════════════

(defn run-all-demos
  "Run all multi-agent demos."
  []
  (println "\n" (apply str (repeat 70 "═")))
  (println "       多Agent协作系统演示")
  (println "       Multi-Agent Collaborative System Demo")
  (println (apply str (repeat 70 "═")))
  (println "\n  架构说明:")
  (println "    - Core Agent (CPU大核): 任务编排与协调")
  (println "    - Specialist Pool (NPU): 领域专家处理")
  (println "    - Parallel Execution: 无依赖任务并行执行")
  (println "    - Result Aggregation: 结果汇总")

  (demo-website-building)
  (demo-api-development)
  (demo-parallel-timing)

  (println "\n" (apply str (repeat 70 "═")))
  (println "       所有演示完成!")
  (println (apply str (repeat 70 "═")))
  (println "\n  关键特性:")
  (println "    ✅ 任务自动分解 - 复杂任务拆分为子任务")
  (println "    ✅ 依赖分析 - 自动识别任务依赖关系")
  (println "    ✅ 并行执行 - 无依赖任务同时执行")
  (println "    ✅ 专家路由 - 子任务分配给合适的专家")
  (println "    ✅ 结果聚合 - 汇总所有子任务结果")
  (println (apply str (repeat 70 "═"))))

(defn -main
  "Main entry point."
  []
  (run-all-demos))

(comment
  ;; Run in REPL
  (run-all-demos)
  (demo-website-building)
  (demo-parallel-timing))
