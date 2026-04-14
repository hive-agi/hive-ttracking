(ns hive-ttracking.budget
  "BC-2 Budgets — declarative budgets, policy, assertion.

   Pure. No I/O. No deps on hive-weave or hive-events.
   See PLAN.md §2 BC-2."
  (:import (java.util.concurrent TimeUnit)))

(defprotocol IBudget
  (within?      [this] "True if now-t0 <= total-ms.")
  (remaining-ms [this] "Milliseconds until deadline, clamped ≥ 0.")
  (policy       [this] "One of #{:hard :soft :partial}."))

(defrecord Budget [total-ms policy-kw started-at-nanos]
  IBudget
  (within? [_]
    (let [elapsed-ms (/ (- (System/nanoTime) started-at-nanos) 1e6)]
      (<= elapsed-ms total-ms)))
  (remaining-ms [_]
    (let [elapsed-ms (/ (- (System/nanoTime) started-at-nanos) 1e6)]
      (max 0 (long (- total-ms elapsed-ms)))))
  (policy [_] policy-kw))

(defn make-budget
  "Construct a Budget. Valid policies: #{:hard :soft :partial}."
  ([total-ms] (make-budget total-ms :partial))
  ([total-ms policy-kw]
   {:pre [(pos? total-ms) (#{:hard :soft :partial} policy-kw)]}
   (->Budget total-ms policy-kw (System/nanoTime))))

;; ---------------------------------------------------------------------------
;; Policy helpers — Wave-2 expands.
;; ---------------------------------------------------------------------------

(defn exceeded?
  "Opposite of within?. Named for readability at call sites."
  [budget]
  (not (within? budget)))

(defn child-budget
  "Nested-span inheritance rule (PLAN §9 Q1):
   child.budget = min(declared, parent.remaining-ms)."
  [parent-budget declared-ms policy-kw]
  (make-budget (min declared-ms (remaining-ms parent-budget)) policy-kw))
