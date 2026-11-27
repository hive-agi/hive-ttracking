(ns hive-ttracking.time.core-test
  "Unit tests of the Core: day, month, time-of-day, period, error.

   Moved from calendario's core_test.cljc (10 tests / 62 assertions) and
   rewritten with the English API."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.month :as month]
            [hive-ttracking.time.period :as period]
            [hive-ttracking.time.time-of-day :as tod]))

(defn- m [f & args]
  (try (apply f args)
       (catch #?(:clj Exception :cljs :default) e (error/reason e))))

(deftest epoch-and-bijection
  (is (= 0 (day/epoch-day 1970 1 1)))
  (is (= -1 (day/epoch-day 1969 12 31)))
  (is (= {:year 1970 :month 1 :day 1} (day/from-epoch-day 0)))
  (doseq [d [{:year 1900 :month 1 :day 1} {:year 2000 :month 2 :day 29}
             {:year 2199 :month 12 :day 31} {:year 2026 :month 11 :day 25}]]
    (is (= d (day/from-epoch-day (day/epoch d))) (str d))))

(deftest leap-years
  (is (day/leap-year? 2024))
  (is (not (day/leap-year? 2023)))
  (is (day/leap-year? 2000))
  (is (not (day/leap-year? 1900)))
  (is (= 29 (day/days-in-month 2024 2)))
  (is (= 28 (day/days-in-month 2023 2))))

(deftest construction-and-reasons
  (testing "impossible first, then range (the oracle's order)"
    (is (= :time/day-impossible (m day/->day 2026 2 31)))
    (is (= :time/day-impossible (m day/->day 2023 2 29)))
    (is (= :time/day-impossible (m day/->day 2026 13 13)))
    (is (= :time/day-out-of-range (m day/->day 1 1 1)))
    (is (= :time/day-out-of-range (m day/->day 1899 12 31)))
    (is (= :time/day-out-of-range (m day/->day 2200 1 1))))
  (is (= {:year 2024 :month 2 :day 29} (day/->day 2024 2 29))))

(deftest predicate-never-throws
  (is (day/day? {:year 2026 :month 11 :day 25}))
  (is (not (day/day? {:year 2026 :month 2 :day 31})))
  (is (not (day/day? {:year 1 :month 1 :day 1})))
  (is (not (day/day? nil)))
  (is (not (day/day? {:year "2026" :month 11 :day 25}))))

(deftest day-arithmetic
  (is (= 1 (day/days-between {:year 2026 :month 10 :day 9} {:year 2026 :month 10 :day 10})))
  (is (= -1 (day/days-between {:year 2026 :month 10 :day 10} {:year 2026 :month 10 :day 9})))
  (is (= 2 (day/days-between {:year 2018 :month 11 :day 3} {:year 2018 :month 11 :day 5})))
  (is (= 2 (day/days-between {:year 2019 :month 2 :day 16} {:year 2019 :month 2 :day 18})))
  (is (= {:year 2019 :month 2 :day 17} (day/add-days {:year 2019 :month 2 :day 16} 1)))
  (is (= {:year 2018 :month 11 :day 4} (day/add-days {:year 2018 :month 11 :day 3} 1)))
  (is (= {:year 2027 :month 1 :day 1} (day/add-days {:year 2026 :month 12 :day 31} 1)))
  (is (= :time/day-out-of-range (m day/add-days {:year 2199 :month 12 :day 31} 1))))

(deftest day-of-week-and-weekend
  (is (= 4 (day/day-of-week {:year 1970 :month 1 :day 1})))
  (is (= 3 (day/day-of-week {:year 2026 :month 11 :day 25})))
  (is (day/weekend? {:year 2026 :month 11 :day 28}))
  (is (day/weekend? {:year 2026 :month 11 :day 29}))
  (is (not (day/weekend? {:year 2026 :month 11 :day 30}))))

(deftest month
  (is (= {:year 2026 :month 7} (month/->month 2026 7)))
  (is (= :time/month-impossible (m month/->month 2026 13)))
  (is (= :time/month-out-of-range (m month/->month 1 1)))
  (is (= {:year 2027 :month 2} (month/add-months {:year 2026 :month 7} 7)))
  (is (= {:year 2025 :month 12} (month/add-months {:year 2026 :month 1} -1)))
  (is (= {:year 2026 :month 11} (month/month-of-day {:year 2026 :month 11 :day 25})))
  (is (= {:year 2026 :month 11 :day 1} (month/first-day {:year 2026 :month 11})))
  (is (= {:year 2026 :month 11 :day 30} (month/last-day {:year 2026 :month 11}))))

(deftest time-of-day
  (is (= {:hour 8 :minute 30 :second 0} (tod/->time-of-day 8 30 0)))
  (is (= :time/time-out-of-range (m tod/->time-of-day 24 0 0)))
  (is (= :time/time-out-of-range (m tod/->time-of-day 8 60 0)))
  (is (= :time/time-out-of-range (m tod/->time-of-day 8 0 60)))
  (is (tod/time-of-day? {:hour 23 :minute 59 :second 59})))

(deftest period
  (is (= {:start {:year 2026 :month 11 :day 25} :end {:year 2026 :month 11 :day 25}}
         (period/->period {:year 2026 :month 11 :day 25} {:year 2026 :month 11 :day 25})))
  (is (= :time/period-missing-bound (m period/->period {:year 2026 :month 11 :day 25} nil)))
  (is (= :time/period-inverted (m period/->period {:year 2026 :month 11 :day 26} {:year 2026 :month 11 :day 25})))
  (is (= 1 (period/days (period/->period {:year 2026 :month 11 :day 25} {:year 2026 :month 11 :day 25}))))
  (is (= {:start {:year 2026 :month 11 :day 1} :end {:year 2026 :month 11 :day 25}}
         (period/current-month {:year 2026 :month 11 :day 25})))
  (is (= {:start {:year 2026 :month 10 :day 1} :end {:year 2026 :month 10 :day 31}}
         (period/previous-month {:year 2026 :month 11 :day 25}))))

(deftest refusal-and-defect
  (is (= :time/day-format (m (fn [] (error/refuse :time/day-format "x")))))
  (is (nil? (error/reason (try (error/refuse :not/a-reason "x")
                               (catch #?(:clj Exception :cljs :default) e e)))))
  (is (= {:ok 1} (error/attempt (fn [] 1))))
  (is (= :time/day-impossible (:error (error/attempt day/->day 2026 2 31))))
  (is (thrown? #?(:clj Exception :cljs :default)
               (error/attempt (fn [] (throw (ex-info "defect" {:x 1})))))))
