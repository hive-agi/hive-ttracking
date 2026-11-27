(ns hive-ttracking.time.instant
  "The instant codec: an RFC 3339 instant <-> epoch milliseconds.

   Pure on both legs: epoch-day arithmetic, no platform at all, no zone. A
   `pad` keeps clj and cljs emitting the same bytes (no `format`).

   This is the shape of `java.time.Instant/toString`: YYYY-MM-DDTHH:MM:SSZ,
   with `.mmm` appended only when the millisecond-of-second is non-zero. A test
   asserts that equality against java.time on the RFC 3339 samples (clj leg)."
  (:require [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]))

(def ^:private ms-dia 86400000)

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

(defn- floor-div [a b] (long (Math/floor (/ (double a) (double b)))))
(defn- floor-mod [a b] (- (long a) (* b (floor-div a b))))

(defn format-instant
  "Epoch ms -> the shape of `Instant.toString`: YYYY-MM-DDTHH:MM:SSZ, with
   `.mmm` appended only when the millisecond-of-second is != 0."
  [ms]
  (let [ms (long ms)
        seg (floor-div ms 1000)
        resto (floor-mod ms 1000)
        d (day/from-epoch-day (floor-div seg 86400))
        s (floor-mod seg 86400)]
    (str (str (pad (:year d) 4) "-" (pad (:month d) 2) "-" (pad (:day d) 2))
         "T" (pad (quot s 3600) 2) ":" (pad (mod (quot s 60) 60) 2) ":" (pad (mod s 60) 2)
         (when-not (zero? resto) (str "." (pad resto 3)))
         "Z")))

(defn parse-instant
  "An RFC 3339 instant (with Z or +HH:MM) -> epoch ms. Off the format ->
   :time/instant-format. Pure: no platform, no zone."
  [s]
  (let [m (if (string? s)
            (re-matches #"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})" s)
            nil)]
    (when-not m
      (error/refuse :time/instant-format (str "instant off RFC 3339: " (pr-str s))))
    (let [[_ y mo d h mi sec frac off] m
          d (day/from-epoch-day (day/epoch-day (->int y) (->int mo) (->int d)))
          hh (->int h) mi2 (->int mi) ss (->int sec)
          frac (if frac (->int (subs (str frac "000") 0 3)) 0)
          off-ms (if (= "Z" off)
                   0
                   (let [sinal (if (= "-" (subs off 0 1)) -1 1)]
                     (* sinal (+ (* (->int (subs off 1 3)) 3600)
                                 (* (->int (subs off 4 6)) 60)) 1000)))]
      (- (+ (* (day/epoch d) ms-dia)
            (* hh 3600000) (* mi2 60000) (* ss 1000)
            (long frac))
         off-ms))))
