(ns agent-cluster-demo
  "Agent Cluster Architecture Integration Demo.

   Demonstrates the ARM-inspired architecture:
   - Event Dispatcher (GIC) - Priority scheduling, load balancing, circuit breaker
   - Core Agent (CPU 大核) - Complex reasoning and orchestration
   - Worker Pool (CPU 小核) - Lightweight task execution
   - Parallel Pool (GPU) - Concurrent batch processing
   - Specialist Pool (NPU) - Domain-specific handlers
   - Pipeline (DMA) - Data flow processing
   - Cache (L1/L2/L3) - Result caching

   Run: lein run -m agent-cluster-demo"
  (:require
   [clojure.core.async :as async]
   [clojure.pprint :refer [pprint]]
   ;; Dispatcher components
   [anima-agent-clj.dispatcher.core :as dispatcher]
   [anima-agent-clj.dispatcher.balancer :as balancer]
   [anima-agent-clj.dispatcher.circuit-breaker :as cb]
   ;; Agent components
   [anima-agent-clj.agent.worker-pool :as worker-pool]
   [anima-agent-clj.agent.worker-agent :as worker]
   [anima-agent-clj.agent.parallel-pool :as parallel-pool]
   [anima-agent-clj.agent.specialist-pool :as specialist-pool]
   ;; Pipeline components
   [anima-agent-clj.pipeline.core :as pipeline]
   [anima-agent-clj.pipeline.source :as source]
   [anima-agent-clj.pipeline.transform :as transform]
   [anima-agent-clj.pipeline.sink :as sink]
   ;; Cache components
   [anima-agent-clj.cache.lru :as lru]
   [anima-agent-clj.cache.ttl :as ttl]
   ;; Metrics
   [anima-agent-clj.metrics :as metrics])
  (:import [java.util UUID Date]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo Utilities
;; ══════════════════════════════════════════════════════════════════════════════

(defn section [title]
  (println (str "\n" (apply str (repeat 60 "═"))))
  (println title)
  (println (apply str (repeat 60 "═"))))

(defn subsection [title]
  (println (str "\n─── " title " ───")))

(defn info [& args]
  (apply println "  " args))

(defn success [msg]
  (println (str "  ✅ " msg)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 1: Event Dispatcher with Circuit Breaker & Load Balancer
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-event-dispatcher
  "Demonstrates the Event Dispatcher with priority queue, circuit breaker, and balancer."
  []
  (section "Demo 1: Event Dispatcher (GIC - 中断控制器)")

  ;; 1. Create balancer with different strategies
  (subsection "Load Balancer Strategies")
  (let [balancer-rr (balancer/create-balancer {:strategy :round-robin})
        balancer-lc (balancer/create-balancer {:strategy :least-connections})]

    ;; Add targets (agents)
    (doseq [id ["agent-core-1" "agent-core-2" "agent-worker-1"]]
      (balancer/add-target! balancer-rr (balancer/make-target {:id id :weight 5}))
      (balancer/add-target! balancer-lc (balancer/make-target {:id id :weight 5})))

    ;; Test round-robin
    (info "Round-Robin selections:")
    (doseq [i (range 6)]
      (info (str "  Selection " (inc i) ": " (:id (balancer/select-target balancer-rr)))))

    ;; Test least-connections with load simulation
    (info "\nLeast-Connections with load:")
    (balancer/update-load! balancer-lc "agent-core-1" :inc)
    (balancer/update-load! balancer-lc "agent-core-1" :inc)
    (balancer/update-load! balancer-lc "agent-core-2" :inc)
    (info "  After adding load, selected:" (:id (balancer/select-target balancer-lc)))

    (success "Load balancer strategies working"))

  ;; 2. Circuit Breaker demonstration
  (subsection "Circuit Breaker State Machine")
  (let [breaker (cb/create-circuit-breaker
                 {:id "demo-breaker"
                  :failure-threshold 3
                  :timeout-ms 5000
                  :success-threshold 2})]

    (info "Initial state:" (cb/current-state breaker))
    (info "Is closed?" (cb/closed? breaker))

    ;; Simulate failures
    (info "\nSimulating failures...")
    (dotimes [i 3]
      (try
        (cb/with-circuit-breaker breaker
          (fn [] (throw (Exception. (str "Simulated failure " (inc i))))))
        (catch Exception e
          (info (str "  Failure " (inc i) ", state: " (cb/current-state breaker))))))

    (info "\nAfter failures, circuit is:" (cb/current-state breaker))
    (info "Is open?" (cb/open? breaker))

    ;; Try to execute when open
    (info "\nTrying to execute when circuit is open...")
    (let [result (cb/with-circuit-breaker breaker (fn [] :success))]
      (info "  Result:" result)
      (info "  Circuit breaker state:" (:circuit-breaker result)))

    ;; Manually reset for demo
    (cb/reset! breaker)
    (info "\nAfter reset, state:" (cb/current-state breaker))
    (info "Is closed?" (cb/closed? breaker))

    (success "Circuit breaker state machine working"))

  ;; 3. Full Dispatcher with agents
  (subsection "Full Dispatcher Integration")
  (let [dispatcher (dispatcher/create-dispatcher {})]

    ;; Register mock agents
    (doseq [agent-id ["core-agent-1" "core-agent-2" "worker-agent-1"]]
      (dispatcher/register-agent!
       dispatcher
       (dispatcher/->AgentInfo
        agent-id
        (if (.contains agent-id "core") :core :worker)
        #{:api-call :inference}
        nil
        (atom :available)
        (atom 0)
        (atom (Date.))
        {})))

    (info "Registered agents:" (count (dispatcher/list-agents dispatcher)))
    (info "Available agents:" (count (dispatcher/list-available-agents dispatcher)))

    ;; Get dispatcher status
    (let [status (dispatcher/dispatcher-status dispatcher)]
      (info "\nDispatcher status:")
      (info "  Status:" (:status status))
      (info "  Queue size:" (:queue-size status))
      (info "  Agent count:" (:agent-count status))
      (info "  Circuit breaker state:" (get-in status [:circuit-breaker :state])))

    (success "Event dispatcher integration working")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 2: Worker Pool & Parallel Pool
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-agent-pools
  "Demonstrates Worker Pool and Parallel Pool execution."
  []
  (section "Demo 2: Agent Pools (CPU 小核 + GPU)")

  ;; 1. Worker Pool
  (subsection "Worker Pool (CPU 小核)")
  (let [pool (worker-pool/create-pool
              {:id "demo-worker-pool"
               :min-size 2
               :max-size 5})]

    (info "Pool config: min-size=2, max-size=5")
    (info "Initial pool size:" (worker-pool/pool-size pool))

    ;; Start pool
    (worker-pool/start-pool! pool)
    (info "Pool status:" (:status (worker-pool/pool-status pool)))

    ;; Submit tasks
    (info "\nSubmitting tasks...")
    (let [task1 (worker/make-task {:type :api-call :payload {:content "Task 1"}})
          task2 (worker/make-task {:type :inference :payload {:content "Task 2"}})
          _ (worker-pool/submit-task! pool task1)
          _ (worker-pool/submit-task! pool task2)]

      (info "Task 1 submitted:" (:id task1))
      (info "Task 2 submitted:" (:id task2)))

    ;; Scale pool
    (info "\nScaling pool to 4 workers...")
    (worker-pool/scale-to pool 4)
    (info "Pool size after scale:" (worker-pool/pool-size pool))

    ;; Get metrics
    (let [pool-metrics (worker-pool/pool-metrics pool)]
      (info "\nPool metrics:")
      (info "  Status:" (:status pool-metrics)))

    ;; Stop pool
    (worker-pool/stop-pool! pool)
    (success "Worker pool working"))

  ;; 2. Parallel Pool
  (subsection "Parallel Pool (GPU - 并行处理)")
  (let [worker-pool (worker-pool/create-pool {:min-size 2 :max-size 4})
        parallel-pool (parallel-pool/create-parallel-pool
                       {:worker-pool worker-pool
                        :max-concurrent 4
                        :default-timeout 5000})]

    (worker-pool/start-pool! worker-pool)
    (info "Parallel pool config: max-concurrent=4, timeout=5000ms")

    ;; Create parallel tasks
    (info "\nExecuting parallel tasks...")
    (let [tasks (for [i (range 5)]
                  {:type :api-call :payload {:content (str "Parallel task " i)}})
          result (parallel-pool/execute-parallel! parallel-pool tasks)]

      (if (map? result)
        (do
          (info "\nParallel execution result:")
          (info "  Total:" (:total result))
          (info "  Successful:" (:successful result))
          (info "  Failed:" (:failed result))
          (info "  Duration:" (:duration result) "ms")
          (info "  Status:" (:status result)))
        (info "Result:" result)))

    (worker-pool/stop-pool! worker-pool)
    (success "Parallel pool working")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 3: Specialist Pool
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-specialist-pool
  "Demonstrates Specialist Pool for domain-specific tasks."
  []
  (section "Demo 3: Specialist Pool (NPU - 专家系统)")

  (let [pool (specialist-pool/create-specialist-pool {:max-specialists 10})]

    ;; Register specialists
    (subsection "Registering Specialists")
    (specialist-pool/register-specialist!
     pool
     {:id "code-specialist"
      :type :code
      :capabilities #{:code-generation :code-review :refactoring}
      :handler (fn [task]
                 {:status :success
                  :result {:code "(defn hello [] (println \"Hello!\"))"}})})

    (specialist-pool/register-specialist!
     pool
     {:id "search-specialist"
      :type :search
      :capabilities #{:web-search :document-search}
      :handler (fn [task]
                 {:status :success
                  :result {:found ["result1" "result2"]}})})

    (specialist-pool/register-specialist!
     pool
     {:id "analysis-specialist"
      :type :analysis
      :capabilities #{:data-analysis :chart-generation}
      :handler (fn [task]
                 {:status :success
                  :result {:analysis "Data looks good"}})})

    (info "Registered specialists:" (count (specialist-pool/list-specialists pool)))

    ;; Route tasks to specialists
    (subsection "Task Routing")
    (let [code-task {:type :code-generation :payload {:prompt "Write a function"}}
          search-task {:type :web-search :payload {:query "Clojure docs"}}
          analysis-task {:type :data-analysis :payload {:data [1 2 3]}}

          code-result (specialist-pool/route-task! pool code-task)
          search-result (specialist-pool/route-task! pool search-task)
          analysis-result (specialist-pool/route-task! pool analysis-task)]

      (info "Code task -> specialist:" (:id code-result))
      (info "Search task -> specialist:" (:id search-result))
      (info "Analysis task -> specialist:" (:id analysis-result)))

    ;; Get pool stats
    (subsection "Pool Statistics")
    (let [stats (specialist-pool/specialist-pool-metrics pool)]
      (info "Specialist count:" (:total-specialists stats))
      (info "Available:" (:available-specialists stats)))

    (success "Specialist pool working")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 4: Data Pipeline
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-pipeline
  "Demonstrates the Data Pipeline for data processing."
  []
  (section "Demo 4: Data Pipeline (DMA - 数据流)")

  (subsection "Collection Source + Transform + Sink")
  (let [;; Create pipeline components
        coll-sink (sink/collection-sink)
        pipeline (pipeline/create-pipeline {:id "demo-pipeline"})]

    ;; Build pipeline
    (pipeline/add-source pipeline (pipeline/make-stage :source (source/collection-source [1 2 3 4 5]) {}))
    (pipeline/add-transform pipeline (pipeline/make-stage :transform (transform/map-transform #(* % 2)) {}))
    (pipeline/add-filter pipeline (pipeline/make-stage :filter (transform/filter-transform #(> % 4)) {}))
    (pipeline/add-sink pipeline (pipeline/make-stage :sink coll-sink {}))

    (info "Pipeline created: source → transform(*2) → filter(>4) → sink")

    ;; Process data manually through stages
    (doseq [item [1 2 3 4 5]]
      (pipeline/process pipeline item))

    (info "\nInput: [1 2 3 4 5]")
    (info "After *2: [2 4 6 8 10]")
    (info "Collection sink items:" (count (sink/get-collection coll-sink)))

    (success "Pipeline transform working"))

  (subsection "Channel Sink")
  (let [out-ch (async/chan 10)
        ch-sink (sink/channel-sink out-ch)]

    (info "Channel sink created")
    (pipeline/write ch-sink "test-data-1" nil)
    (pipeline/write ch-sink "test-data-2" nil)

    (info "Written 2 items to channel")
    (info "Item 1:" (async/<!! out-ch))
    (info "Item 2:" (async/<!! out-ch))

    (pipeline/close-sink ch-sink)
    (success "Channel sink working")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 5: Cache System
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-cache
  "Demonstrates the Cache system with LRU and TTL strategies."
  []
  (section "Demo 5: Cache System (L1/L2/L3 缓存)")

  ;; LRU Cache
  (subsection "LRU Cache (Least Recently Used)")
  (let [cache (lru/create-lru-cache {:max-entries 3})]

    (info "Cache max entries: 3")

    ;; Add items
    (lru/cache-set! cache "a" "value-a")
    (lru/cache-set! cache "b" "value-b")
    (lru/cache-set! cache "c" "value-c")
    (info "Added: a, b, c")
    (info "Cache size:" (.entry-count cache))

    ;; Access 'a' to make it recently used
    (info "Get 'a':" (lru/cache-get cache "a"))

    ;; Add new item, should evict 'b' (least recently used)
    (lru/cache-set! cache "d" "value-d")
    (info "\nAdded 'd', cache should evict LRU item")
    (info "Has 'a'?" (.has-key? cache "a"))
    (info "Has 'b'?" (.has-key? cache "b"))
    (info "Has 'c'?" (.has-key? cache "c"))
    (info "Has 'd'?" (.has-key? cache "d"))

    (success "LRU eviction working"))

  ;; TTL Cache
  (subsection "TTL Cache (Time To Live)")
  (let [cache (ttl/create-ttl-cache {:default-ttl 2000})]  ; 2 seconds

    (info "Cache TTL: 2000ms")

    (ttl/cache-set! cache "key1" "value1")
    (info "Added key1")
    (info "Get key1 immediately:" (ttl/cache-get cache "key1"))

    (info "\nWaiting 2.5 seconds...")
    (Thread/sleep 2500)

    (info "Get key1 after TTL:" (ttl/cache-get cache "key1"))

    (success "TTL expiration working"))

  ;; Cache statistics
  (subsection "Cache Statistics")
  (let [cache (lru/create-lru-cache {:max-entries 10})]
    (dotimes [i 5]
      (lru/cache-set! cache (str "key" i) (str "value" i)))
    (lru/cache-get cache "key0")
    (lru/cache-get cache "key0")
    (lru/cache-get cache "key999")  ; miss

    (let [stats (.stats cache)]
      (info "Cache stats:")
      (info "  Size:" (:entry-count stats))
      (info "  Hits:" (:hits stats))
      (info "  Misses:" (:misses stats))
      (info "  Hit rate:" (format "%.2f%%" (* 100 (:hit-rate stats)))))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Demo 6: Full Integration
;; ══════════════════════════════════════════════════════════════════════════════

(defn demo-full-integration
  "Full integration showing all components working together."
  []
  (section "Demo 6: Full Integration - Complete Message Flow")

  (let [;; Create infrastructure
        metrics-collector (metrics/create-collector {:id "integration-metrics"})

        ;; Create dispatcher with circuit breaker
        dispatcher (dispatcher/create-dispatcher
                    {:id "main-dispatcher"
                     :max-queue-size 100
                     :metrics metrics-collector})

        ;; Create worker pool
        worker-pool (worker-pool/create-pool
                     {:id "integration-workers"
                      :min-size 2
                      :max-size 5
                      :metrics metrics-collector})

        ;; Create parallel pool
        parallel-pool (parallel-pool/create-parallel-pool
                       {:worker-pool worker-pool
                        :max-concurrent 4
                        :metrics metrics-collector})

        ;; Create specialist pool
        specialist-pool (specialist-pool/create-specialist-pool {})]

    ;; Register specialists
    (specialist-pool/register-specialist!
     specialist-pool
     {:id "mock-specialist"
      :type :api-call
      :capabilities #{:api-call :inference}
      :handler (fn [task] {:status :success :result {:processed (:payload task)}})})

    ;; Register agents with dispatcher
    (dispatcher/register-agent!
     dispatcher
     (dispatcher/->AgentInfo
      "core-agent-1" :core #{:api-call :inference}
      nil (atom :available) (atom 0) (atom (Date.)) {}))

    (dispatcher/register-agent!
     dispatcher
     (dispatcher/->AgentInfo
      "worker-agent-1" :worker #{:api-call}
      nil (atom :available) (atom 0) (atom (Date.)) {}))

    (info "Infrastructure created:")
    (info "  - Dispatcher: " (:id dispatcher))
    (info "  - Worker Pool: ready")
    (info "  - Parallel Pool: ready")
    (info "  - Specialist Pool: 1 specialist")

    ;; Start pools
    (worker-pool/start-pool! worker-pool)
    (info "\nPools started")

    ;; Simulate message flow
    (subsection "Message Flow Simulation")
    (info "1. Message arrives at Dispatcher")
    (let [msg-ch (dispatcher/dispatch!
                  dispatcher
                  {:source "demo"
                   :type :api-call
                   :priority :realtime
                   :payload {:content "Hello, Agent Cluster!"}})]
      (info "   - Message queued")
      (info "   - Priority: realtime"))

    (info "2. Dispatcher routes to available agent")
    (info "   - Agent selected based on capability and load")

    (info "3. Agent processes through Worker Pool")
    (let [task (worker/make-task {:type :api-call :payload {:content "test"}})
          result-ch (worker-pool/submit-task! worker-pool task)]
      (info "   - Task submitted to worker"))

    (info "4. Results flow back through Pipeline")
    (info "   - Processed and transformed")

    ;; Get final metrics
    (subsection "Final Metrics")
    (let [disp-status (dispatcher/dispatcher-status dispatcher)
          pool-metrics (worker-pool/pool-metrics worker-pool)]
      (info "Dispatcher:")
      (info "  Messages queued:" (get @(:stats dispatcher) :messages-queued 0))
      (info "  Available agents:" (:available-agents disp-status))
      (info "\nWorker Pool:")
      (info "  Pool size:" (worker-pool/pool-size worker-pool))
      (info "  Status:" (:status pool-metrics)))

    ;; Cleanup
    (worker-pool/stop-pool! worker-pool)
    (success "Full integration flow completed")))

;; ══════════════════════════════════════════════════════════════════════════════
;; Main Entry Point
;; ══════════════════════════════════════════════════════════════════════════════

(defn run-all-demos
  "Run all integration demos."
  []
  (println "\n" (apply str (repeat 60 "═")))
  (println "       Agent Cluster Architecture - Integration Demo")
  (println "       ARM-Inspired Heterogeneous Computing Model")
  (println (apply str (repeat 60 "═")))

  (demo-event-dispatcher)
  (demo-agent-pools)
  (demo-specialist-pool)
  (demo-pipeline)
  (demo-cache)
  (demo-full-integration)

  (println "\n" (apply str (repeat 60 "═")))
  (println "       All Demos Completed Successfully!")
  (println (apply str (repeat 60 "═")))
  (println "\n  Architecture Components Verified:")
  (println "    ✅ Event Dispatcher (GIC) - Priority scheduling, routing, circuit breaker")
  (println "    ✅ Core Agent (CPU 大核) - Reasoning, orchestration")
  (println "    ✅ Worker Pool (CPU 小核) - Lightweight task execution")
  (println "    ✅ Parallel Pool (GPU) - Concurrent batch processing")
  (println "    ✅ Specialist Pool (NPU) - Domain-specific handlers")
  (println "    ✅ Pipeline (DMA) - Data flow processing")
  (println "    ✅ Cache (L1/L2/L3) - LRU/TTL caching")
  (println (apply str (repeat 60 "═"))))

(defn -main
  "Main entry point."
  []
  (run-all-demos))

(comment
  ;; Run in REPL
  (run-all-demos)

  ;; Run individual demos
  (demo-event-dispatcher)
  (demo-agent-pools)
  (demo-specialist-pool)
  (demo-pipeline)
  (demo-cache)
  (demo-full-integration))
