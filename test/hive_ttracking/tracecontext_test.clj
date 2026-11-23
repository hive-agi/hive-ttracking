(ns hive-ttracking.tracecontext-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-ttracking.span :as span]
            [hive-ttracking.track :as track]
            [hive-ttracking.tracecontext :as tc]))

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(def hex-chars "0123456789abcdef")

(defn gen-hex [n]
  (gen/fmap #(apply str %) (gen/vector (gen/elements hex-chars) n)))

(def gen-trace-id (gen/such-that tc/trace-id? (gen-hex 32) 100))
(def gen-span-id  (gen/such-that tc/span-id? (gen-hex 16) 100))
(def gen-flags    (gen-hex 2))

(def gen-member
  ;; opaque list-member: key=value, no comma, no surrounding blanks
  (gen/fmap (fn [[k v]] (str k "=" v))
            (gen/tuple (gen/fmap #(apply str %)
                                 (gen/vector (gen/elements "abcdefghijklmnopqrstuvwxyz0123456789_-*/@") 1 12))
                       (gen/fmap #(apply str %)
                                 (gen/vector (gen/elements "ABCxyz0123456789!#$%&'()*+-./:<>?@[]^_`{|}~") 1 16)))))

(def gen-traceparent
  (gen/fmap (fn [[t p f]] (str "00-" t "-" p "-" f))
            (gen/tuple gen-trace-id gen-span-id gen-flags)))

(def gen-ctx
  (gen/fmap (fn [[t p f ts]]
              (cond-> {:version "00" :trace-id t :parent-id p :flags f}
                (seq ts) (assoc :tracestate ts)))
            (gen/tuple gen-trace-id gen-span-id gen-flags
                       (gen/vector gen-member 0 tc/max-tracestate-members))))

(defn seeded-random-fn
  "Deterministic random source for tests."
  [seed]
  (let [r (java.util.Random. seed)]
    (fn [] (.nextInt r 256))))

;; ---------------------------------------------------------------------------
;; Properties from the card
;; ---------------------------------------------------------------------------

(defspec format-after-parse-is-identity 300
  (prop/for-all [s gen-traceparent]
    (= s (tc/format-traceparent (tc/parse-traceparent s)))))

(defspec headers-round-trip 200
  (prop/for-all [ctx gen-ctx]
    (= ctx (tc/<-headers (tc/->headers ctx)))))

(defspec tracestate-format-after-parse-is-identity 200
  (prop/for-all [ms (gen/not-empty (gen/vector gen-member 1 tc/max-tracestate-members))]
    (let [s (str/join "," ms)]
      (= s (tc/format-tracestate (tc/parse-tracestate s))))))

(defspec bad-length-does-not-parse 300
  (prop/for-all [s gen-traceparent
                 cut (gen/choose 1 54)
                 extra (gen-hex 1)
                 grow? gen/boolean]
    (let [bad (if grow? (str s extra) (subs s 0 (- (count s) cut)))]
      (nil? (tc/parse-traceparent bad)))))

(defspec upper-case-does-not-parse 300
  (prop/for-all [s (gen/such-that #(re-find #"[a-f]" %) gen-traceparent 100)]
    (nil? (tc/parse-traceparent (str/upper-case s)))))

(defspec single-upper-case-char-does-not-parse 300
  (prop/for-all [s (gen/such-that #(re-find #"[a-f]" %) gen-traceparent 100)
                 pick gen/nat]
    (let [idxs (keep-indexed (fn [i c] (when (#{\a \b \c \d \e \f} c) i)) s)
          i    (nth idxs (mod pick (count idxs)))
          bad  (str (subs s 0 i) (str/upper-case (subs s i (inc i))) (subs s (inc i)))]
      (nil? (tc/parse-traceparent bad)))))

(defspec all-zero-ids-do-not-parse 200
  (prop/for-all [t gen-trace-id p gen-span-id f gen-flags]
    (and (nil? (tc/parse-traceparent (str "00-" (apply str (repeat 32 \0)) "-" p "-" f)))
         (nil? (tc/parse-traceparent (str "00-" t "-" (apply str (repeat 16 \0)) "-" f))))))

(defspec version-ff-does-not-parse 200
  (prop/for-all [s gen-traceparent]
    (nil? (tc/parse-traceparent (str "ff" (subs s 2))))))

(defspec child-keeps-trace-id-and-flags-and-changes-parent-id 300
  (prop/for-all [[ctx new-id] (gen/such-that (fn [[ctx id]] (not= id (:parent-id ctx)))
                                             (gen/tuple gen-ctx gen-span-id) 100)]
    (let [c (tc/child ctx new-id)]
      (and (tc/valid? c)
           (= (:trace-id ctx) (:trace-id c))
           (= (:flags ctx) (:flags c))
           (= (:tracestate ctx) (:tracestate c))
           (= new-id (:parent-id c))
           (not= (:parent-id ctx) (:parent-id c))))))

(defspec new-root-is-valid-and-reproducible 100
  (prop/for-all [seed gen/large-integer]
    (let [a (tc/new-root (seeded-random-fn seed))
          b (tc/new-root (seeded-random-fn seed))]
      (and (tc/valid? a) (= a b)
           (= a (tc/parse-traceparent (tc/format-traceparent a)))))))

;; ---------------------------------------------------------------------------
;; Examples
;; ---------------------------------------------------------------------------

(def spec-example "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")

(deftest w3c-spec-example
  (is (= {:version "00"
          :trace-id "4bf92f3577b34da6a3ce929d0e0e4736"
          :parent-id "00f067aa0ba902b7"
          :flags "01"}
         (tc/parse-traceparent spec-example)))
  (is (tc/sampled? (tc/parse-traceparent spec-example)))
  (is (not (tc/sampled? (tc/parse-traceparent
                         "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00")))))

(deftest refused-values
  (testing "not a string, empty, wrong separators, other versions"
    (is (nil? (tc/parse-traceparent nil)))
    (is (nil? (tc/parse-traceparent "")))
    (is (nil? (tc/parse-traceparent (str/replace spec-example "-" "_"))))
    (is (nil? (tc/parse-traceparent (str "01" (subs spec-example 2)))))
    (is (nil? (tc/parse-traceparent (str spec-example "-extra")))))
  (testing "format refuses an invalid context"
    (is (nil? (tc/format-traceparent {:version "00" :trace-id "x" :parent-id "y" :flags "01"})))
    (is (nil? (tc/format-traceparent nil)))))

(deftest tracestate-opaque-and-bounded
  (is (= ["rojo=00f067aa0ba902b7" "congo=t61rcWkgMzE"]
         (tc/parse-tracestate "rojo=00f067aa0ba902b7 , ,congo=t61rcWkgMzE")))
  (is (nil? (tc/parse-tracestate (str/join "," (map #(str "k" % "=v") (range 33))))))
  (is (= 32 (count (tc/parse-tracestate (str/join "," (map #(str "k" % "=v") (range 32)))))))
  (testing "a bad tracestate is dropped, the traceparent survives"
    (let [ctx (tc/parse spec-example (str/join "," (map #(str "k" % "=v") (range 33))))]
      (is (= "4bf92f3577b34da6a3ce929d0e0e4736" (:trace-id ctx)))
      (is (nil? (:tracestate ctx))))))

(deftest headers-are-case-insensitive
  (is (= "00f067aa0ba902b7"
         (:parent-id (tc/<-headers {"TraceParent" spec-example}))))
  (is (= ["a=b"] (:tracestate (tc/<-headers {:traceparent spec-example :tracestate "a=b"})))))

(deftest new-root-with-zero-source-gives-nil
  (is (nil? (tc/new-root (constantly 0))))
  (is (nil? (tc/child (tc/parse-traceparent spec-example) "0000000000000000"))))

;; ---------------------------------------------------------------------------
;; Span bridge
;; ---------------------------------------------------------------------------

(deftest span-bridge
  (let [remote (tc/parse spec-example "vendor=opaque")
        s      (track/make-span {:name :tool-call :trace-context remote})
        out    (span/span->context s)]
    (testing "context -> span parent"
      (is (= "00f067aa0ba902b7" (:parent-id s)))
      (is (= "4bf92f3577b34da6a3ce929d0e0e4736" (get-in s [:tags :w3c/trace-id]))))
    (testing "span -> context: same trace, flags and tracestate; parent-id is this span"
      (is (tc/valid? out))
      (is (= (:trace-id remote) (:trace-id out)))
      (is (= (:flags remote) (:flags out)))
      (is (= ["vendor=opaque"] (:tracestate out)))
      (is (= (span/w3c-span-id s) (:parent-id out)))
      (is (not= (:parent-id remote) (:parent-id out))))
    (testing "an explicit :w3c/span-id tag wins"
      (is (= "aaaaaaaaaaaaaaaa"
             (:parent-id (span/span->context (span/tag s :w3c/span-id "aaaaaaaaaaaaaaaa")))))))
  (testing "a span with no trace gives no context; an invalid ctx leaves the span alone"
    (let [s (track/make-span {:name :x})]
      (is (nil? (span/span->context s)))
      (is (= s (span/with-trace-parent s {:trace-id "nope"})))
      (is (= s (span/with-trace-parent s nil))))))
