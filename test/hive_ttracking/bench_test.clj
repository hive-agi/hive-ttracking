(ns hive-ttracking.bench-test
  "Trifecta tests for tt/bench + defbench (Wave-2)."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.core :as tt]
            [hive-ttracking.bench :as bench]))

(deftest golden-bench-shape
  (testing "bench* returns populated BenchResult"
    (let [r (bench/bench*
              {:name :g.shape :runs 10 :warmup 2}
              (fn [] (Thread/sleep 1)))]
      (is (instance? hive_ttracking.bench.BenchResult r))
      (is (= 10 (:runs r)))
      (is (= 2 (:warmup r)))
      (is (= 10 (count (:samples-ns r))))
      (is (every? pos? (:samples-ns r)))
      (is (<= (:p50 r) (:p95 r)))
      (is (<= (:p95 r) (:p99 r)))
      (is (<= (:min r) (:p50 r)))
      (is (<= (:p99 r) (:max r)))
      (is (number? (:mean r)))
      (is (number? (:stddev r))))))

(deftest bench-swallows-iteration-throw
  (testing "thunk throw records elapsed, does not break the run"
    (let [calls (atom 0)
          r (bench/bench*
              {:name :g.throw :runs 5 :warmup 1}
              (fn []
                (swap! calls inc)
                (throw (RuntimeException. "x"))))]
      (is (= 5 (count (:samples-ns r))))
      (is (= 6 @calls))))) ; 1 warmup + 5 runs

(tt/defbench demo-defbench
  {:runs 5 :warmup 1 :threshold-p95-ms 5000.0}
  (+ 1 2))
