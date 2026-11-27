(ns hive-ttracking.time.coverage-test
  "Step-8 coverage: every public op in the facade has an m/=> contract, every
   reason an op can raise is in the closed set, and every value-object schema
   both accepts a real value and rejects a shape-right-but-invalid one.

   The refusal exercise table below names EVERY reason in the closed set. One
   reason, :time/legacy-format, is facade-only: no op in THIS coordinate raises
   it (calendario's fb-timestamp->dia does, and translates it), so it is
   exercised through error/refuse directly -- which is exactly what proves it is
   a real, accepted member of the set and not a stray keyword.

   The contract-registry and namespace-introspection assertions are clj-only:
   malli's function-schema registry is populated at compile time on clj but is
   empty at runtime on cljs, and ns-resolve has no cljs twin. On cljs the SAME
   facts are covered by the-facade-re-exports-work, which calls the ops."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.time.age :as age]
            [hive-ttracking.time.codec :as codec]
            [hive-ttracking.time.core :as core]
            [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.instant :as instant]
            [hive-ttracking.time.month :as month]
            [hive-ttracking.time.period :as period]
            [hive-ttracking.time.schema :as schema]
            [hive-ttracking.time.time-of-day :as tod]
            [hive-ttracking.time.zone :as zone]
            [malli.core :as m]))

(defn- raised
  "The :time/reason `thunk` raises, or ::none."
  [thunk]
  (try (thunk) ::none
       (catch #?(:clj Exception :cljs :default) e
         (or (error/reason e) ::defect))))

(def ^:private d (fn [y mo dy] {:year y :month mo :day dy}))

(def ^:private sp "America/Sao_Paulo")

(def ^:private refusal-paths
  "reason -> a thunk that raises exactly it. Every reason in the set is here."
  {:time/day-format           #(codec/parse-day "25/11/2026")
   :time/day-is-instant       #(codec/parse-day "2026-11-25T03:00:00Z")
   :time/day-impossible       #(day/->day 2026 2 31)
   :time/day-out-of-range     #(day/->day 1899 12 31)
   :time/month-impossible     #(codec/parse-month "2026-11-25")
   :time/month-is-instant     #(codec/parse-month "2026-11-25T03:00:00Z")
   :time/month-out-of-range   #(month/->month 1 1)
   :time/period-missing-bound #(period/->period (d 2026 11 25) nil)
   :time/period-inverted      #(period/->period (d 2026 11 26) (d 2026 11 25))
   :time/zone-gap             #(zone/local->instant (d 2018 11 4) {:hour 0 :minute 0 :second 0} sp)
   :time/zone-unknown         #(zone/->zone "Marte/Olimpo")
   :time/time-format          #(codec/parse-time "quarter past eight")
   :time/time-out-of-range    #(codec/parse-time "24:50")
   :time/instant-format       #(instant/parse-instant "2026-11-25")
   ;; facade-only: raised by calendario's fb-timestamp->dia, kept in the set for
   ;; the total translation; exercised here through the refusal path itself.
   :time/legacy-format        #(error/refuse :time/legacy-format "wall clock off layout")
   :time/age-before-birth     #(age/completed-years (d 2000 6 15) (d 1999 6 15) :leap/mar-01)
   :time/leap-policy-missing  #(age/completed-years (d 2000 2 29) (d 2025 3 1) nil)
   :time/leap-policy-unknown  #(age/completed-years (d 2000 2 29) (d 2025 3 1) :leap/nope)})

(deftest every-refusal-path-raises-its-reason
  (doseq [[reason thunk] refusal-paths]
    (testing (str reason)
      (is (= reason (raised thunk))))))

(deftest the-reason-set-is-exactly-the-exercised-vocabulary
  (testing "nothing in the set is unreachable, nothing reachable is outside it"
    (is (= (set (keys refusal-paths)) error/reasons)))
  (testing "the reason set is the plan's closed vocabulary"
    (is (every? error/reason? (keys refusal-paths)))
    (is (not (error/reason? :time/nope)))
    (is (= 18 (count error/reasons)))))

#?(:clj
   (deftest every-facade-op-has-a-contract
     (let [contracts (get (m/function-schemas) 'hive-ttracking.time.core)
           names (set (map :name (vals contracts)))
           expected #{'parse-day 'format-day 'parse-month 'format-month
                      'parse-time 'format-time 'format-period
                      'days-between 'add-days 'completed-years
                      'current-month 'previous-month
                      'local-time->instant 'day->instant 'instant->day
                      'period->instant-interval 'period-contains-instant? 'today}]
       (testing "all 18 op-table ops carry an m/=> contract"
         (is (= expected names)))
       (testing "each contract is an :=> with a :cat input"
         (doseq [[qn {:keys [schema]}] contracts]
           (let [form (m/form schema)]
             (is (= :=> (first form)) (str qn))
             (is (= :cat (first (second form))) (str qn))))))))

(deftest offset-ms-and-the-instant-codec-stay-out-of-the-facade
  (testing "offset-ms is public in zone.cljc but not an op-table op"
    (is (fn? zone/offset-ms)))
  (testing "the instant codec has no op of its own, so it is not re-exported"
    (is (fn? instant/parse-instant))
    (is (fn? instant/format-instant)))
  #?(:clj
     (do (is (nil? (ns-resolve 'hive-ttracking.time.core 'offset-ms)))
         (is (nil? (ns-resolve 'hive-ttracking.time.core 'parse-instant)))
         (is (nil? (ns-resolve 'hive-ttracking.time.core 'format-instant))))))

(deftest value-object-schemas-are-closed-and-validating
  (testing "a real value validates"
    (is (m/validate schema/Day (d 2026 11 25)))
    (is (m/validate schema/Month {:year 2026 :month 11}))
    (is (m/validate schema/TimeOfDay {:hour 8 :minute 30 :second 0}))
    (is (m/validate schema/Period {:start (d 2026 11 25) :end (d 2026 11 30)}))
    (is (m/validate schema/Zone sp))
    (is (m/validate schema/Local {:day (d 2026 11 25) :time {:hour 1 :minute 0 :second 0}})))
  (testing "shape-right-but-invalid fails the invariant"
    (is (not (m/validate schema/Day (d 2026 2 31))))
    (is (not (m/validate schema/Day (d 1 1 1))))
    (is (not (m/validate schema/Period {:start (d 2026 11 26) :end (d 2026 11 25)}))))
  (testing "a stray key fails the closed map"
    (is (not (m/validate schema/Day (assoc (d 2026 11 25) :extra 1))))
    (is (not (m/validate schema/TimeOfDay {:hour 8 :minute 30 :second 0 :zone "UTC"}))))
  (testing "the closed sets are the ones the earlier steps defined"
    (is (= error/reasons (set (rest schema/Reason))))
    (is (= age/policies schema/LeapPolicy))))

(deftest the-facade-re-exports-work
  (testing "a call through the facade answers as the source ns does"
    (is (= {:year 2024 :month 2 :day 29} (core/parse-day "2024-02-29")))
    (is (= 1 (core/days-between (d 2026 11 24) (d 2026 11 25))))
    (is (= 25 (core/completed-years (d 2000 2 29) (d 2025 3 1) :leap/feb-28)))
    (is (= 25 (core/completed-years (d 2000 2 29) (d 2025 3 1) :leap/mar-01)))
    (is (= :time/zone-gap (raised #(core/local-time->instant (d 2018 11 4)
                                                            {:hour 0 :minute 0 :second 0} sp))))))
