(ns hive-ttracking.metrics
  "Metrics port: op-key -> latency/frequency aggregation for hive internals.

   Op key: a vector [area op] or [area op sub]; each element a keyword or a
   short string from a closed vocabulary (tool name, verb, addon id, backend,
   method). Never ids, paths, queries or user text.

   Callers bind this namespace late (requiring-resolve) and fall back to a
   no-op when it is absent. `record!` and `timed` never throw because of the
   sink: a sink failure is swallowed and counted in `sink-error-count`."
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLongArray LongAccumulator LongAdder]
           [java.util.function LongBinaryOperator]))

(set! *warn-on-reflection* true)

(defprotocol IMetricsSink
  (observe! [sink k elapsed-ns outcome]
    "Record one observation. k = op key. elapsed-ns = long. outcome = :ok | :error.")
  (snapshot [sink]
    "Immutable value: {k {:count n :errors n :total-ns n :max-ns n :p50-ns n :p95-ns n :p99-ns n}}"))

(defprotocol IResettable
  (-reset! [sink] "Clear every aggregate held by sink."))

;; ---------------------------------------------------------------------------
;; No-op sink
;; ---------------------------------------------------------------------------

(def noop-sink
  "Sink that records nothing; snapshot is {}."
  (reify
    IMetricsSink
    (observe! [_ _ _ _] nil)
    (snapshot [_] {})
    IResettable
    (-reset! [_] nil)))

;; ---------------------------------------------------------------------------
;; Stats sink: lock-free cells
;; ---------------------------------------------------------------------------

(def ^:const bucket-count
  "Number of log2 histogram buckets per op key."
  64)

(defn bucket-index
  "Histogram bucket for elapsed-ns: 0 for ns <= 0, else floor(log2 ns)+1,
   capped at 63. Bucket i holds values in [2^(i-1), 2^i)."
  ^long [^long ns]
  (if (<= ns 0)
    0
    (min 63 (- 64 (Long/numberOfLeadingZeros ns)))))

(defn bucket-upper
  "Inclusive upper bound (ns) of bucket i."
  ^long [^long i]
  (cond (zero? i) 0
        (>= i 63) Long/MAX_VALUE
        :else     (dec (bit-shift-left 1 i))))

(def ^:private ^LongBinaryOperator max-op
  (reify LongBinaryOperator
    (applyAsLong [_ a b] (Math/max a b))))

(deftype Cell [^LongAdder cnt ^LongAdder errs ^LongAdder total
               ^LongAccumulator mx ^AtomicLongArray buckets])

(defn- new-cell ^Cell []
  (Cell. (LongAdder.) (LongAdder.) (LongAdder.)
         (LongAccumulator. max-op 0) (AtomicLongArray. (int bucket-count))))

(defn- percentile-ns
  "Upper bound of the bucket holding rank ceil(p*n), clamped to max-ns."
  ^long [^longs counts ^long n ^double p ^long max-ns]
  (if (<= n 0)
    0
    (let [rank (max 1 (long (Math/ceil (* p (double n)))))]
      (loop [i 0 acc 0]
        (if (>= i bucket-count)
          max-ns
          (let [acc (+ acc (aget counts i))]
            (if (>= acc rank)
              (min (bucket-upper i) max-ns)
              (recur (inc i) acc))))))))

(defn- cell->row [^Cell c]
  (let [^AtomicLongArray b (.-buckets c)
        counts (long-array bucket-count)
        _      (dotimes [i bucket-count] (aset counts i (.get b i)))
        n      (areduce counts i acc 0 (+ acc (aget counts i)))
        mx     (.get ^LongAccumulator (.-mx c))]
    {:count    (.sum ^LongAdder (.-cnt c))
     :errors   (.sum ^LongAdder (.-errs c))
     :total-ns (.sum ^LongAdder (.-total c))
     :max-ns   mx
     :p50-ns   (percentile-ns counts n 0.50 mx)
     :p95-ns   (percentile-ns counts n 0.95 mx)
     :p99-ns   (percentile-ns counts n 0.99 mx)}))

(deftype StatsSink [^ConcurrentHashMap cells]
  IMetricsSink
  (observe! [_ k elapsed-ns outcome]
    (let [ns      (max 0 (long elapsed-ns))
          ^Cell c (or (.get cells k)
                      (let [fresh (new-cell)]
                        (or (.putIfAbsent cells k fresh) fresh)))]
      (.increment ^LongAdder (.-cnt c))
      (when (identical? outcome :error)
        (.increment ^LongAdder (.-errs c)))
      (.add ^LongAdder (.-total c) ns)
      (.accumulate ^LongAccumulator (.-mx c) ns)
      (.incrementAndGet ^AtomicLongArray (.-buckets c) (int (bucket-index ns)))
      nil))
  (snapshot [_]
    (persistent!
     (reduce (fn [m ^java.util.Map$Entry e]
               (assoc! m (.getKey e) (cell->row (.getValue e))))
             (transient {})
             (.entrySet cells))))
  IResettable
  (-reset! [_] (.clear cells) nil))

(defn stats-sink
  "Lock-free in-process aggregator: ConcurrentHashMap k -> cell of LongAdders
   plus a 64-bucket log2 histogram (AtomicLongArray). Percentiles are the
   upper bound of the bucket holding the rank, clamped to :max-ns."
  []
  (StatsSink. (ConcurrentHashMap.)))

;; ---------------------------------------------------------------------------
;; Composite sink
;; ---------------------------------------------------------------------------

(defonce ^:private ^LongAdder sink-errors (LongAdder.))

(defn sink-error-count
  "Number of sink exceptions swallowed by `record!` and `composite-sink`."
  []
  (.sum sink-errors))

(defn composite-sink
  "Fan-out sink: observe! reaches every child (a throwing child is swallowed
   and counted, the others still record). snapshot merges the children's
   snapshots, later children winning on equal keys."
  [& sinks]
  (let [sinks (vec sinks)]
    (reify
      IMetricsSink
      (observe! [_ k elapsed-ns outcome]
        (doseq [s sinks]
          (try (observe! s k elapsed-ns outcome)
               (catch Throwable _ (.increment sink-errors))))
        nil)
      (snapshot [_]
        (reduce (fn [m s]
                  (merge m (try (snapshot s) (catch Throwable _ nil))))
                {} sinks))
      IResettable
      (-reset! [_]
        (doseq [s sinks]
          (when (satisfies? IResettable s) (-reset! s)))
        nil))))

(defn reset-sink!
  "Clear the aggregates of sink (a stats-sink, or a composite holding one).
   Sinks without state are left alone. Returns nil."
  [sink]
  (when (satisfies? IResettable sink) (-reset! sink))
  nil)

;; ---------------------------------------------------------------------------
;; Process-global sink
;; ---------------------------------------------------------------------------

(defonce ^:private installed (atom nil))

(defn install!
  "Set the process-global sink (nil uninstalls). Returns sink."
  [sink]
  (reset! installed sink)
  sink)

(defn current-sink
  "The installed sink, or `noop-sink` when none is installed."
  []
  (or @installed noop-sink))

(defn record!
  "observe! on the current sink. Never throws; a sink exception is counted in
   `sink-error-count`. Returns nil."
  [k elapsed-ns outcome]
  (try
    (when-let [s @installed]
      (observe! s k elapsed-ns outcome))
    (catch Throwable _
      (.increment sink-errors)))
  nil)

(defmacro timed
  "Evaluate body, recording [k elapsed-ns outcome] through `record!`.
   outcome is :error when body throws (the throwable is rethrown), else :ok.
   Returns body's value. k is evaluated once, before body."
  [k & body]
  `(let [k#  ~k
         t0# (System/nanoTime)]
     (try
       (let [r# (do ~@body)]
         (record! k# (- (System/nanoTime) t0#) :ok)
         r#)
       (catch Throwable t#
         (record! k# (- (System/nanoTime) t0#) :error)
         (throw t#)))))

;; ---------------------------------------------------------------------------
;; Pure analysis
;; ---------------------------------------------------------------------------

(def ^:private sort-keys
  {:total  :total-ms
   :count  :count
   :mean   :mean-ms
   :p99    :p99-ms
   :max    :max-ms
   :errors :errors})

(defn- ns->ms ^double [n] (/ (double (or n 0)) 1e6))

(defn- label-str
  "Keyword -> its name with namespace (no colon); anything else -> str."
  [x]
  (if (keyword? x) (subs (str x) 1) (str x)))

(defn hotspots
  "PURE. Rank the rows of snapshot snap.
   opts: :by    one of #{:total :count :mean :p99 :max :errors} (default :total)
         :limit max rows (default 20; nil or <= 0 keeps all)
         :area  keep only keys whose first element equals area (keyword, or
                string spelling such as \"mcp/tool\").
   Returns a vector of {:k :count :errors :total-ms :mean-ms :p50-ms :p95-ms
   :p99-ms :max-ms :share}; :share = row total time / total time of every row
   kept by :area (before :limit)."
  ([snap] (hotspots snap {}))
  ([snap {:keys [by limit area] :or {by :total limit 20}}]
   (let [area-s (some-> area label-str)
         keep?  (fn [k] (or (nil? area-s)
                            (and (sequential? k) (= area-s (label-str (first k))))))
         rows   (for [[k {:keys [count errors total-ns max-ns p50-ns p95-ns p99-ns]}] snap
                      :when (keep? k)
                      :let [n (long (or count 0))]]
                  {:k        k
                   :count    n
                   :errors   (long (or errors 0))
                   :total-ns (long (or total-ns 0))
                   :total-ms (ns->ms total-ns)
                   :mean-ms  (if (pos? n) (/ (ns->ms total-ns) n) 0.0)
                   :p50-ms   (ns->ms p50-ns)
                   :p95-ms   (ns->ms p95-ns)
                   :p99-ms   (ns->ms p99-ns)
                   :max-ms   (ns->ms max-ns)})
         grand  (reduce + 0 (map :total-ns rows))
         sk     (get sort-keys (keyword by) :total-ms)
         ranked (sort-by sk #(compare %2 %1) rows)
         ranked (if (and limit (pos? (long limit))) (take limit ranked) ranked)]
     (mapv (fn [r]
             (-> r
                 (assoc :share (if (pos? grand) (/ (double (:total-ns r)) grand) 0.0))
                 (dissoc :total-ns)))
           ranked))))
