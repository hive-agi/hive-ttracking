(ns hive-ttracking.time.zone-test
  "Unit tests of the zone seam, in America/Sao_Paulo -- the one place the
   library touches the platform, pinned on both legs.

   The cases are named by the edge-case CATEGORY (DST start, DST end, the
   half-open period, the zone value object, today), never after a calendario
   row: ttrack's tests must not know funeraria.

   The two rules fixed here, on both legs:
     - a gap (DST start) is an explicit refusal :time/zone-gap;
     - an overlap (DST end) takes the earliest offset (the first occurrence),
       deterministically."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.time.clock :as clock]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.instant :as instant]
            [hive-ttracking.time.zone :as zone]))

(def ^:private sp "America/Sao_Paulo")

(defn- m [f & args]
  (try (apply f args)
       (catch #?(:clj Exception :cljs :default) e (error/reason e))))

(defn- iso [ms] (instant/format-instant ms))
(defn- inst [s] (instant/parse-instant s))

;; -- the ZoneId value object -------------------------------------------

(deftest zone-value-object-validates
  (is (= sp (zone/->zone sp)))
  (is (= "UTC" (zone/->zone "UTC")))
  (is (= "Asia/Tokyo" (zone/->zone "Asia/Tokyo")))
  (testing "an unknown zone, a blank or a non-string -> :time/zone-unknown"
    (is (= :time/zone-unknown (m zone/->zone "Marte/Olimpo")))
    (is (= :time/zone-unknown (m zone/->zone "")))
    (is (= :time/zone-unknown (m zone/->zone nil)))
    (is (= :time/zone-unknown (m zone/->zone 42))))
  (is (zone/zone? sp))
  (is (not (zone/zone? "Marte/Olimpo")))
  (is (not (zone/zone? nil))))

;; -- DST start: the gap is refused, the edges are valid ---------------

(deftest dst-start-gap-and-edges
  (testing "the 00:00-01:00 gap of 2018-11-04 never happened -> :time/zone-gap"
    (is (= :time/zone-gap (m zone/local->instant {:year 2018 :month 11 :day 4}
                                              {:hour 0 :minute 0 :second 0} sp)))
    (is (= :time/zone-gap (m zone/local->instant {:year 2018 :month 11 :day 4}
                                              {:hour 0 :minute 30 :second 0} sp))))
  (testing "the edge at 01:00 is valid: 01:00 -02:00 = 03:00Z"
    (is (= "2018-11-04T03:00:00Z"
           (iso (zone/local->instant {:year 2018 :month 11 :day 4}
                                     {:hour 1 :minute 0 :second 0} sp)))))
  (testing "just before the gap is valid: 2018-11-03 23:30 -03:00 = 02:30Z"
    (is (= "2018-11-04T02:30:00Z"
           (iso (zone/local->instant {:year 2018 :month 11 :day 3}
                                     {:hour 23 :minute 30 :second 0} sp)))))
  (testing "day-start on the gap day is 00:00 in the offset BEFORE the transition"
    (is (= "2018-11-04T03:00:00Z" (iso (zone/day-start {:year 2018 :month 11 :day 4} sp))))))

;; -- DST end: the overlap takes the earliest offset -------------------

(deftest dst-end-overlap-takes-earliest-offset
  (testing "23:30 happened twice on 2019-02-16; the first occurrence wins"
    (is (= "2019-02-17T01:30:00Z"
           (iso (zone/local->instant {:year 2019 :month 2 :day 16}
                                     {:hour 23 :minute 30 :second 0} sp)))))
  (testing "the day boundaries around the transition"
    (is (= "2019-02-16T02:00:00Z" (iso (zone/day-start {:year 2019 :month 2 :day 16} sp))))
    (is (= "2019-02-17T03:00:00Z" (iso (zone/day-start {:year 2019 :month 2 :day 17} sp))))))

;; -- the half-open interval of instants -------------------------------

(deftest period-interval-is-half-open
  (testing "[start@00:00, (end+1)@00:00) -- never 23:59:59"
    (is (= ["2026-11-25T03:00:00Z" "2026-11-26T03:00:00Z"]
           (mapv iso (zone/period->instant-interval
                      {:start {:year 2026 :month 11 :day 25}
                       :end {:year 2026 :month 11 :day 25}} sp)))))
  (testing "the start bound is INCLUSIVE and the end bound EXCLUSIVE"
    (let [p {:start {:year 2026 :month 11 :day 25} :end {:year 2026 :month 11 :day 25}}]
      (is (zone/period-contains-instant? p sp (inst "2026-11-25T03:00:00Z")))
      (is (not (zone/period-contains-instant? p sp (inst "2026-11-25T02:59:59Z"))))
      (is (zone/period-contains-instant? p sp (inst "2026-11-26T02:59:59Z")))
      (is (not (zone/period-contains-instant? p sp (inst "2026-11-26T03:00:00Z"))))))
  (testing "a period ACROSS the DST day tiles with no gap and no overlap"
    ;; 2018-11-03 00:00 -03:00 = 03:00Z; 2018-11-05 00:00 -02:00 = 02:00Z.
    ;; The DST day is 23 h, so the interval is 47 h, not 48 h.
    (is (= ["2018-11-03T03:00:00Z" "2018-11-05T02:00:00Z"]
           (mapv iso (zone/period->instant-interval
                      {:start {:year 2018 :month 11 :day 3}
                       :end {:year 2018 :month 11 :day 4}} sp))))
    (let [p {:start {:year 2018 :month 11 :day 3} :end {:year 2018 :month 11 :day 4}}]
      (is (zone/period-contains-instant? p sp (inst "2018-11-05T01:59:59Z")))
      (is (not (zone/period-contains-instant? p sp (inst "2018-11-05T02:00:00Z"))))))
  (testing "the last second of the period is not lost"
    (let [p {:start {:year 2026 :month 11 :day 25} :end {:year 2026 :month 11 :day 25}}]
      (is (zone/period-contains-instant? p sp (inst "2026-11-26T02:59:59Z"))))))

;; -- instant -> the wall clock ----------------------------------------

(deftest instant-to-day-and-offset
  (testing "the ZONE is the argument, never a field of the value"
    (is (= {:year 2018 :month 11 :day 4} (zone/instant->day (inst "2018-11-04T03:00:00Z") sp)))
    (is (= {:year 2018 :month 11 :day 3} (zone/instant->day (inst "2018-11-03T03:00:00Z") sp))))
  (testing "offset-ms is wall-clock-as-UTC-ms - instant"
    (is (= -7200000 (zone/offset-ms (inst "2018-11-04T03:00:00Z") sp)))
    (is (= -10800000 (zone/offset-ms (inst "2018-11-03T03:00:00Z") sp)))
    (is (= 0 (zone/offset-ms (inst "2026-11-25T00:00:00Z") "UTC"))))
  (testing "instant->local returns the wall clock"
    (is (= {:day {:year 2018 :month 11 :day 4} :time {:hour 1 :minute 0 :second 0}}
           (zone/instant->local (inst "2018-11-04T03:00:00Z") sp)))))

;; -- the op-table aliases ---------------------------------------------

(deftest op-table-aliases-agree
  (is (= (zone/local->instant {:year 2019 :month 2 :day 16} {:hour 23 :minute 30 :second 0} sp)
         (zone/local-time->instant {:year 2019 :month 2 :day 16} {:hour 23 :minute 30 :second 0} sp)))
  (is (= (zone/day-start {:year 2018 :month 11 :day 4} sp)
         (zone/day->instant {:year 2018 :month 11 :day 4} sp))))

;; -- other zones -------------------------------------------------------

(deftest other-zones
  (is (= "2026-11-24T15:00:00Z" (iso (zone/day-start {:year 2026 :month 11 :day 25} "Asia/Tokyo"))))
  (is (= "2026-11-25T00:00:00Z" (iso (zone/day-start {:year 2026 :month 11 :day 25} "UTC")))))

;; -- today: the one place a clock read meets a zone -------------------

(deftest today-reads-the-day-in-the-zone
  (let [c (clock/fixed-clock (inst "2026-11-25T23:30:00Z"))
        utc (clock/today c "UTC")
        sao (clock/today c sp)
        tok (clock/today c "Asia/Tokyo")]
    (testing "one instant near midnight resolves per zone"
      (is (= {:year 2026 :month 11 :day 25} utc))
      (is (= {:year 2026 :month 11 :day 25} sao))
      (is (= {:year 2026 :month 11 :day 26} tok))
      (testing "the zone changes the answer: not all three agree"
        (is (< 1 (count (distinct [utc sao tok])))))))
  (testing "the other side of midnight: SP falls to the previous day"
    (let [c (clock/fixed-clock (inst "2026-11-25T02:30:00Z"))]
      (is (= {:year 2026 :month 11 :day 25} (clock/today c "UTC")))
      (is (= {:year 2026 :month 11 :day 24} (clock/today c sp)))
      (is (= {:year 2026 :month 11 :day 25} (clock/today c "Asia/Tokyo")))))
  (testing "today is deterministic on a fixed clock"
    (let [c (clock/fixed-clock (inst "2026-11-25T23:30:00Z"))]
      (is (= (clock/today c sp) (clock/today c sp))))))
