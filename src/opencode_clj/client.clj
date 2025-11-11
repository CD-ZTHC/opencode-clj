(ns opencode-clj.client
  "HTTP client wrapper for opencode-server API"
  (:require [clj-http.client :as http]
            [clojure.string :as str]))

(def ^:dynamic *session* nil)

(defn session-id [session-id]
  (if (map? session-id) (:id session-id) session-id))

(defn default-opts
  "Default HTTP client options"
  []
  {:throw-exceptions false
   :as               :json
   :coerce           :always
   :content-type     :json
   :accept           :json})

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
        body (:body response)]
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
    (-> (http/get url opts)
        parse-response)))

(defn post-request
  "Make POST request to opencode-server"
  [client endpoint body & [params]]
  (let [url (build-url (:base-url client) endpoint)
        opts (-> (default-opts)
                 (merge (:http-opts client))
                 (assoc :form-params body)
                 (add-query-params params))]
    (-> (http/post url opts)
        parse-response)))

(defn patch-request
  "Make PATCH request to opencode-server"
  [client endpoint body & [params]]
  (let [url (build-url (:base-url client) endpoint)
        opts (-> (default-opts)
                 (merge (:http-opts client))
                 (assoc :form-params body)
                 (add-query-params params))]
    (-> (http/patch url opts)
        parse-response)))

(defn put-request
  "Make PUT request to opencode-server"
  [client endpoint body & [params]]
  (let [url (build-url (:base-url client) endpoint)
        opts (-> (default-opts)
                 (merge (:http-opts client))
                 (assoc :form-params body)
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
