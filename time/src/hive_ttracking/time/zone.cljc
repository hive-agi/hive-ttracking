(ns hive-ttracking.time.zone
  "The zone seam: the ONE place the library touches the platform.

   Every other namespace is pure. Here lives the bridge between a Day and an
   instant, and it is a FUNCTION OF A LIBRARY, never a field of a Day.

   The zone is ALWAYS an explicit argument: ttrack has NO default zone. A
   library that silently reaches for America/Sao_Paulo is exactly the coupling
   this coordinate was carved out to break.

   The two rules the dst-* rows fix, on BOTH legs:
     - a gap (DST start) is an EXPLICIT error (:time/zone-gap), never a silent
       shift of the wall clock;
     - in an overlap (DST end) the EARLIEST offset wins, deterministically
       (java.time's default; the cljs two-candidate search's min).

   clj leg: java.time ZoneId / LocalDateTime with getValidOffsets.
   cljs leg: Intl.DateTimeFormat with formatToParts, hourCycle h23, cached per
   zone in a js/Map, plus a two-candidate offset search. NO tz library -- the
   zone rules come from the engine's ICU tzdata.

   Map keys are the generic English names (:year :month :day, :hour :minute
   :second, :start :end)."
  (:require [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.time-of-day :as tod]))

(def ^:private ms-day 86400000)

;; -- the ZoneId value object -------------------------------------------

#?(:clj
   (defn- zone-id
     "The java.time.ZoneId for `zone`, or :time/zone-unknown."
     [zone]
     (try (java.time.ZoneId/of zone)
          (catch java.time.DateTimeException _
            (error/refuse :time/zone-unknown
                          (str "unknown zone: " (pr-str zone)))))))

#?(:cljs
   (defn- make-formatter
     "An Intl.DateTimeFormat for `zone`. formatToParts yields the fields already
      split, so no string layout is parsed. An unknown zone throws."
     [zone]
     (js/Intl.DateTimeFormat.
      "en-US"
      #js {:timeZone zone :hourCycle "h23"
           :year "numeric" :month "2-digit" :day "2-digit"
           :hour "2-digit" :minute "2-digit" :second "2-digit"})))

#?(:cljs (defonce ^:private formatters (js/Map.)))

#?(:cljs
   (defn- formatter
     "The Intl formatter for `zone`, cached per zone in a js/Map, or
      :time/zone-unknown."
     [zone]
     (or (.get formatters zone)
         (let [f (try (make-formatter zone)
                      (catch :default _
                        (error/refuse :time/zone-unknown
                                      (str "unknown zone: " (pr-str zone)))))]
           (.set formatters zone f)
           f))))

(defn ->zone
  "Validating constructor: an IANA zone id -> the ZoneId. A non-string, an empty
   string, or an id the platform does not know -> :time/zone-unknown.

   The ZoneId IS the id string; the platform handle is derived on each call and
   never stored in a value, so a value is comparable and serialisable."
  [zone]
  (when-not (and (string? zone) (pos? (count zone)))
    (error/refuse :time/zone-unknown (str "unknown zone: " (pr-str zone))))
  #?(:clj (do (zone-id zone) zone)
     :cljs (do (formatter zone) zone)))

(defn zone?
  "True when `zone` is a usable zone id on this platform. Never throws."
  [zone]
  (and (string? zone)
       (pos? (count zone))
       #?(:clj (try (java.time.ZoneId/of zone) true
                    (catch java.time.DateTimeException _ false))
          :cljs (try (make-formatter zone) true
                     (catch :default _ false)))))

;; -- instant -> the wall clock -----------------------------------------

(defn- ->int [s]
  #?(:clj (Long/parseLong (str s))
     :cljs (js/parseInt (str s) 10)))

(defn- wall->utc-ms
  "The wall clock (a Day + a TimeOfDay) read as if it were UTC, in ms."
  [d h]
  (+ (* (day/epoch d) ms-day)
     (* (:hour h) 3600000) (* (:minute h) 60000) (* (:second h) 1000)))

(defn instant->local
  "The wall clock of `instant` in `zone`: {:day Day :time TimeOfDay}."
  [instant zone]
  #?(:clj
     (let [z (zone-id zone)
           ldt (.atZone (java.time.Instant/ofEpochMilli (long instant)) z)]
       {:day (day/->day (.getYear ldt) (.getMonthValue ldt) (.getDayOfMonth ldt))
        :time (tod/->time-of-day (.getHour ldt) (.getMinute ldt) (.getSecond ldt))})
     :cljs
     (let [f (formatter zone)
           parts (->> (.formatToParts f (js/Date. (long instant)))
                      (map (fn [p] [(.-type p) (.-value p)]))
                      (into {}))]
       {:day (day/->day (->int (get parts "year"))
                        (->int (get parts "month"))
                        (->int (get parts "day")))
        :time (tod/->time-of-day (->int (get parts "hour"))
                                 (->int (get parts "minute"))
                                 (->int (get parts "second")))})))

(defn offset-ms
  "The zone offset at `instant`, in ms: wall-clock-as-UTC-ms - instant."
  [instant zone]
  #?(:clj
     (let [z (zone-id zone)
           i (java.time.Instant/ofEpochMilli (long instant))]
       (* 1000 (long (.getTotalSeconds (.getOffset (.getRules z) i)))))
     :cljs
     (let [p (instant->local instant zone)]
       (- (wall->utc-ms (:day p) (:time p)) (long instant)))))

;; -- local -> instant, the explicit gap/overlap policy ----------------

(defn- same-wall?
  [d h zone instant]
  (let [p (instant->local instant zone)]
    (and (= (:day p) d) (= (:time p) h))))

(defn local->instant
  "The instant of a wall clock (a Day + a TimeOfDay) in `zone`.

   The gap/overlap policy is EXPLICIT, never a silent shift:
     - a gap (the wall clock never happened, DST start) -> :time/zone-gap;
     - an overlap (the wall clock happened twice, DST end) -> the EARLIEST
       offset, deterministically."
  [d h zone]
  #?(:clj
     (let [z (zone-id zone)
           ldt (java.time.LocalDateTime/of (long (:year d)) (long (:month d)) (long (:day d))
                                           (long (:hour h)) (long (:minute h)) (long (:second h)))
           offs (.getValidOffsets (.getRules z) ldt)]
       (if (empty? offs)
         (error/refuse :time/zone-gap
                       (str "wall clock does not exist (DST gap): "
                            (:year d) "-" (:month d) "-" (:day d) " "
                            (:hour h) ":" (:minute h) ":" (:second h)))
         (.toEpochMilli (.toInstant (.atZone ldt z)))))
     :cljs
     (let [w (wall->utc-ms d h)
           cands (distinct [(- w (offset-ms (- w ms-day) zone))
                            (- w (offset-ms (+ w ms-day) zone))])
           valid (filter #(same-wall? d h zone %) cands)]
       (if (empty? valid)
         (error/refuse :time/zone-gap
                       (str "wall clock does not exist (DST gap): "
                            (:year d) "-" (:month d) "-" (:day d) " "
                            (:hour h) ":" (:minute h) ":" (:second h)))
         (apply min valid)))))

(defn local-time->instant
  "The op-table name for `local->instant`: a local Day + TimeOfDay in a zone ->
   epoch ms; a gap -> :time/zone-gap, an overlap -> the earliest offset."
  [d h zone]
  (local->instant d h zone))

;; -- day-start (atStartOfDay), DST-gap safe ----------------------------

(defn day-start
  "The first instant of `day` in `zone` (= atStartOfDay).

   On a DST-gap day 00:00 never happened: the instant is 00:00 read in the
   offset BEFORE the transition, so America/Sao_Paulo 2018-11-04 -> 03:00Z. It
   never refuses -- a day always begins somewhere."
  [d zone]
  #?(:clj
     (let [z (zone-id zone)
           ld (java.time.LocalDate/of (long (:year d)) (long (:month d)) (long (:day d)))]
       (.toEpochMilli (.toInstant (.atStartOfDay ld z))))
     :cljs
     (let [h {:hour 0 :minute 0 :second 0}
           w (wall->utc-ms d h)]
       (try
         (local->instant d h zone)
         (catch :default e
           (if (= :time/zone-gap (error/reason e))
             (- w (offset-ms (- w ms-day) zone))
             (throw e)))))))

(defn day->instant
  "The op-table name for `day-start`: the instant a Day begins in a zone."
  [d zone]
  (day-start d zone))

;; -- period -> a half-open interval of instants ------------------------

(defn period->instant-interval
  "A Period as a HALF-OPEN interval of instants [start@00:00, (end+1)@00:00) in
   `zone`. Never +86400 s, never 23:59:59."
  [p zone]
  [(day-start (:start p) zone)
   (day-start (day/add-days (:end p) 1) zone)])

(defn period-contains-instant?
  "a <= t < b."
  [p zone t]
  (let [[a b] (period->instant-interval p zone)
        t (long t)]
    (and (<= (long a) t) (< t (long b)))))

;; -- instant -> day ----------------------------------------------------

(defn instant->day
  "The Day of `instant` in `zone`. The ZONE is the argument, never a field of
   the value -- that is the whole correction."
  [instant zone]
  (:day (instant->local instant zone)))

;; -- schemas for the new value objects ---------------------------------

(def Zone
  "The ZoneId value object as a malli schema. A zone id is a STRING, so there is
   no closed map to wrap: the schema is the base type wrapped in the invariant
   (zone?), never shape-only. Step-8's schema.cljc re-exports this."
  [:and :string [:fn zone?]])

(defn local?
  "True when `x` is a wall clock: a valid Day and a valid TimeOfDay. Never
   throws."
  [x]
  (and (map? x) (day/day? (:day x)) (tod/time-of-day? (:time x))))

(def Local
  "The wall-clock value object (instant->local) as a CLOSED malli map wrapped in
   its invariant: {:day Day :time TimeOfDay}."
  [:and [:map {:closed true} [:day :map] [:time :map]] [:fn local?]])
