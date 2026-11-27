(ns hive-ttracking.time.codec
  "The ISO-8601 text codecs: between the ISO form on the wire and the value
   objects.

   Pure. The regexes are the oracle's (tools/golden_check.clj): the golden
   table is the truth, and a parser that disagrees with it is the parser that
   is wrong.

   There is no `format` here: a cljc `pad` keeps clj and cljs emitting the
   same bytes.

   Only the GENERIC ISO half lives here. The pt-BR half (br->dia, dia->br,
   competencia->br, competencia->extenso) and the wire parse (iso->periodo)
   stay in calendario's Portuguese facade.

   Map keys are the generic English names (:year :month :day, :start :end)."
  (:require [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.month :as month]
            [hive-ttracking.time.time-of-day :as tod]))

(defn- ->int [s]
  #?(:clj (Long/parseLong (str s))
     :cljs (js/parseInt (str s) 10)))

(defn- pad
  "`n` with `w` digits, zero-padded on the left. No `format`, so it serves clj
   and cljs alike."
  [n w]
  (let [s (str n)]
    (if (>= (count s) w)
      s
      (str (apply str (repeat (- w (count s)) "0")) s))))

;; -- Day ---------------------------------------------------------------

(defn parse-day
  "\"YYYY-MM-DD\" -> Day. A WELL-FORMED instant is the WRONG type, not a
   format error: the refusal is :time/day-is-instant, and it is checked FIRST
   (the oracle's order). Not text, or not ISO -> :time/day-format; impossible
   day -> :time/day-impossible; year outside 1900..2199 ->
   :time/day-out-of-range."
  [s]
  (when (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}T.*" s))
    (error/refuse :time/day-is-instant (str "instant, not a day: " (pr-str s))))
  (when-not (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}" s))
    (error/refuse :time/day-format (str "not ISO YYYY-MM-DD: " (pr-str s))))
  (day/->day (->int (subs s 0 4)) (->int (subs s 5 7)) (->int (subs s 8 10))))

(defn format-day
  "Day -> \"YYYY-MM-DD\", each field zero-padded."
  [d]
  (str (pad (:year d) 4) "-" (pad (:month d) 2) "-" (pad (:day d) 2)))

;; -- Month -------------------------------------------------------------

(defn parse-month
  "\"YYYY-MM\" -> Month. An instant -> :time/month-is-instant; not YYYY-MM ->
   :time/month-impossible (sic, the oracle's choice)."
  [s]
  (when (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}T.*" s))
    (error/refuse :time/month-is-instant (str "instant, not a month: " (pr-str s))))
  (when-not (and (string? s) (re-matches #"\d{4}-\d{2}" s))
    (error/refuse :time/month-impossible (str "not YYYY-MM: " (pr-str s))))
  (month/->month (->int (subs s 0 4)) (->int (subs s 5 7))))

(defn format-month
  "Month -> \"YYYY-MM\", each field zero-padded."
  [m]
  (str (pad (:year m) 4) "-" (pad (:month m) 2)))

;; -- TimeOfDay ---------------------------------------------------------

(defn parse-time
  "The legacy layouts 'H', 'H:M' and 'H:M:S' -> TimeOfDay. Not text, or off
   the layout -> :time/time-format; a field out of range ->
   :time/time-out-of-range."
  [s]
  (when-not (string? s)
    (error/refuse :time/time-format "time is not text"))
  (let [m (re-matches #"(\d{1,2})(?::(\d{1,2}))?(?::(\d{1,2}))?" s)]
    (when-not m
      (error/refuse :time/time-format (str "unknown legacy layout: " (pr-str s))))
    (let [[_ h mi sec] m]
      (tod/->time-of-day (->int h) (if mi (->int mi) 0) (if sec (->int sec) 0)))))

(defn format-time
  "The codec RULE: \"HH:MM\" when the second is 0, else \"HH:MM:SS\". Each
   field zero-padded."
  [h]
  (let [base (str (pad (:hour h) 2) ":" (pad (:minute h) 2))]
    (if (zero? (long (:second h)))
      base
      (str base ":" (pad (:second h) 2)))))

;; -- Period ------------------------------------------------------------

(defn format-period
  "A Period as an object of two ISO Days, with the generic English keyword
   keys (:start :end) -- the facade renames them to the wire's common.v1
   shape."
  [p]
  {:start (format-day (:start p)) :end (format-day (:end p))})
