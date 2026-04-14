# hive-ttracking — Time Tracking, Budgets & Instrumentation

EPIC: `20260414104332-192b2da4`
Status: planning (scaffold wave)
Author: swarm-epic-hive-ttracking-1776179445
Date: 2026-04-14

---

## 1. Purpose

`hive-ttracking` (short: `tt`) is the **call-wrapping instrumentation library** for the
hive codebase. It turns every wrapped call into a first-class *span* with:

- wall-clock latency
- budget assertion (did it return within N ms?)
- partial-progress semantics (deliver whatever finished)
- event emission (span-start / span-end / budget-violation)
- optional protocol instrumentation (zero-change pass-through wrappers)
- benchmark harness (repeat-run, percentile aggregation)
- test adapter (`deftest-tt` asserts budgets as test conditions)

It subsumes the ad-hoc `future` + `deref timeout-ms` pattern used today in
`hive-mcp/tools/catchup/scope.clj` (the `query-axioms` unbounded-deref bug
tracked at kanban `20260414120547-659fe671`).

### The consumer pattern `tt/track` must abstract

From the `query-axioms` regression test:

```clojure
(let [f  (future (slow-work))
      r  (deref f (+ budget-ms 500) ::timeout)]
  (when (= r ::timeout) (future-cancel f))
  ...)
```

This pattern is *everywhere*: race N branches, each under a budget,
collect partial results, cancel stragglers. `tt/track` replaces it with a
declarative API that also emits observability events.

---

## 2. DDD Bounded Contexts (6)

Each BC is a namespace owning one protocol + pure fns + value types.
Cross-BC calls only via protocols. Closed error ADT per BC.

### BC-1 · Tracking (`hive-ttracking.track`)
Owns: the `tt/track` macro + pure span builders.
Protocol: `ITracker` (`start-span`, `end-span`, `record-sample`).
Key types: `Span { :id :parent :name :t0 :t1 :tags :result }`.
No I/O. Delegates pool execution to hive-weave. Delegates event emission to
the Events BC.

### BC-2 · Budgets (`hive-ttracking.budget`)
Owns: budget declaration + assertion + policy.
Protocol: `IBudget` (`within?`, `policy`, `remaining-ms`).
Types: `Budget { :total-ms :policy #{:hard :soft :partial} :started-at }`.
Policies:
- `:hard`   → cancel + return `:err/budget-exceeded`
- `:soft`   → log + return late result
- `:partial`→ return whatever branches finished by deadline
Pure. No I/O.

### BC-3 · Spans (`hive-ttracking.span`)
Owns: span tree + aggregation + serialization.
Protocol: `ISpanSink` (`record!`, `flush!`, `query`).
Types: nested spans via `:parent-id`. Flame-graph friendly.
Default in-memory sink: `AtomSink` backed by `hive-dsl/bounded-atom` (ring
buffer, drop-oldest). Pluggable so consumers can emit to file/OpenTelemetry.

### BC-4 · Bench (`hive-ttracking.bench`)
Owns: the `tt/bench` macro + statistical aggregation.
Protocol: `IBencher` (`run-iteration`, `aggregate`, `compare`).
Value: `BenchResult { :runs :p50 :p95 :p99 :min :max :mean :stddev }`.
Uses hive-weave parallel pool for warmup + run phases. Writes results
through ISpanSink so bench runs participate in normal observability.

### BC-5 · Events (`hive-ttracking.events`)
Owns: integration with hive-events bus.
Pure event builders: `span-started`, `span-ended`, `budget-violation`,
`bench-run-done`. All events use the `:tt/*` namespace:
- `:tt/span-started`
- `:tt/span-ended`
- `:tt/budget-violation`
- `:tt/bench-completed`
Dispatches via `hive.events.router`. Consumers (e.g. olympus UI) subscribe
to `:tt/*` and get real-time visibility.

### BC-6 · Instrumentation (`hive-ttracking.instrument`)
Owns: protocol pass-through wrappers.
API: `(tt/instrument-protocol proto target {:span-name fn :tags fn})`.
Returns a `defrecord`-backed wrapper that implements `proto`, forwards every
method to `target`, and emits a span per call. OCP-compliant: wrapped code
never sees the wrapper. Uses Clojure's `extend` / reified dispatch — no
macro-rewriting of caller sites.

---

## 3. Protocol Definitions (canonical, v0)

```clojure
;; BC-1 Tracking
(defprotocol ITracker
  (start-span [this name tags])
  (end-span   [this span result])
  (record-sample [this span-id k v]))

;; BC-2 Budgets
(defprotocol IBudget
  (within?      [this])
  (remaining-ms [this])
  (policy       [this]))

;; BC-3 Spans
(defprotocol ISpanSink
  (record! [this span])
  (flush!  [this])
  (query   [this filter-fn]))

;; BC-4 Bench
(defprotocol IBencher
  (run-iteration [this f])
  (aggregate     [this samples])
  (compare       [this base target]))
```

All methods return `hive-dsl.result/Result` (`:ok`/`:err`).

---

## 4. Public API (`hive-ttracking.core` re-exports)

```clojure
;; primary wrapper — budget-aware, event-emitting
(tt/track {:name :my.op :budget-ms 2000 :policy :partial
           :tags {:project "hive"}
           :tracker *default-tracker*}
  (do-work))

;; parallel branch racing (replaces raw future+deref+cancel)
(tt/track-race {:budget-ms 2000 :policy :partial}
  {:formal (fn [] (query-formal))
   :legacy (fn [] (query-legacy))})
;; => {:formal [...] :legacy :err/budget-exceeded}

;; benchmark
(tt/bench {:iterations 100 :warmup 10 :name :query-axioms}
  (query-axioms "hive-mcp"))
;; => BenchResult

;; protocol instrumentation
(def traced-store
  (tt/instrument-protocol IMemoryStore raw-store
    {:span-name #(keyword "store" (name %1))
     :tags      (fn [method args] {:method method})}))
;; traced-store implements IMemoryStore, same shape, spans on every call.
```

Test adapter (lives in `hive-test/src/hive_test/tt.clj`):

```clojure
(require '[hive-test.tt :as htt])

(htt/deftest-tt query-axioms-budget-test
  {:budget-ms 2000 :policy :hard}
  (let [r (query-axioms "hive-mcp")]
    (is (= 4 (count r)))))
;; Failing the budget fails the test with a span-tree dump.
```

---

## 5. Config Shape (`config.edn` `:tt` block)

```clojure
{:tt
 {:default-budget-ms 5000
  :default-policy    :partial         ; :hard | :soft | :partial
  :sink
  {:backend :atom                     ; :atom | :file | :otel
   :capacity 10000                    ; ring-buffer size for :atom
   :path nil}                         ; for :file
  :events
  {:emit? true
   :include #{:tt/span-started
              :tt/span-ended
              :tt/budget-violation
              :tt/bench-completed}}
  :bench
  {:default-iterations 100
   :default-warmup     10
   :pool               :tt-bench}     ; hive-weave named pool
  :instrument
  {:enabled? true
   :auto-wrap-protocols                ; DI-level opt-in
   []}}}
```

---

## 6. Composition With Sibling Libs

| Sibling        | Interaction                                                             |
|----------------|-------------------------------------------------------------------------|
| **hive-dsl**   | All public fns return `Result`. Use `bounded-atom` for sink ring-buffer. |
| **hive-weave** | `track-race` submits branches to named pool (`:tt` or caller-chosen). Cancellation via weave timeout primitives. Bench uses weave's `timed` + `parallel`. |
| **hive-events**| Every span terminus becomes `:tt/*` event via `hive.events.router`. Consumers subscribe like any other event. |
| **hive-test**  | Adapter `hive-test.tt/deftest-tt` — test is wrapped in `tt/track`, budget violation fails the deftest. Properties/mutation combinators can be layered on (`tt-property` later). |
| **hive-mcp**   | Consumer only. First consumer site: `hive-mcp.tools.catchup.scope/query-axioms` — replaces ad-hoc future/deref with `tt/track-race`. DO NOT touch catchup code in this EPIC; it is owned by the `fix-catchup-query-axioms` ling. |

**OCP guarantee**: `instrument-protocol` uses `defrecord`/`reify` pass-through.
Wrapped code is untouched. Zero call-site rewriting. Zero changes to
consumer namespaces beyond *opt-in* instrumentation at the DI wiring layer.

---

## 7. Five-Wave Implementation Order

### Wave 1 · Scaffold (THIS WAVE, no commit yet)
- [x] `deps.edn` mirroring hive-proximum
- [x] `src/hive_ttracking/` skeleton namespaces (one per BC + `core`)
- [x] `resources/META-INF/hive-addons/hive-ttracking.edn`
- [x] `test/hive_ttracking/` skeleton test files
- [x] `PLAN.md` (this doc)
- [x] persisted architectural decision memory + KG edges

### Wave 2 · Tracking + Budgets (pure core)
- BC-1 + BC-2 fully implemented with trifecta tests
- `tt/track` macro (single-branch) returning Result
- Budget policies, cancellation semantics
- No hive-weave dependency yet — uses vanilla future to keep Wave 2 leaf-testable
- Golden+property+mutation tests in `test/hive_ttracking/track_test.clj`

### Wave 3 · Spans + Events + Weave Integration
- BC-3 (ISpanSink + AtomSink)
- BC-5 (:tt/* event builders + router hookup)
- Swap Wave-2 vanilla future for hive-weave named pool
- `tt/track-race` multi-branch variant (consumer-pattern abstraction)
- First cross-lib test: assert `:tt/span-ended` lands on hive-events bus

### Wave 4 · Bench + Instrumentation
- BC-4 bench macro + percentile aggregation
- BC-6 `instrument-protocol` — defrecord/reify pass-through generator
- Property test: instrumented protocol behavioral-equivalent to raw target
- Mutation test: drop one method from wrapper → property fails

### Wave 5 · hive-test Adapter + First Consumer
- `hive-test/src/hive_test/tt.clj` — `deftest-tt` macro
- Release coordinated with `fix-catchup-query-axioms` ling: that ling lands
  its fix *against* `tt/track-race`, becoming the first real consumer
- EPIC closes when catchup regression test passes using `deftest-tt`

---

## 8. Non-Goals

- **No caller-site rewriting.** No macroexpansion of existing functions. Only
  opt-in wrappers.
- **No custom thread pool.** Uses hive-weave named pools.
- **No distributed tracing in v0.** OTel backend is a Wave-6+ concern.
- **Does not break hive-mcp.** All integration is via opt-in wiring.

---

## 9. Open Questions (non-blocking)

1. Budget inheritance across nested spans — child inherits parent
   `remaining-ms` or gets its own declared budget? Proposal: child budget =
   `min(declared, parent.remaining-ms)`.
2. Sink backpressure when AtomSink ring fills — drop-oldest (proposed) or
   block? Drop-oldest aligns with hive-dsl `bounded-atom` semantics.
3. Should `tt/track` returning `:err/budget-exceeded` also include the
   partial result as `:data`? Proposal: yes, for `:partial` policy.

---

## 10. References

- EPIC kanban: `20260414104332-192b2da4`
- Consumer site: `hive-mcp/test/hive_mcp/tools/catchup/query_axioms_regression_test.clj`
- Catchup fix kanban: `20260414120547-659fe671` (parallel ling)
- hive-dsl Result ADT: `hive-dsl/src/hive_dsl/result.clj`
- hive-weave pool: `hive-weave/src/hive_weave/pool.clj`
- hive-events router: `hive-events/src/hive/events/router.cljc`
