(ns hive-ttracking.time.codec-test
  "Unit tests of the ISO/RFC 3339 codecs (day, month, time-of-day, period) and
   the instant codec.

   Moved from calendario's formato tests and the fuso_test iso helpers, and
   rewritten with the English API. The instant codec additionally has to agree
   with `java.time.Instant/toString` on the clj leg: the golden rows pin the
   bytes, and this test pins them to the platform's own formatter."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.time.codec :as codec]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.instant :as instant]))

(defn- m [f & args]
  (try (apply f args)
       (catch #?(:clj Exception :cljs :default) e (error/reason e))))

(def ^:private rfc3339-samples
  ["1970-01-01T00:00:00Z"
   "2018-11-04T03:00:00Z"
   "2019-02-16T02:00:00Z"
   "2019-02-17T01:30:00Z"
   "2019-02-17T03:00:00Z"
   "2026-11-25T03:00:00Z"
   "2026-11-24T15:00:00Z"
   "1900-01-01T00:00:00Z"
   "2199-12-31T23:59:59Z"
   "2026-11-25T03:00:00.123Z"
   "2026-11-25T03:00:00.007Z"])

(deftest parse-day-shapes-and-reasons
  (is (= {:year 2026 :month 11 :day 25} (codec/parse-day "2026-11-25")))
  (testing "an instant is the wrong type, checked first (the oracle's order)"
    (is (= :time/day-is-instant (m codec/parse-day "2026-11-25T03:00:00Z"))))
  (is (= :time/day-format (m codec/parse-day "25/11/2026")))
  (is (= :time/day-format (m codec/parse-day "2026-1-1")))
  (is (= :time/day-format (m codec/parse-day 20261125)))
  (is (= :time/day-format (m codec/parse-day nil)))
  (testing "impossible first, then the range"
    (is (= :time/day-impossible (m codec/parse-day "2026-02-31")))
    (is (= :time/day-impossible (m codec/parse-day "2023-02-29")))
    (is (= :time/day-out-of-range (m codec/parse-day "1899-12-31")))
    (is (= :time/day-out-of-range (m codec/parse-day "2200-01-01"))))
  (is (= {:year 2024 :month 2 :day 29} (codec/parse-day "2024-02-29"))))

(deftest format-day-pads
  (is (= "2026-11-25" (codec/format-day {:year 2026 :month 11 :day 25})))
  (is (= "2026-01-05" (codec/format-day {:year 2026 :month 1 :day 5})))
  (is (= "1900-01-01" (codec/format-day {:year 1900 :month 1 :day 1}))))

(deftest parse-month-shapes-and-reasons
  (is (= {:year 2026 :month 11} (codec/parse-month "2026-11")))
  (is (= :time/month-is-instant (m codec/parse-month "2026-11-25T03:00:00Z")))
  (is (= :time/month-impossible (m codec/parse-month "2026-11-25")))
  (is (= :time/month-impossible (m codec/parse-month "2026-13")))
  (is (= :time/month-impossible (m codec/parse-month nil)))
  (is (= :time/month-out-of-range (m codec/parse-month "1899-12"))))

(deftest format-month-pads
  (is (= "2026-11" (codec/format-month {:year 2026 :month 11})))
  (is (= "2026-01" (codec/format-month {:year 2026 :month 1}))))

(deftest parse-time-legacy-layouts
  (is (= {:hour 8 :minute 0 :second 0} (codec/parse-time "8")))
  (is (= {:hour 8 :minute 30 :second 0} (codec/parse-time "8:30")))
  (is (= {:hour 8 :minute 30 :second 15} (codec/parse-time "8:30:15")))
  (is (= {:hour 0 :minute 0 :second 0} (codec/parse-time "0")))
  (is (= :time/time-format (m codec/parse-time "8:30:")))
  (is (= :time/time-format (m codec/parse-time "quarter past eight")))
  (is (= :time/time-format (m codec/parse-time nil)))
  (is (= :time/time-out-of-range (m codec/parse-time "24:50")))
  (is (= :time/time-out-of-range (m codec/parse-time "24:00")))
  (is (= :time/time-out-of-range (m codec/parse-time "8:60"))))

(deftest format-time-second-rule
  (is (= "08:30" (codec/format-time {:hour 8 :minute 30 :second 0})))
  (is (= "08:30:15" (codec/format-time {:hour 8 :minute 30 :second 15})))
  (is (= "00:00" (codec/format-time {:hour 0 :minute 0 :second 0}))))

(deftest format-period-english-keys
  (is (= {:start "2026-11-25" :end "2026-11-30"}
         (codec/format-period {:start {:year 2026 :month 11 :day 25}
                               :end {:year 2026 :month 11 :day 30}}))))

(deftest instant-round-trip
  (doseq [s ["1970-01-01T00:00:00Z"
             "2018-11-04T03:00:00Z"
             "2026-11-25T03:00:00Z"
             "2026-11-25T03:00:00.123Z"
             "1900-01-01T00:00:00Z"]]
    (is (= s (instant/format-instant (instant/parse-instant s))) (str s)))
  (is (= 0 (instant/parse-instant "1970-01-01T00:00:00Z")))
  (testing "a non-UTC offset is normalised to the same instant"
    (is (= (instant/parse-instant "2026-11-25T03:00:00Z")
           (instant/parse-instant "2026-11-25T00:00:00-03:00"))))
  (is (= :time/instant-format (m instant/parse-instant "2026-11-25")))
  (is (= :time/instant-format (m instant/parse-instant nil))))

#?(:clj
   (deftest instant-matches-java-time-to-string
     "The codec emits exactly the bytes java.time.Instant/toString does."
     (doseq [s rfc3339-samples]
       (is (= (str (java.time.Instant/parse s))
              (instant/format-instant (instant/parse-instant s)))
           (str s)))))
