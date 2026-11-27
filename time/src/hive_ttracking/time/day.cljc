(ns hive-ttracking.time.day
  "The Day, and calendar arithmetic over it.

   A Day is {:year int :month int :day int}, the calendar day as a person says
   it. It carries no zone, ever: the ambiguity that comes from writing a day as
   midnight in one zone and reading it in another is the whole reason a day is
   not an instant.

   The arithmetic is by epoch-day, integer (Hinnant days_from_civil /
   civil_from_days): no floats, no js/Date, no java.time. That is why the same
   function answers the same in clj and cljs, and why a day does not depend on
   the process zone.

   The map keys are the generic English names (:year :month :day). calendario's
   Portuguese facade renames them at its own boundary."
  (:require [hive-ttracking.time.error :as error]))

(def min-year 1900)
(def max-year 2199)

(defn leap-year?
  "True when `year` is a leap year in the Gregorian calendar."
  [year]
  (and (zero? (mod year 4))
       (or (not (zero? (mod year 100))) (zero? (mod year 400)))))

(defn days-in-month
  "How many days `month` has in `year`."
  [year month]
  (case (int month)
    1 31 3 31 5 31 7 31 8 31 10 31 12 31
    4 30 6 30 9 30 11 30
    2 (if (leap-year? year) 29 28)
    0))

(defn epoch-day
  "Days since 1970-01-01 (epoch-day). Hinnant days_from_civil, integer."
  [year month day]
  (let [y (- (long year) (if (<= (long month) 2) 1 0))
        era (quot (if (>= y 0) y (- y 399)) 400)
        yoe (- y (* era 400))
        mp (mod (+ (long month) 9) 12)
        doy (+ (quot (+ (* 153 mp) 2) 5) (- (long day) 1))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (- (+ (* era 146097) doe) 719468)))

(defn from-epoch-day
  "The Day of an epoch-day. Hinnant civil_from_days, integer."
  [n]
  (let [z (+ (long n) 719468)
        era (quot (if (>= z 0) z (- z 146096)) 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y (+ yoe (* era 400))
        doy (- doe (+ (* 365 yoe) (quot yoe 4) (- (quot yoe 100))))
        mp (quot (+ (* 5 doy) 2) 153)
        d (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m (+ mp (if (< mp 10) 3 -9))]
    {:year (int (if (<= m 2) (inc y) y))
     :month (int m)
     :day (int d)}))

(defn ->day
  "Validating constructor. Checks IMPOSSIBLE first (a LocalDate/of would
   throw), then the range: a year outside 1900..2199 -> :time/day-out-of-range;
   a month outside 1..12 or a day outside 1..(days-in-month) ->
   :time/day-impossible. The order is the oracle's: 0001-01-01 ->
   :time/day-out-of-range, 2026-02-31 -> :time/day-impossible,
   13/13/2026 -> :time/day-impossible."
  [year month day]
  (let [y (long year) m (long month) d (long day)]
    (when-not (and (<= 1 m 12) (<= 1 d (long (days-in-month y m))))
      (error/refuse :time/day-impossible
                    (str "impossible day: " year "-" month "-" day)
                    {:year year :month month :day day}))
    (when-not (<= min-year y max-year)
      (error/refuse :time/day-out-of-range
                    (str "year outside " min-year ".." max-year ": " y)
                    {:year year :month month :day day}))
    {:year (int y) :month (int m) :day (int d)}))

(defn day?
  "True when `x` is structurally a Day AND a real day in range. Never throws."
  [x]
  (and (map? x)
       (integer? (:year x)) (integer? (:month x)) (integer? (:day x))
       (<= 1 (long (:month x)) 12)
       (<= 1 (long (:day x)) (long (days-in-month (long (:year x)) (long (:month x)))))
       (<= min-year (long (:year x)) max-year)))

(defn epoch
  "The epoch-day of an already-built Day."
  [d]
  (epoch-day (:year d) (:month d) (:day d)))

(defn compare-day
  "Day comparator: negative when `a` < `b`."
  [a b]
  (compare (epoch a) (epoch b)))

(defn days-between
  "Exact days from `from` to `to`: may be negative. Never ms/86400000."
  [from to]
  (- (epoch to) (epoch from)))

(defn add-days
  "`d` plus `n` days, revalidated by ->day (range)."
  [d n]
  (let [x (from-epoch-day (+ (epoch d) (long n)))]
    (->day (:year x) (:month x) (:day x))))

(defn day-of-week
  "ISO day of week: 1=Monday .. 7=Sunday. 1970-01-01 was a Thursday (=4)."
  [d]
  (inc (mod (+ (epoch d) 3) 7)))

(defn weekend?
  "Saturday or Sunday."
  [d]
  (contains? #{6 7} (day-of-week d)))
