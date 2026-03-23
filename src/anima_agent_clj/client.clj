(ns anima-agent-clj.client
  "HTTP client wrapper for opencode-server API.

   Low-level HTTP operations: get-request, post-request, patch-request,
   put-request, delete-request.

   Session binding: *session*, session-id

   Not typically used directly - use anima-agent-clj.core instead."
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.data.json :as json]))

(def ^:dynamic *session* nil)

(defn session-id
  ([] (session-id *session*))
  ([session-id]
   (if (map? session-id) (:id session-id) session-id)))

(defn default-opts
  "Default HTTP client options"
  []
  {:throw-exceptions false
   :as               :text              ; Use text to handle chunked/streaming responses properly
   :content-type     :json
   :accept           :json
   ;; Timeout settings for chunked/streaming responses
   ;; Use very long timeout for AI operations that may take a while
   :socket-timeout   600000  ; 10 minutes
   :connection-timeout 60000
   :conn-timeout     60000})

(defn build-url
  "Build full URL from base URL and endpoint path"
  [base-url endpoint]
  (let [base (str/replace base-url #"/$" "")                ; Remove trailing slash from base
        path (str/replace endpoint #"^/" "")]               ; Remove leading slash from endpoint
    (str base "/" path)))

(defn add-query-params
  "Add query parameters to request options"
  [opts params]
  (if (seq params)
    (assoc opts :query-params params)
    opts))

(defn parse-response
  "Parse HTTP response and handle errors"
  [response]
  (let [status (:status response)
        body-str (:body response)
        ;; Parse JSON body if present
        body (when (and body-str (string? body-str) (> (count body-str) 0))
              (try
                (json/read-str body-str {:key-fn keyword})
                (catch Exception e
                  (println "  [DEBUG] JSON parse error:" (.getMessage e))
                  body-str)))]
    (case status
      200 {:success true :data body}
      201 {:success true :data body}
      400 {:success false :error :bad-request :data body}
      404 {:success false :error :not-found :data body}
      500 {:success false :error :server-error :data body}
      {:success false :error :unknown :status status :data body})))

(defn get-request
  "Make GET request to opencode-server"
  [client endpoint & [params]]
  (let [url (build-url (:base-url client) endpoint)
        opts (-> (default-opts)
                 (merge (:http-opts client))
                 (add-query-params params))]
    (let [response (http/get url opts)]
      (parse-response response))))

(defn post-request
  "Make POST request to opencode-server"
  [client endpoint body & [params]]
  (let [url (build-url (:base-url client) endpoint)
        ;; Manually encode body to JSON string for reliable encoding
        json-body (json/write-str body)
        opts (-> (default-opts)
                 (merge (:http-opts client))
                 (assoc :body json-body)
                 (add-query-params params))]
    (let [response (http/post url opts)]
      (parse-response response))))

(defn patch-request
  "Make PATCH request to opencode-server"
  [client endpoint body & [params]]
  (let [url (build-url (:base-url client) endpoint)
        json-body (json/write-str body)
        opts (-> (default-opts)
                 (merge (:http-opts client))
                 (assoc :body json-body)
                 (add-query-params params))]
    (-> (http/patch url opts)
        parse-response)))

(defn put-request
  "Make PUT request to opencode-server"
  [client endpoint body & [params]]
  (let [url (build-url (:base-url client) endpoint)
        json-body (json/write-str body)
        opts (-> (default-opts)
                 (merge (:http-opts client))
                 (assoc :body json-body)
                 (add-query-params params))]
    (-> (http/put url opts)
        parse-response)))

(defn delete-request
  "Make DELETE request to opencode-server"
  [client endpoint & [params]]
  (let [url (build-url (:base-url client) endpoint)
        opts (-> (default-opts)
                 (merge (:http-opts client))
                 (add-query-params params))]
    (-> (http/delete url opts)
        parse-response)))
