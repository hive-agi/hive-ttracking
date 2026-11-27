(ns hive-ttracking.time.clock-test
  "Unit tests of the Clock port: system-clock and fixed-clock.

   The port IS the seam, so the determinism test freezes the clock with a VALUE
   and reads it back; no with-redefs, no dynamic var.

   today(clock, zone) is NOT tested here: it needs the zone mechanics of step-5
   (time/zone.cljc), which does not exist yet, so it lives in step-5's scope."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.time.clock :as clock]))

(deftest fixed-clock-is-frozen
  (let [c (clock/fixed-clock 1732492800000)]
    (is (= 1732492800000 (clock/now c)))
    (is (= (clock/now c) (clock/now c)))
    (is (= 0 (clock/now (clock/fixed-clock 0))))
    (is (= -1 (clock/now (clock/fixed-clock -1))))))

(deftest fixed-clock-is-deterministic-across-calls
  (testing "the same frozen value answers every time, on both legs"
    (let [c (clock/fixed-clock 1764200000000)]
      (is (apply = (repeatedly 5 #(clock/now c)))))))

(deftest system-clock-reads-a-plausible-instant
  (let [c (clock/system-clock)
        t1 (clock/now c)
        t2 (clock/now c)]
    (is (integer? t1))
    (is (pos? t1))
    (is (<= t1 t2))
    (testing "epoch ms, not seconds: 2020-01-01 is 1.577e12 ms"
      (is (> t1 1577836800000)))))

(deftest system-and-fixed-satisfy-the-same-protocol
  (is (satisfies? clock/Clock (clock/system-clock)))
  (is (satisfies? clock/Clock (clock/fixed-clock 0))))
