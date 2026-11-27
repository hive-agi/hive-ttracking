(ns hive-ttracking.time.month
  "The Month: the month a charge refers to, the way a person says it (\"the
   July 2026 instalment\"). A MONTH, never an instant.

   A month written as 01T00:00Z and read at UTC-3 shows the PREVIOUS month --
   which is the whole reason a month is not an instant.

   Map keys are the generic English names (:year :month)."
  (:require [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]))

(defn ->month
  "Validating constructor. Impossible first (month outside 1..12), then the
   year range: outside 1900..2199 -> :time/month-out-of-range."
  [year month]
  (let [y (long year) m (long month)]
    (when-not (<= 1 m 12)
      (error/refuse :time/month-impossible
                    (str "impossible month: " year "-" month)
                    {:year year :month month}))
    (when-not (<= day/min-year y day/max-year)
      (error/refuse :time/month-out-of-range
                    (str "year outside " day/min-year ".." day/max-year ": " y)
                    {:year year :month month}))
    {:year (int y) :month (int m)}))

(defn month?
  "True when `x` is structurally a valid Month. Never throws."
  [x]
  (and (map? x)
       (integer? (:year x)) (integer? (:month x))
       (<= 1 (long (:month x)) 12)
       (<= day/min-year (long (:year x)) day/max-year)))

(defn add-months
  "TOTAL month arithmetic: m0 = year*12 + month-1 + n."
  [m n]
  (let [m0 (+ (* (long (:year m)) 12) (dec (long (:month m))) (long n))]
    (->month (quot m0 12) (inc (mod m0 12)))))

(defn month-of-day
  "The Month of a Day."
  [d]
  {:year (:year d) :month (:month d)})

(defn first-day
  "The first Day of the month."
  [m]
  (day/->day (:year m) (:month m) 1))

(defn last-day
  "The last Day of the month."
  [m]
  (day/->day (:year m) (:month m) (day/days-in-month (:year m) (:month m))))
