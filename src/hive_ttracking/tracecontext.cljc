(ns hive-ttracking.tracecontext
  "W3C Trace Context codec (https://www.w3.org/TR/trace-context/), pure.

   STD-04 O1: trace context crosses hops as a W3C `traceparent`
   (MCP params._meta.traceparent per SEP-414, NATS header `traceparent`,
   child env TRACEPARENT). This ns only parses, formats and derives
   contexts; it performs no I/O and owns no randomness — every fn that
   needs fresh ids takes the random source as an argument.

   Context value:
     {:version   \"00\"
      :trace-id  32 lower-hex chars, not all zero
      :parent-id 16 lower-hex chars, not all zero
      :flags     2 lower-hex chars (bit 0 = sampled)
      :tracestate [\"k=v\" ...]}   ; optional, opaque members, at most 32

   Only version 00 is accepted; version ff is invalid by the spec and any
   other version is refused rather than parsed by the forward-compat rule.
   `tracestate` members are kept opaque: they are split on commas, trimmed
   of optional white space, empty members dropped, and otherwise passed
   through unchanged. A header of more than 32 members is discarded whole."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def version "00")
(def max-tracestate-members 32)

(def ^:private traceparent-re
  #"([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})")

(def ^:private zero-trace-id (apply str (repeat 32 \0)))
(def ^:private zero-span-id  (apply str (repeat 16 \0)))

;; ---------------------------------------------------------------------------
;; Field predicates
;; ---------------------------------------------------------------------------

(defn trace-id?
  "True iff `s` is 32 lower-hex chars and not all zero."
  [s]
  (boolean (and (string? s)
                (re-matches #"[0-9a-f]{32}" s)
                (not= s zero-trace-id))))

(defn span-id?
  "True iff `s` is 16 lower-hex chars and not all zero (a valid parent-id)."
  [s]
  (boolean (and (string? s)
                (re-matches #"[0-9a-f]{16}" s)
                (not= s zero-span-id))))

(defn flags?
  "True iff `s` is 2 lower-hex chars."
  [s]
  (boolean (and (string? s) (re-matches #"[0-9a-f]{2}" s))))

(defn valid?
  "True iff `ctx` is a well-formed version-00 context."
  [{:keys [version trace-id parent-id flags tracestate] :as ctx}]
  (boolean (and (map? ctx)
                (= "00" version)
                (trace-id? trace-id)
                (span-id? parent-id)
                (flags? flags)
                (or (nil? tracestate)
                    (and (vector? tracestate)
                         (<= (count tracestate) max-tracestate-members)
                         (every? #(and (string? %)
                                       (not (str/blank? %))
                                       (not (str/includes? % ",")))
                                 tracestate))))))

(defn- hex->int [s]
  #?(:clj  (Integer/parseInt s 16)
     :cljs (js/parseInt s 16)))

(defn sampled?
  "True iff the sampled bit (0x01) of the context's flags is set."
  [{:keys [flags]}]
  (and (flags? flags) (odd? (hex->int flags))))

;; ---------------------------------------------------------------------------
;; traceparent
;; ---------------------------------------------------------------------------

(defn parse-traceparent
  "Parse a traceparent header value. Returns a context map (without
   :tracestate) or nil when the value is not a valid version-00 header:
   wrong length, upper-case hex, all-zero trace-id or parent-id, version
   ff or any version other than 00."
  [s]
  (when (string? s)
    (when-let [[_ v tid pid fl] (re-matches traceparent-re s)]
      (when (and (= v version) (trace-id? tid) (span-id? pid))
        {:version v :trace-id tid :parent-id pid :flags fl}))))

(defn format-traceparent
  "Format a context as a traceparent header value. Returns nil when the
   context is not valid (never emits a header a parser would refuse)."
  [{:keys [trace-id parent-id flags] :as ctx}]
  (when (valid? ctx)
    (str version "-" trace-id "-" parent-id "-" flags)))

;; ---------------------------------------------------------------------------
;; tracestate (opaque)
;; ---------------------------------------------------------------------------

(defn parse-tracestate
  "Split a tracestate header value into its opaque members. Returns a
   vector (possibly empty) or nil when the value is absent or carries more
   than 32 members (the whole header is discarded, as the spec allows)."
  [s]
  (when (string? s)
    (let [members (into [] (comp (map str/trim) (remove str/blank?))
                        (str/split s #","))]
      (when (<= (count members) max-tracestate-members)
        members))))

(defn format-tracestate
  "Join opaque tracestate members into a header value. Returns nil for an
   empty or absent list, or when there are more than 32 members."
  [members]
  (when (and (seq members) (<= (count members) max-tracestate-members))
    (str/join "," members)))

;; ---------------------------------------------------------------------------
;; Both headers
;; ---------------------------------------------------------------------------

(defn parse
  "Parse a traceparent value and an optional tracestate value into one
   context. nil when traceparent is invalid. An invalid tracestate is
   dropped; it never invalidates the traceparent."
  ([traceparent] (parse traceparent nil))
  ([traceparent tracestate]
   (when-let [ctx (parse-traceparent traceparent)]
     (let [ts (parse-tracestate tracestate)]
       (cond-> ctx (seq ts) (assoc :tracestate ts))))))

(defn ->headers
  "Header map {\"traceparent\" .. \"tracestate\" ..} for a valid context;
   tracestate is omitted when empty. nil for an invalid context."
  [ctx]
  (when-let [tp (format-traceparent ctx)]
    (let [ts (format-tracestate (:tracestate ctx))]
      (cond-> {"traceparent" tp}
        ts (assoc "tracestate" ts)))))

(defn <-headers
  "Context from a header map. Keys are matched case-insensitively (HTTP
   header names are case-insensitive) and may be strings or keywords."
  [headers]
  (let [by-name (into {}
                      (map (fn [[k v]] [(str/lower-case (name k)) v]))
                      headers)]
    (parse (get by-name "traceparent") (get by-name "tracestate"))))

;; ---------------------------------------------------------------------------
;; Derivation — the random source is always an argument
;; ---------------------------------------------------------------------------

(def ^:private max-attempts 16)

(defn- byte->hex [b]
  (let [b (bit-and (long b) 0xff)
        s #?(:clj  (Integer/toHexString b)
             :cljs (.toString b 16))]
    (if (< b 16) (str "0" s) s)))

(defn random-hex
  "Lower-hex string of `n-bytes` bytes drawn from `random-fn`, a fn of no
   arguments returning an integer (only its low 8 bits are used)."
  [random-fn n-bytes]
  (apply str (repeatedly n-bytes #(byte->hex (random-fn)))))

(defn- draw-valid
  "Draw ids until `valid-fn` accepts one; nil after max-attempts (a source
   stuck at zero must not loop forever)."
  [random-fn n-bytes valid-fn]
  (some (fn [_] (let [s (random-hex random-fn n-bytes)]
                  (when (valid-fn s) s)))
        (range max-attempts)))

(defn new-span-id
  "Fresh 16-hex span id from `random-fn`, or nil if the source yields only
   zero ids."
  [random-fn]
  (draw-valid random-fn 8 span-id?))

(defn new-trace-id
  "Fresh 32-hex trace id from `random-fn`, or nil if the source yields
   only zero ids."
  [random-fn]
  (draw-valid random-fn 16 trace-id?))

(defn new-root
  "A new root context: fresh trace-id and parent-id from `random-fn`,
   flags default \"01\" (sampled), no tracestate. nil when the source
   cannot produce non-zero ids."
  ([random-fn] (new-root random-fn "01"))
  ([random-fn flags]
   (let [tid (new-trace-id random-fn)
         pid (new-span-id random-fn)
         ctx {:version version :trace-id tid :parent-id pid :flags flags}]
     (when (valid? ctx) ctx))))

(defn child
  "Context for a hop below `ctx`: same trace-id, flags and tracestate,
   parent-id replaced by `new-span-id` (the id of the span the caller
   opened). nil when `ctx` is invalid or `new-span-id` is not a valid
   span id."
  [ctx new-span-id]
  (when (and (valid? ctx) (span-id? new-span-id))
    (assoc ctx :parent-id new-span-id)))

#?(:clj
   (defn secure-random-fn
     "A random-fn backed by one java.security.SecureRandom. Convenience for
      callers; nothing in this ns calls it implicitly."
     []
     (let [r (java.security.SecureRandom.)]
       (fn [] (.nextInt r 256)))))
