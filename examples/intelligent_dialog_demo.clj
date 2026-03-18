(ns intelligent-dialog-demo
  "Intelligent Dialog System Demo.

   Demonstrates automatic task classification and routing:
   1. User sends message through channel
   2. System classifies task type (code, search, chat, etc.)
   3. Routes to appropriate specialist
   4. Returns processed response

   Run: lein run -m intelligent-dialog-demo"
  (:require
   [clojure.core.async :as async]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [anima-agent-clj.bus :as bus]
   [anima-agent-clj.agent.task-classifier :as classifier]
   [anima-agent-clj.agent.intelligent-router :as router]
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

(defn result [label value]
  (println (str "  📤 " label ": " value)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 1: Task Classification
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-task-classification
  "Demonstrate automatic task classification."
  []
  (section "Demo 1: 任务自动分类")

  (let [test-messages
        [{:msg "Write a function to sort a list" :expected :code-generation}
         {:msg "帮我写一个 Clojure 函数计算斐波那契数列" :expected :code-generation}
         {:msg "Review this code for potential bugs" :expected :code-review}
         {:msg "Debug this error: NullPointerException at line 42" :expected :code-debug}
         {:msg "Search for Clojure best practices" :expected :web-search}
         {:msg "你好，今天天气怎么样？" :expected :general-chat}
         {:msg "Analyze this data and create a chart" :expected :data-analysis}
         {:msg "Write unit tests for the user service" :expected :test-generation}]]

    (doseq [{:keys [msg expected]} test-messages]
      (let [result (classifier/classify-task msg)
            task-type (:type result)
            confidence (:confidence result)
            correct? (= task-type expected)]
        (info "\n消息:" msg)
        (result "分类" (name task-type))
        (result "置信度" (format "%.2f" confidence))
        (result "预期" (name expected))
        (if correct?
          (println "  ✅ 分类正确")
          (println "  ⚠️  分类不同"))))

    (success "任务分类演示完成")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 2: Context Extraction
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-context-extraction
  "Demonstrate context extraction from messages."
  []
  (section "Demo 2: 上下文提取")

  (let [code-msg "Write a Clojure function:\n```clojure\n(defn hello [] (println \"Hi\"))\n```"
        search-msg "Search for: Clojure async programming"
        analysis-msg "Analyze this dataset and show statistics:\nData: [1, 2, 3, 4, 5]"]

    (info "\n代码消息分析:")
    (let [ctx (classifier/extract-task-context code-msg)]
      (result "任务类型" (name (:type ctx)))
      (result "语言检测" (str (:language-hint ctx)))
      (result "包含代码" (-> ctx :code-blocks :has-code?)))

    (info "\n搜索消息分析:")
    (let [ctx (classifier/extract-task-context search-msg)]
      (result "任务类型" (name (:type ctx)))
      (result "紧急程度" (name (:urgency ctx))))

    (info "\n分析消息分析:")
    (let [ctx (classifier/extract-task-context analysis-msg)]
      (result "任务类型" (name (:type ctx)))
      (result "检测关键词" (str/join ", " (:detected-keywords ctx))))

    (success "上下文提取演示完成")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 3: Specialist Pool
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-specialist-pool
  "Demonstrate specialist pool routing."
  []
  (section "Demo 3: 专家池路由")

  (let [pool (specialist-pool/create-specialist-pool
              {:load-balance-strategy :least-loaded})]

    ;; Register specialists
    (specialist-pool/register-specialist!
     pool
     {:id "clojure-expert"
      :name "Clojure Expert"
      :type :code
      :capabilities #{:code-generation :code-review :code-refactor}
      :handler (fn [task]
                 {:status :success
                  :result {:language :clojure
                           :code "(defn hello [name] (str \"Hello, \" name))"}})
      :priority 10})

    (specialist-pool/register-specialist!
     pool
     {:id "python-expert"
      :name "Python Expert"
      :type :code
      :capabilities #{:code-generation :code-review}
      :handler (fn [task]
                 {:status :success
                  :result {:language :python
                           :code "def hello(name): return f'Hello, {name}'"}})
      :priority 5})

    (specialist-pool/register-specialist!
     pool
     {:id "search-expert"
      :name "Search Expert"
      :type :search
      :capabilities #{:web-search}
      :handler (fn [task]
                 {:status :success
                  :result {:results ["Result 1" "Result 2"]}})})

    (info "已注册专家:")
    (doseq [s (specialist-pool/list-specialists pool)]
      (info (str "  - " (:name s) " (ID: " (:id s) ")"))
      (info (str "    能力: " (str/join ", " (map name (:capabilities s))))))

    ;; Route tasks
    (subsection "任务路由测试")

    (info "\n路由代码生成任务...")
    (let [result-ch (specialist-pool/route-task!
                     pool
                     {:type :code-generation
                      :payload {:content "Write a function"}})
          result (async/<!! result-ch)]
      (info "  结果:" (pr-str result)))

    (info "\n路由搜索任务...")
    (let [result-ch (specialist-pool/route-task!
                     pool
                     {:type :web-search
                      :payload {:query "Clojure"}})
          result (async/<!! result-ch)]
      (info "  结果:" (pr-str result)))

    (success "专家池路由演示完成")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 4: Complete Dialog Flow
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-complete-flow
  "Demonstrate complete dialog flow."
  []
  (section "Demo 4: 完整对话流程")

  (let [;; Create bus
        bus (bus/create-bus)

        ;; Create metrics collector
        metrics-collector (metrics/create-collector {:id "demo-metrics"})

        ;; Create router with all components
        router (router/create-intelligent-router
                {:bus bus
                 :metrics metrics-collector
                 :register-defaults true
                 :classification-threshold 0.3})

        ;; Test messages
        test-dialogs
        [{:msg "Hello! 你好！"
          :desc "普通对话"}

         {:msg "Write a function to calculate fibonacci"
          :desc "代码生成请求"}

         {:msg "Debug this error: Cannot read property of undefined"
          :desc "调试请求"}

         {:msg "Search for Clojure core.async tutorial"
          :desc "搜索请求"}

         {:msg "帮我写一个排序算法"
          :desc "中文代码生成请求"}]]

    ;; Start router
    (router/start-router! router)
    (info "智能路由器已启动")

    (subsection "处理对话消息")

    (doseq [{:keys [msg desc]} test-dialogs]
      (info (str "\n" desc ":"))
      (info (str "  消息: " msg))

      (let [response (router/send-message router msg {})]
        (info (str "  响应: " (:message response)))
        (info (str "  任务类型: " (name (:task-type response "unknown"))))
        (info (str "  置信度: " (format "%.2f" (:confidence response 0))))))

    ;; Get metrics
    (subsection "系统指标")
    (let [rm (router/router-metrics router)]
      (info "总处理数:" (:total-processed rm 0))
      (info "成功数:" (:total-succeeded rm 0)))

    ;; Stop router
    (router/stop-router! router)

    (success "完整对话流程演示完成")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 5: Multi-task Detection
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-multi-task
  "Demonstrate multi-task detection."
  []
  (section "Demo 5: 多任务检测")

  (let [multi-task-msg "Write a function to sort a list and also search for best sorting algorithms"]

    (info "消息:" multi-task-msg)
    (info "\n检测到的任务:")

    (let [tasks (classifier/detect-multiple-tasks multi-task-msg)]
      (doseq [[idx task] (map-indexed vector tasks)]
        (info (str "\n  任务 " (inc idx) ":"))
        (info (str "    类型: " (name (:type task))))
        (info (str "    置信度: " (format "%.2f" (:confidence task)))))))

  (success "多任务检测演示完成"))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 6: Load Balancing & Circuit Breaker
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-resilience
  "Demonstrate load balancing and circuit breaker."
  []
  (section "Demo 6: 负载均衡 & 熔断器")

  (subsection "负载均衡")
  (let [balancer (balancer/create-balancer {:strategy :least-connections})]

    ;; Add targets
    (doseq [id ["agent-1" "agent-2" "agent-3"]]
      (balancer/add-target! balancer (balancer/make-target {:id id :weight 5})))

    ;; Simulate load
    (balancer/update-load! balancer "agent-1" :inc)
    (balancer/update-load! balancer "agent-1" :inc)
    (balancer/update-load! balancer "agent-2" :inc)

    (info "负载分布:")
    (doseq [target (balancer/list-targets balancer)]
      (info (str "  " (:id target) ": 负载=" (:active @(:load target)))))

    (info "\n选择结果 (5次):")
    (doseq [i (range 5)]
      (let [selected (balancer/select-target balancer)]
        (info (str "  选择 " (inc i) ": " (:id selected)))))

    (success "负载均衡演示完成"))

  (subsection "熔断器")
  (let [breaker (cb/create-circuit-breaker
                 {:failure-threshold 3
                  :timeout-ms 5000
                  :success-threshold 2})]

    (info "初始状态:" (name (cb/current-state breaker)))

    ;; Simulate failures
    (info "\n模拟3次失败...")
    (dotimes [i 3]
      (cb/with-circuit-breaker breaker
        (fn [] (throw (Exception. (str "Failure " (inc i))))))
      (info (str "  失败 " (inc i) ", 状态: " (name (cb/current-state breaker)))))

    (info "\n熔断器已打开，尝试执行:")
    (let [result (cb/with-circuit-breaker breaker (fn [] :success))]
      (info "  结果:" (if (:circuit-breaker result) "被拒绝 (熔断器打开)" result)))

    ;; Reset
    (cb/reset! breaker)
    (info "\n重置后状态:" (name (cb/current-state breaker)))

    (success "熔断器演示完成")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Main Entry Point
;; ══════════════════════════════════════════════════════════════════════════════

(defn run-all-demos
  "Run all demos."
  []
  (println "\n" (apply str (repeat 70 "═")))
  (println "       智能对话系统 - 自动任务分类与路由演示")
  (println "       Intelligent Dialog System - Auto Task Classification & Routing")
  (println (apply str (repeat 70 "═")))

  (demo-task-classification)
  (demo-context-extraction)
  (demo-specialist-pool)
  (demo-complete-flow)
  (demo-multi-task)
  (demo-resilience)

  (println "\n" (apply str (repeat 70 "═")))
  (println "       所有演示完成！")
  (println (apply str (repeat 70 "═")))
  (println "\n  系统组件:")
  (println "    ✅ 任务分类器 - 自动识别任务类型")
  (println "    ✅ 智能路由器 - 分发到合适的专家")
  (println "    ✅ 专家池 - 专业任务处理")
  (println "    ✅ 负载均衡 - 最少连接策略")
  (println "    ✅ 熔断器 - 故障保护")
  (println "    ✅ 指标收集 - 性能监控")
  (println (apply str (repeat 70 "═"))))

(defn -main
  "Main entry point."
  []
  (run-all-demos))

(comment
  ;; Run in REPL
  (run-all-demos)

  ;; Run individual demos
  (demo-task-classification)
  (demo-specialist-pool)
  (demo-complete-flow))
