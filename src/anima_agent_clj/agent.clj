(ns anima-agent-clj.agent
  "Message processing agent - Backward Compatibility Layer.

   This namespace provides backward compatibility for the legacy Agent API.
   Internally, it creates and manages a CoreAgent with a WorkerPool.

   The Agent now uses the new Agent Cluster Architecture:
   - CoreAgent: Handles reasoning, orchestration, and memory
   - WorkerPool: Manages WorkerAgents for task execution

   Flow: Bus.inbound → CoreAgent.reason → CoreAgent.orchestrate → Workers → Bus.outbound

   Usage (Legacy API - still supported):
   (def agent (create-agent {:bus bus :opencode-client client}))
   (start-agent agent)
   ;; ... messages flow through ...
   (stop-agent agent)

   For more control, use the new CoreAgent directly:
   (require '[anima-agent-clj.agent.core-agent :as core])
   (def agent (core/create-core-agent {:bus bus :opencode-client client}))"
  (:require
   [anima-agent-clj.agent.core-agent :as core-agent]
   [anima-agent-clj.agent.worker-agent :as worker-agent]
   [anima-agent-clj.agent.worker-pool :as worker-pool]
   [anima-agent-clj.bus :as bus]
   [anima-agent-clj.channel.session :as session]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Re-export Types for Compatibility
;; ══════════════════════════════════════════════════════════════════════════════

;; Legacy Agent record - now wraps CoreAgent
(defrecord Agent
           [bus              ; Bus instance
            opencode-client  ; OpenCode API client {:base-url "..."}
            session-manager  ; SessionStore for managing sessions
            running?         ; atom<boolean>
            core-agent       ; The underlying CoreAgent
            worker])         ; atom - holds reference for compatibility

;; ══════════════════════════════════════════════════════════════════════════════
;; Message Processing (Delegated to CoreAgent)
;; ══════════════════════════════════════════════════════════════════════════════

(defn process-message
  "Process a single InboundMessage.

   This function is provided for backward compatibility.
   In the new architecture, message processing is handled internally by CoreAgent.

   For direct task execution, use worker-agent/submit-task! instead."
  [agent inbound-msg]
  (when-let [ca (:core-agent agent)]
    ;; CoreAgent handles processing automatically via its main loop
    ;; This function is kept for API compatibility but the actual
    ;; processing happens through the bus subscription
    (let [channel-name (:channel inbound-msg)
          content (:content inbound-msg)]
      ;; Publish to inbound bus - CoreAgent will pick it up
      (let [inbound (bus/make-inbound
                     {:channel channel-name
                      :chat-id (:chat-id inbound-msg)
                      :content content
                      :sender-id (:sender-id inbound-msg)
                      :session-key (:session-key inbound-msg)
                      :metadata (:metadata inbound-msg)})]
        (bus/publish-inbound! (:bus agent) inbound)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Agent Lifecycle
;; ══════════════════════════════════════════════════════════════════════════════

(defn start-agent
  "Start the agent - begins consuming messages from bus/inbound.
   Internally starts the CoreAgent and its WorkerPool.
   Returns the agent."
  [agent]
  (when-not @(:running? agent)
    (reset! (:running? agent) true)
    ;; Start the underlying CoreAgent
    (core-agent/start-core-agent! (:core-agent agent)))
  agent)

(defn stop-agent
  "Stop the agent - stops consuming messages.
   Internally stops the CoreAgent and its WorkerPool.
   Returns the agent."
  [agent]
  (reset! (:running? agent) false)
  ;; Stop the underlying CoreAgent
  (when-let [ca (:core-agent agent)]
    (core-agent/stop-core-agent! ca))
  agent)

;; ══════════════════════════════════════════════════════════════════════════════
;; Extended API - Access to New Architecture
;; ══════════════════════════════════════════════════════════════════════════════

(defn get-core-agent
  "Get the underlying CoreAgent for advanced operations."
  [agent]
  (:core-agent agent))

(defn get-worker-pool
  "Get the WorkerPool for direct task submission."
  [agent]
  (when-let [ca (:core-agent agent)]
    (:worker-pool ca)))

(defn agent-status
  "Get detailed agent status including worker pool info."
  [agent]
  (merge
   {:running? @(:running? agent)}
   (when-let [ca (:core-agent agent)]
     (core-agent/core-agent-status ca))))

(defn scale-workers
  "Scale the worker pool to a specific size.
   Only available in the new architecture."
  [agent size]
  (when-let [pool (get-worker-pool agent)]
    (worker-pool/scale-to pool size)))

;; ══════════════════════════════════════════════════════════════════════════════
;; Constructor
;; ══════════════════════════════════════════════════════════════════════════════

(defn create-agent
  "Create a new message processing agent.

   Options:
     :bus              - Bus instance (required)
     :opencode-client  - OpenCode client map {:base-url \"...\"}
     :session-manager  - SessionStore instance (will create if not provided)
     :opencode-url     - OpenCode server URL (default: http://127.0.0.1:9711)
     :pool-config      - WorkerPool configuration (new architecture)
       :min-size       - Minimum workers (default: 1)
       :max-size       - Maximum workers (default: 10)
       :initial-size   - Starting workers (default: 2)

   The agent now uses the Agent Cluster Architecture internally:
   - CoreAgent handles reasoning, orchestration, and memory
   - WorkerPool manages WorkerAgents for parallel task execution"
  [{:keys [bus opencode-client session-manager opencode-url pool-config]
    :or {opencode-url "http://127.0.0.1:9711"}}]
  (let [client-map (or opencode-client {:base-url opencode-url})
        sm (or session-manager (session/create-store))
        ;; Create the underlying CoreAgent
        core (core-agent/create-core-agent
              {:bus bus
               :opencode-client client-map
               :session-manager sm
               :opencode-url opencode-url
               :pool-config pool-config})]
    (->Agent
     bus
     client-map
     sm
     (atom false)
     core
     (atom nil))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Direct Task Submission (New API)
;; ══════════════════════════════════════════════════════════════════════════════

(defn submit-direct-task!
  "Submit a task directly to the worker pool.
   Bypasses the CoreAgent's reasoning/orchestration layer.
   Useful for simple, well-defined tasks.

   Returns a channel that will receive the TaskResult.

   Example:
   (submit-direct-task! agent
     {:type :api-call
      :payload {:opencode-session-id \"xxx\" :content \"Hello\"}})"
  [agent task-spec]
  (when-let [pool (get-worker-pool agent)]
    (let [task (worker-agent/make-task task-spec)]
      (worker-pool/submit-task! pool task))))
