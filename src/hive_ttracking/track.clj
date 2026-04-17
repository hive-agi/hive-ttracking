(ns hive-ttracking.track
  "BC-1 Tracking — span lifecycle + track/track-race macros.

   Wave-2: AtomTracker + real track*. Wave-3 fills track-race*.
   See PLAN.md §2 BC-1."
  (:require [hive-ttracking.budget :as budget]
            [hive-ttracking.span :as span]
            [hive-ttracking.events :as events]
            [hive-dsl.bounded-atom :as ba]
            [taoensso.timbre :as log]))

(defprotocol ITracker
  "Owns span lifecycle. Pure when backed by atom-sink; async when pool-backed."
  (start-span   [this span-name tags]
    "Return an open Span value with :id :parent :t0.")
  (end-span     [this span result]
    "Close span, emit :tt/span-ended, return closed Span.")
  (record-sample [this span-id k v]
    "Attach a typed sample to an open span (latency subcomponent, counter, ...)."))

;; ---------------------------------------------------------------------------
;; Value types
;; ---------------------------------------------------------------------------

(defrecord Span [id parent-id name t0 t1 tags result samples])

(defn make-span
  [{:keys [id parent-id name tags]}]
  (->Span (or id (random-uuid))
          parent-id
          name
          (System/nanoTime)
          nil
          (or tags {})
          nil
          []))

;; ---------------------------------------------------------------------------
;; AtomTracker — Wave-2 production implementation.
;;
;; Backed by two hive-dsl bounded-atoms (open + closed spans) so long-running
;; processes never grow unbounded. :on-evict is a no-op — losing trailing
;; closed spans is preferable to OOM.
;; ---------------------------------------------------------------------------

(defrecord AtomTracker [open-spans closed-spans]
  ITracker
  (start-span [_ span-name tags]
    (let [s (make-span {:name span-name :tags tags})]
      (ba/bput! open-spans (:id s) s)
      (events/publish (events/span-started s))
      s))

  (end-span [_ s result]
    (let [closed (span/close s result)]
      ;; drop from open, persist to closed — in that order so a concurrent
      ;; reader never observes the span as both open + closed.
      (swap! (:atom open-spans) dissoc (:id closed))
      (ba/bput! closed-spans (:id closed) closed)
      (events/publish (events/span-ended closed result))
      closed))

  (record-sample [_ span-id k v]
    (if-let [s (ba/bget open-spans span-id)]
      (let [updated (span/add-sample s k v)]
        (ba/bput! open-spans span-id updated)
        updated)
      (do (log/debug "tt/record-sample: span-id not found (already closed?)" span-id)
          nil))))

(defn make-atom-tracker
  "Construct an AtomTracker.

   Options:
     :max-open    — cap on concurrently open spans (default 10_000)
     :max-closed  — cap on retained closed spans (default 10_000)
     :ttl-ms      — TTL for both maps (default 1h; nil = no TTL)
     :on-evict    — optional (fn [{:keys [name evicted-count reason entries]}])"
  ([] (make-atom-tracker {}))
  ([{:keys [max-open max-closed ttl-ms on-evict]
     :or {max-open 10000 max-closed 10000 ttl-ms 3600000}}]
   (->AtomTracker
     (ba/bounded-atom {:max-entries max-open
                       :ttl-ms ttl-ms
                       :eviction-policy :lru
                       :name "tt-open-spans"
                       :on-evict on-evict})
     (ba/bounded-atom {:max-entries max-closed
                       :ttl-ms ttl-ms
                       :eviction-policy :fifo
                       :name "tt-closed-spans"
                       :on-evict on-evict}))))

;; ---------------------------------------------------------------------------
;; Default tracker — lazy atom-tracker so callers without explicit DI still
;; get a working tracker. Consumers can `binding` *default-tracker* or pass
;; :tracker via opts.
;; ---------------------------------------------------------------------------

(defonce ^:private default-atom-tracker (delay (make-atom-tracker)))

(def ^:dynamic *default-tracker* nil)

(defn current-tracker
  "Resolve the active tracker: explicit opt > *default-tracker* > shared default."
  ([] (current-tracker nil))
  ([opt-tracker]
   (or opt-tracker *default-tracker* @default-atom-tracker)))

;; ---------------------------------------------------------------------------
;; Macros' runtime entry points.
;; ---------------------------------------------------------------------------

(defn- check-budget!
  "Post-hoc budget assertion. For :hard policy, throws ex-info with
   :tt/budget-exceeded so callers can distinguish budget violations from
   thunk exceptions. :soft and :partial only publish an event."
  [budget closed-span budget-ms]
  (when (budget/exceeded? budget)
    (events/publish (events/budget-violation closed-span budget))
    (when (= :hard (budget/policy budget))
      (throw (ex-info "tt/track: :hard budget exceeded"
                      {:tt/budget-exceeded true
                       :span               closed-span
                       :budget-ms          budget-ms
                       :policy             :hard
                       :elapsed-ms         (span/elapsed-ms closed-span)})))))

(defn track*
  "Wrap a thunk with a budget-enforced span. Returns
   `{:span closed-span :result <thunk-result-or-nil> :error <Throwable-or-nil>}`.

   opts:
     :name       keyword or string — span name (required)
     :budget-ms  long               — optional deadline
     :policy     #{:hard :soft :partial}  (default :partial)
     :tags       map                — tags attached to span + events
     :tracker    ITracker           — override current-tracker

   Semantics:
     - Thunk runs inside (try ... (catch Throwable t ...)) so AssertionError
       does not leak past the tracker (axiom 3d0e1f7c).
     - On thunk success, budget is checked; :hard violation throws ex-info
       with :tt/budget-exceeded after the span is closed and events published.
     - On thunk throw, span is closed with {:ok false :error msg} and the
       Throwable is returned in :error (not re-thrown — callers decide)."
  [{:keys [name tags tracker budget-ms policy]
    :or   {tags {} policy :partial}}
   thunk]
  (let [tracker (current-tracker tracker)
        budget  (when budget-ms (budget/make-budget budget-ms policy))
        tags'   (cond-> tags
                  budget-ms (assoc :tt/budget-ms budget-ms
                                   :tt/policy    policy))
        span    (start-span tracker name tags')
        ;; Run the thunk inside try/catch Throwable so AssertionError is
        ;; captured (axiom 3d0e1f7c). Outcome is a {:ok? :value :error} map.
        outcome (try
                  {:ok? true :value (thunk)}
                  (catch Throwable t
                    {:ok? false :error t}))
        result-map (if (:ok? outcome)
                     {:ok true  :value (:value outcome)}
                     {:ok false :error (some-> (:error outcome) .getMessage)})
        closed  (end-span tracker span result-map)]
    ;; Budget enforcement happens AFTER end-span + event so the violation
    ;; is observable even when :hard re-throws. Only enforced when the
    ;; thunk succeeded — a thrown thunk already failed independently.
    (when (and budget (:ok? outcome))
      (check-budget! budget closed budget-ms))
    {:span closed :result (:value outcome) :error (:error outcome)}))

(defn track-race*
  "Wave-3. Races branches-map under a single budget using hive-weave pool.
   Returns {branch-key Result}. Replaces the ad-hoc future/deref/cancel pattern
   seen in hive-mcp.tools.catchup.scope/query-axioms."
  [_opts branches-map]
  {:tt/skeleton true
   :branches    (into {} (for [[k _] branches-map] [k :tt/not-implemented]))})

;; ---------------------------------------------------------------------------
;; Wave-1.5 — timed-query helper (unchanged; predates ITracker).
;; ---------------------------------------------------------------------------

(defn default-log-sink
  "Default telemetry sink used by timed-query. Logs elapsed-ms + n at :info
   on non-empty results, :warn on zero-row completion (fork-join fallback
   symptom). Overridable via dependency injection at the call site."
  [{:keys [label elapsed-ms n]}]
  (if (zero? (or n 0))
    (log/warn "tt/timed-query" label "returned 0 entries in" elapsed-ms "ms"
              "— may indicate backend stall, fork-join fallback, or genuinely empty scope")
    (log/info "tt/timed-query" label ":" n "entries in" elapsed-ms "ms")))

(defn timed-query
  "Wrap a query thunk with elapsed-ms + row-count telemetry.

   Returns a thunk of no arguments. Invoking it runs `qfn`, measures wall
   time, counts rows in the result, and calls `sink` with the observation
   before returning the original result unchanged.

   Args:
     label — string used in telemetry (e.g. \"catchup-hierarchy\")
     qfn   — 0-arg fn that performs the query and returns a countable result
     sink  — optional 1-arg fn receiving {:label :elapsed-ms :n :result}
             Defaults to default-log-sink.

   Shape is DI-friendly: call sites never reach into hive-ttracking internals;
   they hand in (tt/timed-query label qfn) and the addon decides how to emit.

   Example — plug into a fork-join task:
     [:hierarchy (tt/timed-query \"hierarchy\" #(query-milvus ...)) []]"
  ([label qfn]
   (timed-query label qfn default-log-sink))
  ([label qfn sink]
   (fn []
     (let [t0 (System/currentTimeMillis)
           result (qfn)
           elapsed (- (System/currentTimeMillis) t0)
           n (count (or result []))]
       (sink {:label label :elapsed-ms elapsed :n n :result result})
       result))))
