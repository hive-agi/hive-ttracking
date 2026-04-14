(ns hive-ttracking.bench
  "BC-4 Bench — tt/bench macro + statistical aggregation.

   Wave-1 skeleton. Wave-4 implements run-iteration + percentile aggregation
   via hive-weave parallel. See PLAN.md §2 BC-4.")

(defprotocol IBencher
  (run-iteration [this f])
  (aggregate     [this samples])
  (compare       [this base target]))

(defrecord BenchResult [name runs p50 p95 p99 min max mean stddev])

(defn bench*
  "Wave-4 entry point. Wave-1 stub returns a sentinel BenchResult."
  [opts thunk]
  (->BenchResult (:name opts) 0 nil nil nil nil nil nil nil))
