(ns hive-ttracking.clock
  "Clock facade for domain code.

   Domain layers MUST NOT call `java.time.*` or `System/currentTimeMillis`
   directly — route through this ns so tests can pin time without
   `with-redefs` on JVM statics.

   Substitution model: `java.time.Clock` rebinding via `*clock*`.
   - Default: `Clock/systemDefaultZone`
   - Tests: bind to `Clock/fixed` for deterministic instants
   - All `now-*` fns honor the dynamic binding

   Provided primitives mirror the subset needed by hive-knowledge,
   hive-mcp, and other consumers. Add new fns here, never inline
   `java.time.*` in calling code."
  (:require [hive-dsl.result :as r])
  (:import (java.time Clock Instant LocalDateTime LocalDate ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit Temporal)))
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: AGPL-3.0-or-later

;; =============================================================================
;; Clock binding
;; =============================================================================

(def ^:dynamic ^Clock *clock*
  "Clock used by all `now-*` fns. Rebind to `Clock/fixed` in tests."
  (Clock/systemDefaultZone))

(defmacro with-clock
  "Run body with `*clock*` bound to the given java.time.Clock."
  [clock & body]
  `(binding [*clock* ~clock]
     ~@body))

(defmacro with-fixed
  "Run body with `*clock*` pinned at the given instant in `zone`
   (default: system default zone)."
  ([instant & body]
   `(with-fixed ~instant (ZoneId/systemDefault) ~@body))
  ([instant zone & body]
   `(binding [*clock* (Clock/fixed ~instant ~zone)]
      ~@body)))

;; =============================================================================
;; Now
;; =============================================================================

(defn ^Instant now-instant []
  (Instant/now *clock*))

(defn now-millis ^long []
  (.toEpochMilli (now-instant)))

(defn ^LocalDateTime now-local-date-time []
  (LocalDateTime/now *clock*))

(defn ^LocalDate now-local-date []
  (LocalDate/now *clock*))

(defn ^ZonedDateTime now-zoned-date-time []
  (ZonedDateTime/now *clock*))

;; =============================================================================
;; Parse
;; =============================================================================

(defn ^Instant parse-instant
  "Parse an ISO-8601 instant string."
  [s]
  (Instant/parse (str s)))

(defn ^ZonedDateTime parse-zoned-date-time
  "Parse an ISO-8601 zoned datetime string."
  [s]
  (ZonedDateTime/parse (str s)))

(defn ^LocalDateTime parse-local-date-time
  "Parse an ISO-8601 local datetime string."
  [s]
  (LocalDateTime/parse (str s)))

;; =============================================================================
;; Format
;; =============================================================================

(defn format-temporal
  "Format a java.time.temporal.Temporal with the given pattern."
  [^Temporal t pattern]
  (.format (DateTimeFormatter/ofPattern (str pattern)) t))

(defn today-yyyy-mm-dd
  "Convenience: today's date as `yyyy-MM-dd` honoring `*clock*`."
  []
  (format-temporal (now-local-date-time) "yyyy-MM-dd"))

;; =============================================================================
;; Differences
;; =============================================================================

(def ^:private chrono-units
  {:nanos    ChronoUnit/NANOS
   :micros   ChronoUnit/MICROS
   :millis   ChronoUnit/MILLIS
   :seconds  ChronoUnit/SECONDS
   :minutes  ChronoUnit/MINUTES
   :hours    ChronoUnit/HOURS
   :days     ChronoUnit/DAYS
   :weeks    ChronoUnit/WEEKS
   :months   ChronoUnit/MONTHS
   :years    ChronoUnit/YEARS})

(defn between
  "Whole units of `unit-kw` between two Temporals.
   `unit-kw` ∈ #{:nanos :micros :millis :seconds :minutes :hours :days
                 :weeks :months :years}."
  ^long [unit-kw ^Temporal a ^Temporal b]
  (if-let [^ChronoUnit u (get chrono-units unit-kw)]
    (.between u a b)
    (throw (ex-info "Unknown chrono unit" {:unit unit-kw
                                           :allowed (set (keys chrono-units))}))))

(defn between-minutes ^long [a b] (between :minutes a b))
(defn between-hours   ^long [a b] (between :hours   a b))
(defn between-days    ^long [a b] (between :days    a b))
(defn between-millis  ^long [a b] (between :millis  a b))
