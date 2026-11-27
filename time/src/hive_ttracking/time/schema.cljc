(ns hive-ttracking.time.schema
  "The validating malli schemas for the time core's value objects.

   Every schema here is CLOSED and validating, never shape-only:
   [:and [:map {:closed true} ...] [:fn pred?]] -- the closed map pins the shape
   (no stray keys) and the predicate pins the invariant, so a map that LOOKS
   right but is not a real Day (2026-02-31, year 1) fails. Consumers (reitit,
   the facade, calendario's wire layer) validate against these; the library
   itself does not depend on them.

   Reuse, never duplicate: the Zone and Local schemas are the ones zone.cljc
   already defined and exports, and the closed sets (:enum) are the ones
   error.cljc (Reason) and age.cljc (policies) already defined. Only Day,
   Month, TimeOfDay and Period are NEW here, built on the predicates the earlier
   steps exported (day?, month?, time-of-day?, period?).

   An Instant is NOT a value object: it is epoch milliseconds, a long. So there
   is no Instant schema; the instant CODEC (parse-instant / format-instant) is
   in instant.cljc.

   Closed sets are malli :enum, never hive-dsl defadt: hive-dsl 0.6.2 pulls
   data.json and core.async into every cljs client, too heavy to buy keyword
   dispatch for a handful of sets."
  (:require [hive-ttracking.time.age :as age]
            [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.month :as month]
            [hive-ttracking.time.period :as period]
            [hive-ttracking.time.time-of-day :as tod]
            [hive-ttracking.time.zone :as zone]))

(def Day
  "The Day value object: {:year :month :day}, a real calendar day in 1900..2199."
  [:and [:map {:closed true} [:year :int] [:month :int] [:day :int]]
   [:fn day/day?]])

(def Month
  "The Month value object: {:year :month}, a month in 1900..2199."
  [:and [:map {:closed true} [:year :int] [:month :int]]
   [:fn month/month?]])

(def TimeOfDay
  "The TimeOfDay value object: {:hour :minute :second}, each in range."
  [:and [:map {:closed true} [:hour :int] [:minute :int] [:second :int]]
   [:fn tod/time-of-day?]])

(def Period
  "The Period value object: {:start Day :end Day}, a closed, non-inverted run."
  [:and [:map {:closed true} [:start Day] [:end Day]]
   [:fn period/period?]])

;; -- re-exports (defined in the earlier steps; NOT duplicated here) ----

(def Zone
  "The ZoneId value object (an IANA id string), from zone.cljc."
  zone/Zone)

(def Local
  "The wall clock {:day Day :time TimeOfDay}, from zone.cljc."
  zone/Local)

(def Reason
  "The closed refusal-reason vocabulary, from error.cljc."
  error/Reason)

(def LeapPolicy
  "The closed leap-policy set, from age.cljc."
  age/policies)
