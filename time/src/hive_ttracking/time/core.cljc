(ns hive-ttracking.time.core
  "The facade: the library's public API, one entry point.

   This namespace re-exports the op table -- the GENERIC half (18 ops) that
   lives in this coordinate. Every op carries an m/=> contract (malli), so the
   shape of each argument and return is stated once, here, and a consumer
   (reitit, the calendario facade) can read it. The FUNERARIA half (br->dia,
   dia->br, competencia->br, competencia->extenso, instante->br, vencimento-em,
   vencido?, prazo, data-reajuste, fb-timestamp->dia) stays in calendario and is
   NOT here.

   Which ops are in the facade: the 18 op-table ops, nothing else. Supporting
   API (->day, day?, epoch, days-in-month, add-months, ->zone, zone?, offset-ms,
   instant->local, day-start, the instant codec parse-instant/format-instant,
   the codec pads) is reached through its own namespace, not re-exported.

   offset-ms is PUBLIC in zone.cljc but is NOT one of the 28 op-table ops: no
   op in the table needs it as a consumer entry point (period->instant-interval
   and day->instant go through day-start), so it stays OUT of the facade. The
   instant codec is the same case: parse-instant / format-instant have no op of
   their own in the table (they are used by day->instant / instant->*), so they
   stay in instant.cljc, OUT of the facade. A consumer that wants either
   imports hive-ttracking.time.zone / hive-ttracking.time.instant directly.

   The value-object schemas of schema.cljc are re-exported below as a
   convenience, so a consumer can validate with one require."
  (:require [hive-ttracking.time.age :as age]
            [hive-ttracking.time.clock :as clock]
            [hive-ttracking.time.codec :as codec]
            [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.month :as month]
            [hive-ttracking.time.period :as period]
            [hive-ttracking.time.schema :as schema]
            [hive-ttracking.time.time-of-day :as tod]
            [hive-ttracking.time.zone :as zone]
            [malli.core :as m]))

;; -- the codecs: day, month, time-of-day, period ----------------------

(def parse-day
  "\"YYYY-MM-DD\" -> Day. Refuses :time/day-is-instant, :time/day-format,
   :time/day-impossible, :time/day-out-of-range."
  codec/parse-day)

(m/=> parse-day [:=> [:cat :any] schema/Day])

(def format-day
  "Day -> \"YYYY-MM-DD\"."
  codec/format-day)

(m/=> format-day [:=> [:cat schema/Day] :string])

(def parse-month
  "\"YYYY-MM\" -> Month. Refuses :time/month-is-instant, :time/month-impossible,
   :time/month-out-of-range."
  codec/parse-month)

(m/=> parse-month [:=> [:cat :any] schema/Month])

(def format-month
  "Month -> \"YYYY-MM\"."
  codec/format-month)

(m/=> format-month [:=> [:cat schema/Month] :string])

(def parse-time
  "The legacy layouts 'H', 'H:M' and 'H:M:S' -> TimeOfDay. Refuses
   :time/time-format, :time/time-out-of-range."
  codec/parse-time)

(m/=> parse-time [:=> [:cat :any] schema/TimeOfDay])

(def format-time
  "TimeOfDay -> \"HH:MM\" when the second is 0, else \"HH:MM:SS\"."
  codec/format-time)

(m/=> format-time [:=> [:cat schema/TimeOfDay] :string])

(def format-period
  "Period -> the object of two ISO Days, with the generic keys (:start :end)."
  codec/format-period)

(m/=> format-period [:=> [:cat schema/Period]
                       [:map [:start :string] [:end :string]]])

;; -- calendar arithmetic ----------------------------------------------

(def days-between
  "Exact days from `from` to `to`; may be negative."
  day/days-between)

(m/=> days-between [:=> [:cat schema/Day schema/Day] :int])

(def add-days
  "`d` plus `n` days, revalidated (range) -> :time/day-out-of-range."
  day/add-days)

(m/=> add-days [:=> [:cat schema/Day :int] schema/Day])

;; -- completed years ---------------------------------------------------

(def completed-years
  "Completed years of birth as of day, under leap-policy (REQUIRED). Refuses
   :time/age-before-birth, :time/leap-policy-missing, :time/leap-policy-unknown."
  age/completed-years)

(m/=> completed-years [:=> [:cat schema/Day schema/Day schema/LeapPolicy] :int])

;; -- periods over days -------------------------------------------------

(def current-month
  "The period from the first day of `today`'s month up to `today`."
  period/current-month)

(m/=> current-month [:=> [:cat schema/Day] schema/Period])

(def previous-month
  "The previous full month, from its first to its last day."
  period/previous-month)

(m/=> previous-month [:=> [:cat schema/Day] schema/Period])

;; -- the zone seam -----------------------------------------------------

(def local-time->instant
  "A local Day + TimeOfDay in a zone -> epoch ms. A gap -> :time/zone-gap; an
   overlap -> the earliest offset."
  zone/local-time->instant)

(m/=> local-time->instant [:=> [:cat schema/Day schema/TimeOfDay schema/Zone] :int])

(def day->instant
  "The instant a Day begins in a zone (atStartOfDay, DST-gap safe)."
  zone/day->instant)

(m/=> day->instant [:=> [:cat schema/Day schema/Zone] :int])

(def instant->day
  "The Day of `instant` in `zone`. The zone is the argument."
  zone/instant->day)

(m/=> instant->day [:=> [:cat :int schema/Zone] schema/Day])

(def period->instant-interval
  "A Period as a HALF-OPEN interval of instants [start@00:00, (end+1)@00:00)."
  zone/period->instant-interval)

(m/=> period->instant-interval [:=> [:cat schema/Period schema/Zone]
                                  [:tuple :int :int]])

(def period-contains-instant?
  "a <= t < b."
  zone/period-contains-instant?)

(m/=> period-contains-instant? [:=> [:cat schema/Period schema/Zone :int] :boolean])

;; -- the clock ---------------------------------------------------------

(def today
  "The Day of `clock`'s current instant in `zone` (the ONE clock read + zone)."
  clock/today)

(m/=> today [:=> [:cat :any schema/Zone] schema/Day])

;; -- the value-object schemas (convenience re-export) -----------------

(def Day
  "The Day schema, from schema.cljc."
  schema/Day)

(def Month
  "The Month schema, from schema.cljc."
  schema/Month)

(def TimeOfDay
  "The TimeOfDay schema, from schema.cljc."
  schema/TimeOfDay)

(def Period
  "The Period schema, from schema.cljc."
  schema/Period)

(def Zone
  "The ZoneId schema, from schema.cljc."
  schema/Zone)

(def Local
  "The wall-clock schema, from schema.cljc."
  schema/Local)

(def Reason
  "The closed refusal-reason vocabulary, from schema.cljc."
  schema/Reason)

(def LeapPolicy
  "The closed leap-policy set, from schema.cljc."
  schema/LeapPolicy)

;; -- the refusal surface ----------------------------------------------

(def reason
  "The :time/reason of a refusal of this library, or nil."
  error/reason)

(def refusal?
  "True when `e` is a designed refusal of this library."
  error/refusal?)

(def attempt
  "Run (f & args): {:ok v}, or {:error reason :message msg} on a refusal."
  error/attempt)

(def reasons
  "The closed reason vocabulary."
  error/reasons)

(def reason?
  "True when `r` is in the closed reason vocabulary."
  error/reason?)
