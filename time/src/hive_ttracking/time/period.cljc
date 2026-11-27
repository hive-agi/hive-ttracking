(ns hive-ttracking.time.period
  "The Period: a CLOSED interval of days, \"from 01/11 to 25/11\", both ends
   included. Half-open is a property of the interval of INSTANTS a Period
   becomes under a zone, never of the Period.

   Both ends are required: a missing end is a REFUSAL, never an open interval --
   it is not \"up to today\".

   Map keys are the generic English names (:start :end)."
  (:require [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.month :as month]))

(defn ->period
  "Validating constructor: a nil end -> :time/period-missing-bound; start >
   end -> :time/period-inverted."
  [start end]
  (when (nil? end)
    (error/refuse :time/period-missing-bound "period without both ends" {:start start}))
  (when (nil? start)
    (error/refuse :time/period-missing-bound "period without both ends" {:end end}))
  (when (pos? (day/compare-day start end))
    (error/refuse :time/period-inverted "inverted period"
                  {:start start :end end}))
  {:start start :end end})

(defn period?
  "True when `x` is a valid Period. Never throws."
  [x]
  (and (map? x)
       (day/day? (:start x)) (day/day? (:end x))
       (not (pos? (day/compare-day (:start x) (:end x))))))

(defn days
  "How many days the period covers, ends included."
  [p]
  (inc (day/days-between (:start p) (:end p))))

(defn current-month
  "The period from the first day of `today`'s month up to `today`."
  [today]
  (->period (day/->day (:year today) (:month today) 1) today))

(defn previous-month
  "The previous full month, from its first to its last day."
  [today]
  (let [m (month/add-months {:year (:year today) :month (:month today)} -1)]
    (->period (month/first-day m)
              (month/last-day m))))
