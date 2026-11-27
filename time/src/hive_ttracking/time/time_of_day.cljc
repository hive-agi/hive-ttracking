(ns hive-ttracking.time.time-of-day
  "The TimeOfDay: a time of day with no date and no zone. \"08:30\",
   \"23:59:59\".

   Map keys are the generic English names (:hour :minute :second)."
  (:require [hive-ttracking.time.error :as error]))

(defn ->time-of-day
  "Validating constructor: hour 0..23, minute 0..59, second 0..59; each out of
   range -> :time/time-out-of-range. 24:00 is not a value: it is the midnight
   of the NEXT day."
  [h m s]
  (let [hh (long h) mm (long m) ss (long s)]
    (when-not (<= 0 hh 23)
      (error/refuse :time/time-out-of-range (str "hour outside 0..23: " hh) {:hour hh}))
    (when-not (<= 0 mm 59)
      (error/refuse :time/time-out-of-range (str "minute outside 0..59: " mm) {:minute mm}))
    (when-not (<= 0 ss 59)
      (error/refuse :time/time-out-of-range (str "second outside 0..59: " ss) {:second ss}))
    {:hour (int hh) :minute (int mm) :second (int ss)}))

(defn time-of-day?
  "True when `x` is structurally a valid TimeOfDay. Never throws."
  [x]
  (and (map? x)
       (integer? (:hour x)) (integer? (:minute x)) (integer? (:second x))
       (<= 0 (long (:hour x)) 23)
       (<= 0 (long (:minute x)) 59)
       (<= 0 (long (:second x)) 59)))
