(ns hive-ttracking.bench
  "BC-4 Bench — statistical benchmarking for Clojure code.

   Wave-2 implementation: warmup + N timed iterations, percentile aggregation
   (p50/p95/p99/min/max/mean/stddev), :tt/bench-run event emission, and a
   `defbench` macro that wraps `deftest` with a p95 threshold assertion.

   Design notes:
     * Pure data in, pure data out. BenchResult is a value type.
     * Event publishing delegated to hive-ttracking.events/publish, which
       swallows throwables so a stalled router never fails a bench.
     * Warmup iterations run the thunk but are NOT included in samples —
       they let JIT compile hot paths before measurement."
  (:require [hive-ttracking.events :as events]
            [taoensso.timbre :as log]))

(defprotocol IBencher
  (run-iteration [this f])
  (aggregate     [this samples])
  (compare-benches [this base target]))

(defrecord BenchResult [name runs warmup
                        p50 p95 p99
                        min max mean stddev
                        samples-ns])

;; ---------------------------------------------------------------------------
;; Pure math helpers.
;; ---------------------------------------------------------------------------

(defn- percentile
  "Nearest-rank percentile (0 < p < 1) on a pre-sorted vector of numbers.
   Returns nil for empty input. For N=1, returns the lone sample."
  [sorted p]
  (when (seq sorted)
    (let [n   (count sorted)
          idx (-> (* p n) Math/ceil long (max 1) (min n) dec)]
      (nth sorted idx))))

(defn- mean [xs]
  (when (seq xs)
    (/ (reduce + 0.0 xs) (double (count xs)))))

(defn- stddev
  "Sample standard deviation. Returns 0.0 for N<2."
  [xs]
  (if (< (count xs) 2)
    0.0
    (let [m (mean xs)
          sq (reduce (fn [acc x]
                       (let [d (- x m)]
                         (+ acc (* d d))))
                     0.0
                     xs)]
      (Math/sqrt (/ sq (dec (count xs)))))))

(defn aggregate-samples
  "Build a BenchResult from a seq of iteration wall-time samples (nanos)."
  [{:keys [name runs warmup]} samples-ns]
  (let [sorted (vec (sort samples-ns))
        ns->ms #(when % (/ % 1e6))]
    (->BenchResult
      name
      runs
      warmup
      (ns->ms (percentile sorted 0.50))
      (ns->ms (percentile sorted 0.95))
      (ns->ms (percentile sorted 0.99))
      (ns->ms (first sorted))
      (ns->ms (last sorted))
      (ns->ms (mean sorted))
      (ns->ms (stddev sorted))
      (vec samples-ns))))

;; ---------------------------------------------------------------------------
;; bench* — single-threaded sequential runner.
;;
;; Design rationale: parallel bench via hive-weave is a Wave-4+ optimization.
;; Sequential gives stable percentiles without contention noise; callers that
;; need throughput numbers can drive parallelism themselves.
;; ---------------------------------------------------------------------------

(defn- time-once-ns
  "Invoke `thunk`, return elapsed nanos. Swallows Throwables — a throwing
   iteration is recorded as its elapsed time, not as an error."
  [thunk]
  (let [t0 (System/nanoTime)]
    (try (thunk)
         (catch Throwable t
           (log/debug t "tt/bench iteration threw")))
    (- (System/nanoTime) t0)))

(defn bench*
  "Run `thunk` `warmup` times (discarded) then `runs` times (measured).
   Emits :tt/bench-run and returns a BenchResult.

   opts:
     :name    keyword/string — label (required)
     :runs    long           — measured iterations (default 30)
     :warmup  long           — warmup iterations (default 3)

   The returned :samples-ns is a vector of raw nanosecond measurements
   in execution order — callers computing extra stats can reuse it."
  [{:keys [name runs warmup]
    :or   {runs 30 warmup 3}
    :as   opts}
   thunk]
  (dotimes [_ warmup] (time-once-ns thunk))
  (let [samples (vec (for [_ (range runs)] (time-once-ns thunk)))
        result  (aggregate-samples (assoc opts :runs runs :warmup warmup) samples)]
    (events/publish (events/bench-run result))
    result))

;; ---------------------------------------------------------------------------
;; defbench — deftest-like wrapper that runs a bench and asserts p95.
;;
;; Registered with clojure.test via :bench metadata; hive-test/bench.clj
;; (Wave-3 T6) adds first-class reporter support.
;; ---------------------------------------------------------------------------

(defmacro defbench
  "Define a clojure.test test that runs a bench and asserts a p95 threshold.

   opts:
     :name              keyword         — bench label (defaults to test sym)
     :runs              long            — measured iterations (default 30)
     :warmup            long            — warmup iterations (default 3)
     :threshold-p95-ms  double          — fail if p95 exceeds this (required)
     :on-result         ref/ns-qualified fn — side effect on BenchResult

   Example:
     (defbench hot-path-bench
       {:runs 100 :threshold-p95-ms 5.0}
       (work!))

   Expands to a tagged deftest — run via `lein test :bench` or equivalent."
  [bench-sym opts & body]
  (let [{:keys [name runs warmup threshold-p95-ms on-result]
         :or   {runs 30 warmup 3}} opts
        bench-name (or name (keyword (str bench-sym)))
        result-sym (gensym "result_")
        err-sym    (gensym "t_")]
    `(clojure.test/deftest ~(vary-meta bench-sym assoc :bench true)
       (let [~result-sym (bench*
                           {:name   ~bench-name
                            :runs   ~runs
                            :warmup ~warmup}
                           (fn [] ~@body))]
         ~(when on-result
            `(try (~on-result ~result-sym)
                  (catch Throwable ~err-sym
                    (log/debug ~err-sym "defbench on-result threw"))))
         ~(when threshold-p95-ms
            `(clojure.test/is (<= (:p95 ~result-sym) ~threshold-p95-ms)
                              (format "p95 %.3fms exceeded threshold %.3fms (runs=%d warmup=%d)"
                                      (:p95 ~result-sym) ~threshold-p95-ms ~runs ~warmup)))
         ~result-sym))))
