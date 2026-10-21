(ns hive-ttracking.metrics-test
  "Golden, property and concurrency tests for the metrics port."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-ttracking.metrics :as m]))

(use-fixtures :each (fn [f] (m/install! nil) (try (f) (finally (m/install! nil)))))

(defn- recording-sink
  "Stub sink that appends every observation to atom a."
  [a]
  (reify m/IMetricsSink
    (observe! [_ k ns outcome] (swap! a conj [k ns outcome]))
    (snapshot [_] {})))

(def throwing-sink
  (reify m/IMetricsSink
    (observe! [_ _ _ _] (throw (RuntimeException. "sink down")))
    (snapshot [_] (throw (RuntimeException. "sink down")))))

;; ---------------------------------------------------------------------------
;; Golden
;; ---------------------------------------------------------------------------

(deftest no-sink-is-noop
  (is (nil? (m/record! [:t :x] 10 :ok)))
  (is (= {} (m/snapshot (m/current-sink))))
  (is (= 7 (m/timed [:t :x] 7))))

(deftest timed-records-ok-and-error
  (let [obs (atom [])]
    (m/install! (recording-sink obs))
    (is (= 3 (m/timed [:t :ok] (+ 1 2))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                          (m/timed [:t :err] (throw (ex-info "boom" {})))))
    (is (= [[:t :ok] [:t :err]] (mapv first @obs)))
    (is (= [:ok :error] (mapv #(nth % 2) @obs)))
    (is (every? #(and (int? (second %)) (<= 0 (second %))) @obs))))

(deftest sink-failure-never-fails-the-call
  (let [before (m/sink-error-count)]
    (m/install! throwing-sink)
    (is (nil? (m/record! [:t :x] 1 :ok)))
    (is (= :v (m/timed [:t :x] :v)))
    (is (= (+ before 2) (m/sink-error-count)))))

(deftest stats-sink-golden
  (let [s (m/stats-sink)]
    (doseq [ns [100 200 300 1000000]] (m/observe! s [:a :b] ns :ok))
    (m/observe! s [:a :b] 50 :error)
    (let [row (get (m/snapshot s) [:a :b])]
      (is (= 5 (:count row)))
      (is (= 1 (:errors row)))
      (is (= 1000650 (:total-ns row)))
      (is (= 1000000 (:max-ns row)))
      (is (= 255 (:p50-ns row)) "bucket [128,256) upper bound")
      (is (= 1000000 (:p99-ns row)) "clamped to max"))
    (m/reset-sink! s)
    (is (= {} (m/snapshot s)))))

(deftest composite-fans-out-and-isolates-failures
  (let [a (m/stats-sink) b (m/stats-sink)
        c (m/composite-sink a throwing-sink b)]
    (m/observe! c [:x :y] 10 :ok)
    (is (= 1 (get-in (m/snapshot a) [[:x :y] :count])))
    (is (= 1 (get-in (m/snapshot b) [[:x :y] :count])))
    (is (= 1 (get-in (m/snapshot c) [[:x :y] :count])))
    (m/reset-sink! c)
    (is (= {} (m/snapshot a) (m/snapshot b)))))

(deftest hotspots-golden
  (let [snap {[:mcp/tool "memory" "search"] {:count 10 :errors 1 :total-ns 3000000
                                             :max-ns 900000 :p50-ns 200000
                                             :p95-ns 800000 :p99-ns 900000}
              [:mcp/mw :piggyback]          {:count 100 :errors 0 :total-ns 1000000
                                             :max-ns 50000 :p50-ns 8000
                                             :p95-ns 30000 :p99-ns 50000}}]
    (testing ":by :total"
      (let [[r1 r2] (m/hotspots snap {:by :total})]
        (is (= [:mcp/tool "memory" "search"] (:k r1)))
        (is (= 3.0 (:total-ms r1)))
        (is (= 0.3 (:mean-ms r1)))
        (is (= 0.75 (:share r1)))
        (is (= 0.25 (:share r2)))))
    (testing ":by :count, :limit"
      (is (= [[:mcp/mw :piggyback]] (mapv :k (m/hotspots snap {:by :count :limit 1})))))
    (testing ":area filters by first key element, keyword or string"
      (is (= [[:mcp/mw :piggyback]] (mapv :k (m/hotspots snap {:area :mcp/mw}))))
      (is (= [[:mcp/mw :piggyback]] (mapv :k (m/hotspots snap {:area "mcp/mw"}))))
      (is (= 1.0 (:share (first (m/hotspots snap {:area :mcp/mw}))))))
    (is (= [] (m/hotspots {} {})))))

;; ---------------------------------------------------------------------------
;; Properties
;; ---------------------------------------------------------------------------

(def gen-elapsed
  (gen/one-of [(gen/large-integer* {:min 0 :max 1000})
               (gen/large-integer* {:min 0 :max 10000000000})]))

(defspec percentiles-monotonic 200
  (prop/for-all [xs (gen/not-empty (gen/vector gen-elapsed))]
    (let [s (m/stats-sink)]
      (doseq [x xs] (m/observe! s [:p] x :ok))
      (let [{:keys [count p50-ns p95-ns p99-ns max-ns total-ns]} (get (m/snapshot s) [:p])]
        (and (= count (clojure.core/count xs))
             (= total-ns (reduce + xs))
             (= max-ns (reduce max xs))
             (<= p50-ns p95-ns p99-ns max-ns)
             ;; bucket upper bound never under-reports the true percentile by
             ;; more than one bucket (true value <= reported, within 2x)
             (let [sorted (vec (sort xs))
                   true-p50 (nth sorted (dec (max 1 (long (Math/ceil (* 0.5 (clojure.core/count xs)))))))]
               (and (<= true-p50 p50-ns)
                    (<= p50-ns (max 1 (* 2 true-p50))))))))))

(defspec hotspots-share-sums-to-one 200
  (prop/for-all [rows (gen/vector (gen/tuple gen/keyword (gen/large-integer* {:min 1 :max 1000})
                                             (gen/large-integer* {:min 1 :max 100000000}))
                                  1 30)]
    (let [s (m/stats-sink)]
      (doseq [[k n ns] rows] (dotimes [_ (min n 5)] (m/observe! s [:area k] ns :ok)))
      (let [hs (m/hotspots (m/snapshot s) {:limit 0})
            total (reduce + (map :share hs))]
        (and (< (Math/abs (- 1.0 total)) 1e-9)
             (apply >= (map :total-ms hs)))))))

(deftest count-conservation-under-8-threads
  (let [s        (m/stats-sink)
        threads  8
        per      20000
        keys     [[:c :a] [:c :b] [:c :c]]
        start    (java.util.concurrent.CountDownLatch. 1)
        futs     (doall
                  (for [t (range threads)]
                    (future
                      (.await start)
                      (dotimes [i per]
                        (m/observe! s (keys (mod (+ i t) 3)) (inc i)
                                    (if (zero? (mod i 10)) :error :ok))))))]
    (.countDown start)
    (run! deref futs)
    (let [snap (m/snapshot s)]
      (is (= (* threads per) (reduce + (map :count (vals snap)))))
      (is (= (* threads (/ per 10)) (reduce + (map :errors (vals snap)))))
      (is (= (* threads (/ (* per (inc per)) 2)) (reduce + (map :total-ns (vals snap)))))
      (is (= per (reduce max (map :max-ns (vals snap))))))))

;; ---------------------------------------------------------------------------
;; Overhead
;; ---------------------------------------------------------------------------

(deftest record-under-300ns-warm
  (let [s (m/stats-sink)
        k [:mcp/tool "memory" "search"]
        n 2000000]
    (m/install! s)
    (dotimes [_ n] (m/record! k 1234 :ok))
    (let [t0  (System/nanoTime)
          _   (dotimes [_ n] (m/record! k 1234 :ok))
          per (/ (double (- (System/nanoTime) t0)) n)]
      (is (< per 300.0) (str "record! cost " per " ns")))))
