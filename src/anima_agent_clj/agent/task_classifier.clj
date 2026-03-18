(ns anima-agent-clj.agent.task-classifier
  "Intelligent Task Classifier for automatic task type detection.

   Analyzes user messages and classifies them into task types:
   - :code-generation  - Writing new code
   - :code-review      - Reviewing existing code
   - :code-debug       - Debugging issues
   - :code-refactor    - Refactoring code
   - :web-search       - Web search queries
   - :documentation    - Documentation tasks
   - :data-analysis    - Data analysis tasks
   - :general-chat     - General conversation
   - :api-call         - API calls to OpenCode

   Usage:
   (classify-task \"Write a function to sort a list\")
   => {:type :code-generation :confidence 0.9 :keywords [\"write\" \"function\"]}"
  (:require [clojure.string :as str]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Type Definitions
;; ══════════════════════════════════════════════════════════════════════════════

(def task-type-patterns
  "Patterns for classifying task types.
   Format: {:type {:keywords [...] :patterns [...] :priority n}}"
  {:code-generation
   {:keywords ["write" "create" "implement" "build" "develop" "编写" "创建" "实现" "生成" "代码"]
    :patterns [#"(?i)write\s+(a|an|the)?\s*(function|method|class|module|component)"
               #"(?i)implement\s+(a|an|the)?\s*\w+"
               #"(?i)create\s+(a|an|the)?\s*(function|class|api)"
               #"(?i)build\s+(a|an|the)?\s*\w+"
               #"编写" #"创建" #"实现" #"生成代码"]
    :priority 10}

   :code-review
   {:keywords ["review" "check" "analyze" "audit" "审查" "检查" "分析代码"]
    :patterns [#"(?i)review\s+(this|the|my)?\s*code"
               #"(?i)check\s+(this|the|my)?\s*code"
               #"(?i)analyze\s+(this|the|my)?\s*code"
               #"(?i)(code|code)\s*review"
               #"审查代码" #"检查代码" #"代码审查"]
    :priority 8}

   :code-debug
   {:keywords ["debug" "fix" "error" "bug" "issue" "problem" "调试" "修复" "错误" "bug"]
    :patterns [#"(?i)debug\s+(this|the|my)?\s*(code|error|issue)"
               #"(?i)fix\s+(this|the|my)?\s*(error|bug|issue)"
               #"(?i)(error|exception|bug)\s+(in|with)"
               #"调试" #"修复" #"错误" #"报错"]
    :priority 9}

   :code-refactor
   {:keywords ["refactor" "optimize" "improve" "clean" "重构" "优化" "改进"]
    :patterns [#"(?i)refactor\s+(this|the|my)?\s*code"
               #"(?i)optimize\s+(this|the|my)?\s*(code|performance)"
               #"(?i)improve\s+(this|the|my)?\s*code"
               #"重构" #"优化" #"改进代码"]
    :priority 7}

   :web-search
   {:keywords ["search" "find" "lookup" "google" "搜索" "查找" "搜索"]
    :patterns [#"(?i)search\s+(for|about|on)"
               #"(?i)find\s+(information|docs|documentation)"
               #"(?i)look\s+up"
               #"搜索" #"查找" #"搜索一下"]
    :priority 6}

   :documentation
   {:keywords ["document" "docs" "readme" "explain" "describe" "文档" "说明" "解释"]
    :patterns [#"(?i)(write|create|generate)\s+(documentation|docs|readme)"
               #"(?i)document\s+(this|the|my)?\s*code"
               #"(?i)explain\s+(how|what|why)"
               #"写文档" #"生成文档" #"说明文档"]
    :priority 5}

   :data-analysis
   {:keywords ["analyze" "data" "statistics" "chart" "graph" "分析" "数据" "统计" "图表"]
    :patterns [#"(?i)analyze\s+(this|the|my)?\s*(data|dataset)"
               #"(?i)(show|create|generate)\s+(a|the)?\s*(chart|graph|plot)"
               #"分析数据" #"数据统计" #"生成图表"]
    :priority 6}

   :test-generation
   {:keywords ["test" "spec" "testing" "unit test" "测试" "单元测试"]
    :patterns [#"(?i)(write|create|generate)\s+(tests?|specs?)"
               #"(?i)add\s+tests?"
               #"(?i)unit\s+tests?"
               #"写测试" #"生成测试" #"添加测试"]
    :priority 7}

   :api-call
   {:keywords ["api" "call" "request" "fetch" "invoke"]
    :patterns [#"(?i)(call|invoke|fetch)\s+(the|an?)?\s*api"
               #"(?i)api\s+(call|request|endpoint)"
               #"(?i)(get|post|put|delete)\s+(request|call)"]
    :priority 8}

   :general-chat
   {:keywords ["hello" "hi" "hey" "thanks" "thank" "你好" "谢谢" "您好"]
    :patterns [#"(?i)^(hello|hi|hey|good\s+(morning|afternoon|evening))"
               #"你好" #"您好" #"谢谢"]
    :priority 1}})

;; ══════════════════════════════════════════════════════════════════════════════
;; Classification Logic
;; ══════════════════════════════════════════════════════════════════════════════

(defn- normalize-text
  "Normalize text for classification."
  [text]
  (-> text
      (str/lower-case)
      (str/replace #"[^\w\s\u4e00-\u9fff]" " ")
      (str/replace #"\s+" " ")
      (str/trim)))

(defn- count-keyword-matches
  "Count how many keywords match in text."
  [text keywords]
  (let [text-lower (str/lower-case text)]
    (count (filter #(str/includes? text-lower (str/lower-case %)) keywords))))

(defn- check-patterns
  "Check if any pattern matches the text."
  [text patterns]
  (some #(re-find % text) patterns))

(defn- score-task-type
  "Calculate score for a task type given the message."
  [message task-type-def]
  (let [{:keys [keywords patterns priority]} task-type-def
        normalized (normalize-text message)
        keyword-score (* 10 (count-keyword-matches message keywords))
        pattern-score (if (check-patterns message patterns) 50 0)
        total (+ keyword-score pattern-score (* priority 5))]
    {:score total
     :keyword-matches (count-keyword-matches message keywords)
     :pattern-match (boolean (check-patterns message patterns))}))

(defn classify-task
  "Classify a message into a task type.

   Returns:
   {:type :code-generation
    :confidence 0.85
    :scores {:code-generation 85 :code-review 20 ...}
    :detected-keywords [\"write\" \"function\"]}

   Example:
   (classify-task \"Write a function to sort a list\")
   => {:type :code-generation :confidence 0.9 ...}"
  ([message]
   (classify-task message {}))
  ([message opts]
   (let [scores (into {}
                      (for [[type def] task-type-patterns]
                        [type (score-task-type message def)]))
         sorted (sort-by (comp :score second) > scores)
         [best-type best-result] (first sorted)
         second-best (second sorted)
         total-score (apply + (map (comp :score second) scores))
         confidence (if (pos? total-score)
                      (/ (:score best-result) total-score)
                      0.0)
         ;; Find matched keywords
         matched-keywords (->> (get-in task-type-patterns [best-type :keywords])
                               (filter #(str/includes? (str/lower-case message)
                                                       (str/lower-case %))))]
     {:type best-type
      :confidence (double (min 1.0 confidence))
      :scores (into {} (map (fn [[k v]] [k (:score v)]) scores))
      :detected-keywords matched-keywords
      :metadata {:message-length (count message)
                 :has-code-block (boolean (re-find #"`{3}|`" message))}})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Task Extraction
;; ══════════════════════════════════════════════════════════════════════════════

(defn extract-code-blocks
  "Extract code blocks from message."
  [message]
  (let [fenced (map second (re-seq #"`{3}(\w*)\n([\s\S]*?)`{3}" message))
        inline (map second (re-seq #"`([^`]+)`" message))]
    {:fenced fenced
     :inline inline
     :has-code? (or (seq fenced) (seq inline))}))

(defn extract-language-hint
  "Try to detect programming language from message."
  [message]
  (let [lang-patterns {:clojure [#"\(defn" #"\(def" #"\(ns" #":require"]
                       :java [#"public\s+class" #"private\s+void" #"import\s+java"]
                       :python [#"def\s+\w+\s*\(" #"import\s+\w+" #"from\s+\w+\s+import"]
                       :javascript [#"function\s+\w+\s*\(" #"const\s+\w+\s*=" #"import\s+.*from"]
                       :typescript [#"interface\s+\w+" #"type\s+\w+\s*=" #":\s*\w+\s*[;=]"]
                       :rust [#"fn\s+\w+\s*\(" #"let\s+mut" #"impl\s+\w+"]
                       :go [#"func\s+\w+\s*\(" #"package\s+\w+" #"import\s+\("]}]
    (->> lang-patterns
         (filter (fn [[lang patterns]]
                   (some #(re-find % message) patterns)))
         (map first)
         first)))

(defn extract-task-context
  "Extract additional context from the message."
  [message]
  (let [classification (classify-task message)
        code-blocks (extract-code-blocks message)
        lang-hint (extract-language-hint message)]
    (merge classification
           {:code-blocks code-blocks
            :language-hint lang-hint
            :urgency (cond
                       (re-find #"(?i)(urgent|asap|immediately|紧急|立即)" message) :high
                       (re-find #"(?i)(when\s+you\s+can|no\s+rush|有空)" message) :low
                       :else :normal)})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Multi-task Detection
;; ══════════════════════════════════════════════════════════════════════════════

(defn detect-multiple-tasks
  "Detect if message contains multiple tasks.
   Returns vector of task classifications."
  [message]
  (let [;; Split by common separators
        parts (str/split message #"(?i)(?:also|and|then|additionally|另外|同时|并且|，)")]
    (if (< 1 (count parts))
      (mapv classify-task parts)
      [(classify-task message)])))

;; ══════════════════════════════════════════════════════════════════════════════
;; Multi-task Detection
;; ══════════════════════════════════════════════════════════════════════════════

(defn detect-multiple-tasks
  "Detect if message contains multiple tasks.
   Returns vector of task classifications."
  [message]
  (let [;; Split by common separators
        parts (str/split message #"(?i)(?:also|and|then|additionally|另外|同时|并且|，)")]
    (if (< 1 (count parts))
      (mapv classify-task parts)
      [(classify-task message)])))

;; ══════════════════════════════════════════════════════════════════════════════
;; Utility Functions
;; ══════════════════════════════════════════════════════════════════════════════

(defn get-task-description
  "Get human-readable description for a task type."
  [task-type]
  (case task-type
    :code-generation "代码生成 - 编写新代码"
    :code-review "代码审查 - 检查代码质量"
    :code-debug "代码调试 - 修复错误"
    :code-refactor "代码重构 - 优化改进"
    :web-search "网络搜索 - 查找信息"
    :documentation "文档生成 - 创建文档"
    :data-analysis "数据分析 - 处理数据"
    :test-generation "测试生成 - 编写测试"
    :api-call "API调用 - 外部接口"
    :general-chat "普通对话 - 日常交流"
    "未知任务类型"))

(defn suggest-specialist
  "Suggest which specialist type should handle this task."
  [task-type]
  (case task-type
    (:code-generation :code-review :code-debug :code-refactor :test-generation)
    :code-specialist

    :web-search
    :search-specialist

    :documentation
    :doc-specialist

    :data-analysis
    :analysis-specialist

    :general-chat
    :chat-specialist

    :general-specialist))
