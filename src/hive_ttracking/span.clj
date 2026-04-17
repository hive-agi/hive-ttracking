(ns hive-ttracking.span
  "BC-3 Spans — span sink abstraction + pure helpers over Span values.

   Wave-2: helpers for closing/tagging/hierarchy. Sinks are Wave-3.
   See PLAN.md §2 BC-3.")

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
