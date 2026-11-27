(ns hive-ttracking.time.error
  "The one refusal of the time core.

   A refusal is an ex-info carrying :time/reason -- the CLOSED vocabulary
   below -- and :autonomous/error with the SAME value, so the autonomous
   runtime turns the throw into the right kind of refusal
   (autonomous.domain.answer/from-exception).

   A reason outside the vocabulary is not a refusal: it is a caller defect,
   and becomes an ordinary ex-info with no :time/reason. A designed refusal is
   always recognisable; a defect never passes for one.

   Closed sets are malli :enum, never hive-dsl defadt: hive-dsl 0.6.2 pulls
   data.json and core.async into every cljs client, too heavy to buy keyword
   dispatch for a handful of sets."
  (:require [malli.core :as m]))

(def reasons
  "The CLOSED vocabulary of refusal reasons.

   Generic (day, month, period, zone, age, codecs) plus the two the ISO codec
   needs."
  #{:time/day-format
    :time/day-impossible
    :time/day-out-of-range
    :time/day-is-instant
    :time/month-impossible
    :time/month-out-of-range
    :time/month-is-instant
    :time/period-missing-bound
    :time/period-inverted
    :time/zone-gap
    :time/zone-unknown
    :time/time-format
    :time/time-out-of-range
    :time/instant-format
    :time/legacy-format
    :time/age-before-birth
    :time/leap-policy-missing
    :time/leap-policy-unknown})

(def Reason
  "The reason set as a malli :enum, for contracts and consumer validation."
  (into [:enum] reasons))

(defn reason?
  "True when `r` is in the closed reason vocabulary."
  [r]
  (contains? reasons r))

(defn refuse
  "A refusal with `reason` (from the vocabulary) and `msg`. `data` joins the
   ex-data. A reason outside the vocabulary is a caller defect: an ex-info
   \"unknown time reason\", with no :time/reason."
  ([reason msg] (refuse reason msg nil))
  ([reason msg data]
   (if (contains? reasons reason)
     (throw (ex-info msg (merge data {:time/reason reason
                                      :autonomous/error reason})))
     (throw (ex-info "unknown time reason"
                     {:time/unknown-reason reason
                      :message msg
                      :data data})))))

(defn reason
  "The reason of a refusal, or nil when `e` is not a refusal of this library."
  [e]
  (:time/reason (ex-data e)))

(defn refusal?
  "True when `e` is a designed refusal of this library."
  [e]
  (contains? reasons (reason e)))

(defn attempt
  "Run (f & args): {:ok v} on the value, {:error reason :message msg} on a
   refusal of this library. Any other exception (a defect) is rethrown."
  [f & args]
  (try
    {:ok (apply f args)}
    (catch #?(:clj Throwable :cljs :default) e
      (if (refusal? e)
        {:error (reason e) :message (ex-message e)}
        (throw e)))))

(m/=> reason? [:=> [:cat :any] :boolean])
(m/=> reason [:=> [:cat :any] [:maybe Reason]])
(m/=> refusal? [:=> [:cat :any] :boolean])
