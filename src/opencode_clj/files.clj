(ns opencode-clj.files
  "File operations for opencode-server"
  (:require [opencode-clj.client :as http]
            [opencode-clj.utils :as utils]))

(defn list-files
  "List files and directories at a given path"
  [client {:keys [path]}]
  (utils/validate-required {:path path} [:path])
  (-> (http/get-request client "/file" {:path path})
      utils/handle-response))

(defn read-file
  "Read a file's content"
  [client {:keys [path]}]
  (utils/validate-required {:path path} [:path])
  (-> (http/get-request client "/file/content" {:path path})
      utils/handle-response))

(defn get-file-status
  "Get file status"
  [client & [params]]
  (-> (http/get-request client "/file/status" params)
      utils/handle-response))

(defn find-text
  "Find text in files"
  [client pattern & [params]]
  (utils/validate-required {:pattern pattern} [:pattern])
  (-> (http/get-request client "/find" (merge {:pattern pattern} params))
      utils/handle-response))

(defn find-files
  "Find files by query"
  [client query & [params]]
  (utils/validate-required {:query query} [:query])
  (-> (http/get-request client "/find/file" (merge {:query query} params))
      utils/handle-response))

(defn find-symbols
  "Find workspace symbols"
  [client query & [params]]
  (utils/validate-required {:query query} [:query])
  (-> (http/get-request client "/find/symbol" (merge {:query query} params))
      utils/handle-response))