(ns hive-ttracking.instrument
  "Protocol and function instrumentation: pass-through wrappers that record
   each call's latency and outcome through hive-ttracking.metrics/record!.
   Wrapped code is untouched."
  (:require [hive-ttracking.metrics]))

(defn- protocol-map
  "The protocol map for proto: a protocol var, or the protocol map itself."
  [proto]
  (if (var? proto) @proto proto))

(defn- method-specs
  "[[method-sym [arity ...]] ...] for the protocol map p, in a stable order."
  [p]
  (->> (:sigs p)
       vals
       (map (fn [{:keys [name arglists]}]
              [name (vec (distinct (map count arglists)))]))
       (sort-by (comp str first))
       vec))

(defn- gen-wrapper-ctor
  "Source of (fn [target keys] (reify P ...)) for the protocol map p: each
   method arity forwards to the protocol fn on target, recording
   (nth keys i) through hive-ttracking.metrics/record!."
  [p]
  (let [pvar   (:var p)
        pns    (-> pvar meta :ns ns-name str)
        psym   (symbol pns (-> pvar meta :name str))
        target (gensym "target")
        ks     (gensym "keys")
        specs  (method-specs p)
        k-syms (mapv (fn [_] (gensym "k")) specs)
        methods
        (for [[i [mname arities]] (map-indexed vector specs)
              arity arities
              :let [args (vec (repeatedly (dec arity) #(gensym "a")))
                    k    (k-syms i)
                    f    (symbol pns (str mname))]]
          `(~mname [~'_ ~@args]
            (let [t0# (System/nanoTime)]
              (try
                (let [r# (~f ~target ~@args)]
                  (hive-ttracking.metrics/record! ~k (- (System/nanoTime) t0#) :ok)
                  r#)
                (catch Throwable t#
                  (hive-ttracking.metrics/record! ~k (- (System/nanoTime) t0#) :error)
                  (throw t#))))))]
    `(fn [~target ~ks]
       (let [~@(mapcat (fn [i s] [s `(nth ~ks ~i)]) (range) k-syms)]
         (reify ~psym ~@methods)))))

(defonce ^:private ctor-cache (atom {}))

(defn- wrapper-ctor
  "Compiled wrapper constructor for protocol map p, built once per protocol
   (keyed by its var) and cached."
  [p]
  (let [pv (:var p)]
    (or (get @ctor-cache pv)
        (let [ctor (binding [*ns* (the-ns 'hive-ttracking.instrument)]
                     (eval (gen-wrapper-ctor p)))]
          (swap! ctor-cache assoc pv ctor)
          ctor))))

(defn wrap
  "Return an object implementing protocol `proto` (a protocol var, e.g.
   #'my.ns/IStore) that forwards every method to `target` and records each
   call as [area label method-name] through hive-ttracking.metrics/record!
   (outcome :error when the method throws; the throwable is rethrown).

   opts:
     :area  keyword, first element of the op key (default :proto/call)
     :label value, or (fn [target] => value); computed once at wrap time.
            nil label yields [area method-name].

   The wrapper implements ONLY `proto`: other protocols or interfaces target
   satisfies are not exposed. Where callers need them, use `wrap-fn` on the
   individual call site instead. nil target returns nil. The reify class is
   generated (eval) once per protocol and cached; wrapping is cheap after."
  ([proto target] (wrap proto target {}))
  ([proto target {:keys [area label] :or {area :proto/call}}]
   (when (some? target)
     (let [p     (protocol-map proto)
           lbl   (if (fn? label) (label target) label)
           keys  (mapv (fn [[mname _]]
                         (if (some? lbl)
                           [area lbl (str mname)]
                           [area (str mname)]))
                       (method-specs p))]
       ((wrapper-ctor p) target keys)))))

(defn wrap-fn
  "Wrap plain function f so every call is recorded as op key k through
   hive-ttracking.metrics/record! (outcome :error when f throws; rethrown).
   Returns a fn with f's behaviour."
  [k f]
  (fn
    ([] (hive-ttracking.metrics/timed k (f)))
    ([a] (hive-ttracking.metrics/timed k (f a)))
    ([a b] (hive-ttracking.metrics/timed k (f a b)))
    ([a b c] (hive-ttracking.metrics/timed k (f a b c)))
    ([a b c d] (hive-ttracking.metrics/timed k (f a b c d)))
    ([a b c d & more] (hive-ttracking.metrics/timed k (apply f a b c d more)))))
