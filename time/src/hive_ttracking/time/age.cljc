(ns hive-ttracking.time.age
  "Completed years from a birth Day to a Day, with an explicit leap policy.

   The leap policy is REQUIRED, with no default: a birth on 29 February has no
   anniversary in a common year, and WHICH day it completes on is a legal
   statement, not arithmetic. The statute lives in the consumer (calendario
   binds :leap/mar-01, Codigo Civil art. 132 paragraph 3); the arithmetic lives
   here.

     :leap/feb-28 -- the birthday falls on 28 February in a common year;
     :leap/mar-01  -- it falls on 1 March (the next day).

   The arithmetic stays integer calendar arithmetic: y = day.year - birth.year,
   minus 1 when the (month, day) pair has not yet arrived. Never days/365.25,
   never day-of-year, never a plusYears loop.

   A missing or unknown policy is refused with its OWN reason
   (:time/leap-policy-missing, :time/leap-policy-unknown), distinct from the
   before-birth refusal (:time/age-before-birth)."
  (:require [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]))

(def policies
  "The CLOSED set of leap policies, as a malli :enum."
  [:enum :leap/feb-28 :leap/mar-01])

(defn leap-policy?
  "True when p is a leap policy in the closed set."
  [p]
  (contains? #{:leap/feb-28 :leap/mar-01} p))

(defn- anniversary
  "The (month, day) the birthday is observed on IN `year`. In a LEAP year 29
   February exists, so a 29 February birth completes on 29 February under BOTH
   policies; the policies differ ONLY in a common year, where :leap/feb-28
   observes it on 28 February and :leap/mar-01 on 1 March. A birth on any other
   day keeps its own pair."
  [birth policy year]
  (if (and (= 2 (long (:month birth))) (= 29 (long (:day birth)))
           (not (day/leap-year? year)))
    (if (= :leap/feb-28 policy) [2 28] [3 1])
    [(long (:month birth)) (long (:day birth))]))

(defn completed-years
  "Completed years of birth as of day, under leap-policy (from the closed set).

   In a LEAP year 29 February exists, so a 29 February birth completes on
   29 February under BOTH policies; the policies differ ONLY in a common year,
   where :leap/feb-28 completes on 28 February and :leap/mar-01 on 1 March.

   day before birth -> :time/age-before-birth; a policy outside the set ->
   :time/leap-policy-unknown; a nil policy -> :time/leap-policy-missing."
  [birth day leap-policy]
  (when (nil? leap-policy)
    (error/refuse :time/leap-policy-missing "a leap policy is required"
                  {:birth birth :day day}))
  (when-not (leap-policy? leap-policy)
    (error/refuse :time/leap-policy-unknown
                  (str "unknown leap policy: " (pr-str leap-policy))
                  {:leap-policy leap-policy}))
  (when (neg? (day/compare-day day birth))
    (error/refuse :time/age-before-birth "day before birth"
                  {:birth birth :day day}))
  (let [y (- (long (:year day)) (long (:year birth)))
        [bm bd] (anniversary birth leap-policy (long (:year day)))]
    (if (neg? (compare [(long (:month day)) (long (:day day))] [bm bd]))
      (dec y)
      y)))
