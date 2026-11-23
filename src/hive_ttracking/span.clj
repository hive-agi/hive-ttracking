(ns hive-ttracking.span
  "BC-3 Spans — span sink abstraction + pure helpers over Span values.

   Wave-2: helpers for closing/tagging/hierarchy. Sinks are Wave-3.
   See PLAN.md §2 BC-3.

   STD-04 O1: span <-> W3C trace-context bridge (span->context,
   with-trace-parent) at the bottom; the codec is hive-ttracking.tracecontext.
   Trace fields ride in :tags under :w3c/trace-id, :w3c/span-id,
   :w3c/flags and :w3c/tracestate."
  (:require [hive-ttracking.tracecontext :as tc]))

(defprotocol ISpanSink
  (record! [this span]   "Persist a closed Span. Returns Result.")
  (flush!  [this]        "Force-flush any buffered spans. Returns Result.")
  (query   [this filter-fn] "Query stored spans by predicate. Returns Result."))

;; Wave-3: AtomSink, FileSink, (Wave-6+) OtelSink.
(defrecord NoopSink []
  ISpanSink
  (record! [_ _] {:ok true})
  (flush!  [_]   {:ok true})
  (query   [_ _] {:ok true :data []}))

(defn noop-sink [] (->NoopSink))

;; ---------------------------------------------------------------------------
;; Pure helpers — operate on Span records without side effects.
;; ---------------------------------------------------------------------------

(defn elapsed-ns
  "Nanoseconds from span open. Uses :t1 if closed, else nanoTime - :t0.
   Returns nil if span has no :t0 (malformed)."
  [{:keys [t0 t1] :as _span}]
  (when t0
    (- (or t1 (System/nanoTime)) t0)))

(defn elapsed-ms
  "Milliseconds elapsed. See elapsed-ns. Returns nil if malformed."
  [span]
  (when-let [ns (elapsed-ns span)]
    (/ ns 1e6)))

(defn close
  "Return a closed copy of `span` stamped with t1 (defaults to nanoTime) and
   a result value. Pure — does not emit events or mutate sinks."
  ([span result]
   (close span result (System/nanoTime)))
  ([span result t1]
   (assoc span :t1 t1 :result result)))

(defn closed?
  "True iff span has :t1 set."
  [span]
  (some? (:t1 span)))

(defn tag
  "Attach a tag (or merge a map of tags) onto a span. Returns updated span."
  ([span k v]
   (assoc-in span [:tags k] v))
  ([span tag-map]
   (update span :tags (fnil merge {}) tag-map)))

(defn with-parent
  "Return `child` with :parent-id bound to `parent`'s :id. Accepts either a
   Span record or a raw id keyword/uuid/string for `parent`."
  [child parent]
  (assoc child :parent-id (if (map? parent) (:id parent) parent)))

(defn descendant?
  "True iff `maybe-child` claims `maybe-ancestor` as its direct parent.
   Shallow (one-hop) — full-chain walks require an external index."
  [maybe-child maybe-ancestor]
  (and (map? maybe-child) (map? maybe-ancestor)
       (some? (:id maybe-ancestor))
       (= (:parent-id maybe-child) (:id maybe-ancestor))))

(defn add-sample
  "Append `{:k k :v v :at nanoTime}` onto the span's :samples vector.
   Preserves insertion order."
  [span k v]
  (update span :samples (fnil conj []) {:k k :v v :at (System/nanoTime)}))

;; ---------------------------------------------------------------------------
;; STD-04 O1 — bridge Span values <-> W3C trace context (pure).
;;
;; A Span keeps its own :id (a uuid) and :parent-id. The W3C side lives in
;; :tags so the Span record shape does not change:
;;   :w3c/trace-id   32 lower-hex, shared by every span of one trace
;;   :w3c/span-id    16 lower-hex, this span's id on the wire (optional;
;;                   derived from the low 64 bits of a uuid :id when absent)
;;   :w3c/flags      2 lower-hex (default "01")
;;   :w3c/tracestate opaque member vector
;; ---------------------------------------------------------------------------

(defn w3c-span-id
  "This span's 16-hex wire id: the :w3c/span-id tag when valid, else the low
   64 bits of a uuid :id. nil when neither yields a valid (non-zero) id."
  [{:keys [id tags]}]
  (let [tagged (:w3c/span-id tags)]
    (cond
      (tc/span-id? tagged) tagged
      (uuid? id) (let [s (format "%016x" (.getLeastSignificantBits ^java.util.UUID id))]
                   (when (tc/span-id? s) s))
      :else nil)))

(defn span->context
  "W3C context whose parent-id is this span: what a hop leaving this span
   carries (traceparent header, _meta, TRACEPARENT env). nil when the span
   carries no valid :w3c/trace-id or has no usable wire id."
  [{:keys [tags] :as span}]
  (let [ts  (:w3c/tracestate tags)
        ctx (cond-> {:version   tc/version
                     :trace-id  (:w3c/trace-id tags)
                     :parent-id (w3c-span-id span)
                     :flags     (or (:w3c/flags tags) "01")}
              (seq ts) (assoc :tracestate (vec ts)))]
    (when (tc/valid? ctx) ctx)))

(defn with-trace-parent
  "Return `span` as a child of the remote context `ctx`: :parent-id becomes
   ctx's parent-id (the caller's wire span id), and the trace-id, flags and
   tracestate are copied into :tags. An invalid or nil ctx leaves the span
   unchanged."
  [span ctx]
  (if (tc/valid? ctx)
    (-> span
        (assoc :parent-id (:parent-id ctx))
        (update :tags (fnil merge {})
                (cond-> {:w3c/trace-id (:trace-id ctx)
                         :w3c/flags    (:flags ctx)}
                  (seq (:tracestate ctx)) (assoc :w3c/tracestate (:tracestate ctx)))))
    span))

(defn with-trace-root
  "Return `span` as the root of a new trace drawn from `random-fn` (see
   tracecontext/new-root): trace-id and flags in :tags, no :parent-id
   change. Unchanged when the source cannot produce a valid id."
  [span random-fn]
  (if-let [tid (tc/new-trace-id random-fn)]
    (update span :tags (fnil merge {}) {:w3c/trace-id tid :w3c/flags "01"})
    span))
