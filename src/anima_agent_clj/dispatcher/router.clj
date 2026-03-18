(ns anima-agent-clj.dispatcher.router
  "Routing strategies for dispatching tasks to agents.")

(defn round-robin
  "Select a target from a list of targets using round-robin."
  [targets last-idx]
  (let [idx (mod (inc @last-idx) (count targets))]
    (reset! last-idx idx)
    (nth targets idx)))

(defn constant-hashing
  "Consistent hashing strategy based on session-id."
  [targets session-id]
  (let [hash-code (hash session-id)
        idx (mod hash-code (count targets))]
    (nth targets idx)))
