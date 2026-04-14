(ns hive-ttracking.span
  "BC-3 Spans — span sink abstraction + default AtomSink.

   Wave-1 skeleton: protocol only. Wave-3 implements AtomSink backed by
   hive-dsl.bounded-atom (ring buffer, drop-oldest). See PLAN.md §2 BC-3.")

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
