(ns hive-ttracking.core
  "Public API re-exports for hive-ttracking (short alias: tt).

   Wave-1 skeleton — implementations live in per-BC namespaces
   and land in Waves 2-5. See PLAN.md §7.

   Primary entry points:
     tt/track          — budget-aware single-branch wrapper
     tt/track-race     — multi-branch racing with partial-progress
     tt/bench          — statistical benchmarking
     tt/instrument-protocol — defrecord pass-through instrumentation
     tt/timed-query    — lightweight elapsed-ms + row-count telemetry"
  (:require [hive-ttracking.track :as track]
            [hive-ttracking.budget :as budget]
            [hive-ttracking.span :as span]
            [hive-ttracking.bench :as bench]
            [hive-ttracking.events :as events]
            [hive-ttracking.instrument :as instrument]))

;; ---------------------------------------------------------------------------
;; Re-exports — thin facade over bounded contexts. All fns return Result.
;; ---------------------------------------------------------------------------

(defmacro with-tracker
  "Bind `tracker` as the active tracker for the dynamic extent of `body`.

   Callers that need scoped DI (e.g. tests, multi-tenant request handlers)
   wrap their entry point with this form. `tt/track`, `tt/bench` and any
   nested BC code will see the rebound tracker via
   `track/current-tracker`.

   Example:
     (def t (track/make-atom-tracker))
     (tt/with-tracker t
       (tt/track {:name :demo} (do-work))
       (tt/track {:name :demo2} (do-more))) ;; both land in `t`"
  [tracker & body]
  `(binding [track/*default-tracker* ~tracker]
     ~@body))

(defmacro track
  "Wrap body in a budget-enforced span. Emits :tt/span-* events.

   opts:
     :name       keyword — span name (required)
     :budget-ms  long    — deadline (default from config)
     :policy     #{:hard :soft :partial}
     :tags       map     — attached to span + events
     :tracker    ITracker — defaults to *default-tracker*

   Returns {:span :result :error}. :hard policy re-throws via ex-info
   with :tt/budget-exceeded on violation. See hive-ttracking.track/track*."
  [opts & body]
  `(track/track* ~opts (fn [] ~@body)))

(defmacro track-race
  "Race N branches under one budget. Returns map of branch-key -> Result.

   opts: as for `track` plus :branches-map (or pass branches as second arg).
   Replaces the ad-hoc future+deref+cancel consumer pattern. Wave-3 implements."
  [opts branches-map]
  `(track/track-race* ~opts ~branches-map))

(defmacro bench
  "Run body N iterations, return BenchResult (p50/p95/p99/mean/stddev).
   opts: :name :runs :warmup. See hive-ttracking.bench/bench*."
  [opts & body]
  `(bench/bench* ~opts (fn [] ~@body)))

(defmacro defbench
  "deftest-like wrapper: run a bench, assert p95 against threshold.
   See hive-ttracking.bench/defbench for opts."
  [bench-sym opts & body]
  `(bench/defbench ~bench-sym ~opts ~@body))

(defn instrument-protocol
  "Return a pass-through wrapper around `target` that implements `proto`
   and emits a span per method call. OCP-compliant: wrapped code unchanged.
   Wave-4 implements."
  [proto target opts]
  (instrument/wrap proto target opts))

(defn timed-query
  "Wrap a query thunk with elapsed-ms + row-count telemetry.
   Returns a 0-arg thunk that invokes `qfn`, measures wall time, counts
   result rows, emits an observation to `sink`, and returns the original
   result unchanged.

   See hive-ttracking.track/timed-query for full docs.

   DI-friendly: consumers compose this wrapper at call sites without
   reaching into hive-ttracking internals. The default sink logs at :info
   on success, :warn on zero rows — which surfaces silent fork-join
   fallbacks (e.g. hive-mcp catchup bundle timeouts) as observable events.

   Example — fork-join task in hive-mcp catchup:
     (require '[hive-ttracking.core :as tt])
     [:hierarchy (tt/timed-query \"catchup-hierarchy\" #(query-milvus ...)) []]"
  ([label qfn]       (track/timed-query label qfn))
  ([label qfn sink]  (track/timed-query label qfn sink)))
