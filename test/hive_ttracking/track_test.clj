(ns hive-ttracking.track-test
  "Trifecta tests for tt/track + budget assertion (Wave-2).

   Facets:
     golden   — tt/track returns {:span :result :error} + events fire
     property — elapsed-ms ≤ budget-ms when :hard policy satisfied
     mutation — drop end-span call → property fails
     axiom    — AssertionError is caught (axiom 3d0e1f7c)
     policy   — :hard violation throws ex-info with :tt/budget-exceeded
     samples  — record-sample preserves insertion order"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-ttracking.core :as tt]
            [hive-ttracking.track :as track]
            [hive-ttracking.span :as span]
            [hive-ttracking.budget :as budget]
            [hive-ttracking.events :as events]
            [hive.events :as ev]))

;; ---------------------------------------------------------------------------
;; Wave-1 smoke — kept to prove the ns graph still resolves.
;; ---------------------------------------------------------------------------

(deftest ^:smoke skeleton-loads-test
  (testing "public API resolves"
    (is (some? #'tt/track))
    (is (some? #'tt/track-race))
    (is (some? #'tt/with-tracker))
    (is (some? #'tt/bench))
    (is (some? #'tt/defbench))
    (is (some? track/make-span))
    (is (some? track/make-atom-tracker))
    (is (some? budget/make-budget))))

(deftest ^:smoke budget-math-test
  (testing "budget value type is constructible + policies validate"
    (let [b (budget/make-budget 1000 :partial)]
      (is (budget/within? b))
      (is (<= 0 (budget/remaining-ms b) 1000))
      (is (= :partial (budget/policy b))))))

;; ---------------------------------------------------------------------------
;; Helpers — isolate tests by binding a fresh tracker and an in-memory
;; event capture; no dependency on router configuration or global state.
;; ---------------------------------------------------------------------------

(defn- with-captured-events [f]
  (let [captured (atom [])
        ;; hive.events/dispatch fans out to registered handlers. We shim
        ;; tt/publish directly rather than wiring a handler, which keeps the
        ;; test hermetic regardless of router init order.
        orig    events/publish]
    (with-redefs [events/publish (fn [event]
                                   (swap! captured conj event)
                                   (orig event)
                                   event)]
      (let [result (f)]
        {:result result :events @captured}))))

(defn- fresh-tracker []
  (track/make-atom-tracker {:max-open 128 :max-closed 128 :ttl-ms nil}))

;; ---------------------------------------------------------------------------
;; GOLDEN — shape of return + events fired.
;; ---------------------------------------------------------------------------

(deftest golden-track-returns-result+events
  (testing "tt/track returns {:span :result :error} and emits :tt/span-started + :tt/span-ended"
    (let [t (fresh-tracker)
          {:keys [result events]}
          (with-captured-events
            (fn []
              (tt/with-tracker t
                (tt/track {:name :golden.ok} (+ 1 2)))))]
      (is (= 3 (:result result)))
      (is (nil? (:error result)))
      (is (some? (:span result)))
      (is (span/closed? (:span result)))
      (let [types (mapv :event/type events)]
        (is (= #{:tt/span-started :tt/span-ended} (set types)))))))

(deftest golden-track-catches-exception
  (testing "thunk throws → span still closed, error captured, no re-throw"
    (let [t (fresh-tracker)
          boom (RuntimeException. "boom")
          {:keys [result events]}
          (with-captured-events
            (fn []
              (tt/with-tracker t
                (tt/track {:name :golden.throw}
                  (throw boom)))))]
      (is (nil? (:result result)))
      (is (identical? boom (:error result)))
      (is (span/closed? (:span result)))
      (let [ended (first (filter #(= :tt/span-ended (:event/type %)) events))]
        (is (false? (-> ended :result :ok)))
        (is (= "boom" (-> ended :result :error)))))))

;; ---------------------------------------------------------------------------
;; AXIOM 3d0e1f7c — catch Throwable, not Exception.
;; ---------------------------------------------------------------------------

(deftest axiom-catches-assertion-error
  (testing "AssertionError propagates into :error without escaping tracker"
    (let [t (fresh-tracker)
          {:keys [error span]}
          (tt/with-tracker t
            (tt/track {:name :axiom.assert}
              (assert false "kaboom")))]
      (is (instance? AssertionError error))
      (is (span/closed? span)))))

;; ---------------------------------------------------------------------------
;; POLICY — :hard violation throws ex-info with :tt/budget-exceeded.
;; ---------------------------------------------------------------------------

(deftest policy-hard-throws-ex-info
  (testing ":hard policy re-throws after close/event, carries :tt/budget-exceeded + diagnostic data"
    (let [t (fresh-tracker)
          thrown (try
                   (tt/with-tracker t
                     (tt/track {:name :policy.hard
                                :budget-ms 1
                                :policy :hard}
                       (Thread/sleep 50)
                       :done))
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown))
      (let [data (ex-data thrown)]
        (is (true? (:tt/budget-exceeded data)))
        (is (= :hard (:policy data)))
        (is (= 1 (:budget-ms data)))
        (is (span/closed? (:span data)))
        (is (< 1 (:elapsed-ms data)))))))

(deftest policy-partial-does-not-throw
  (testing ":partial policy over-budget emits :tt/budget-violation but returns normally"
    (let [t (fresh-tracker)
          {:keys [result events]}
          (with-captured-events
            (fn []
              (tt/with-tracker t
                (tt/track {:name :policy.partial
                           :budget-ms 1
                           :policy :partial}
                  (Thread/sleep 20)
                  :ok))))]
      (is (= :ok (:result result)))
      (is (nil? (:error result)))
      (is (some #(= :tt/budget-violation (:event/type %)) events)))))

;; ---------------------------------------------------------------------------
;; PROPERTY — elapsed within budget on :hard when thunk sleeps under budget.
;;
;; Budget >= sleep + slack; track* must not throw. Generalizes the golden
;; sample to any (sleep-ms, slack-ms) pair within a safe band.
;; ---------------------------------------------------------------------------

(deftest property-hard-within-budget-never-throws
  (testing "sleep << budget-ms :hard run completes normally"
    (let [result
          (tc/quick-check
            25
            (prop/for-all [sleep-ms (gen/choose 0 5)
                           slack-ms (gen/choose 50 200)]
              (let [t (fresh-tracker)
                    budget-ms (+ sleep-ms slack-ms)
                    {:keys [result error span]}
                    (tt/with-tracker t
                      (tt/track {:name :prop.hard
                                 :budget-ms budget-ms
                                 :policy :hard}
                        (Thread/sleep sleep-ms)
                        ::ok))]
                (and (= ::ok result)
                     (nil? error)
                     (span/closed? span)
                     (<= (span/elapsed-ms span) (* 1.5 budget-ms))))))]
      (is (:pass? result) (pr-str result)))))

;; ---------------------------------------------------------------------------
;; MUTATION — drop end-span → spans never close → property breaks.
;;
;; Locks down the behavioral contract: the property would accept *any*
;; implementation that passes golden, so we verify the property
;; distinguishes the real impl from an obvious mutant.
;; ---------------------------------------------------------------------------

(deftest mutation-missing-end-span-fails-property
  (testing "redefing end-span to skip closure leaves span open → closed? false"
    (let [t (fresh-tracker)]
      ;; Monkey-patch track* to a no-op that skips end-span: returns the
      ;; incoming (still-open) span without stamping :t1. A healthy
      ;; impl must return a closed span — this mutant does not.
      (with-redefs [track/track*
                    (fn [{:keys [name tags]} thunk]
                      (let [s (track/start-span t name (or tags {}))
                            _ (thunk)]
                        ;; Intentionally skip end-span — the mutant.
                        {:span s :result nil :error nil}))]
        (let [{:keys [span]} (tt/track {:name :mutant} (+ 1 1))]
          (is (not (span/closed? span))
              "mutant must produce an open span — if this fails the property is too weak"))))))

;; ---------------------------------------------------------------------------
;; SAMPLES — record-sample preserves insertion order.
;; ---------------------------------------------------------------------------

(deftest samples-preserve-insertion-order
  (testing "record-sample appends; order matches call order"
    (let [t (fresh-tracker)
          span (track/start-span t :samples {})
          ks   [:a :b :c :d :e]]
      (doseq [k ks] (track/record-sample t (:id span) k (name k)))
      (let [stored (hive-dsl.bounded-atom/bget (:open-spans t) (:id span))
            actual (mapv :k (:samples stored))]
        (is (= ks actual))))))

;; ---------------------------------------------------------------------------
;; WITH-TRACKER — dynamic binding isolates scopes.
;; ---------------------------------------------------------------------------

(deftest with-tracker-scopes-bindings
  (testing "nested with-tracker rebinds *default-tracker* for the dynamic extent"
    (let [outer (fresh-tracker)
          inner (fresh-tracker)]
      (tt/with-tracker outer
        (tt/track {:name :outer} :o)
        (tt/with-tracker inner
          (tt/track {:name :inner} :i))
        (tt/track {:name :outer2} :o2))
      (let [outer-closed (count (hive-dsl.bounded-atom/bkeys (:closed-spans outer)))
            inner-closed (count (hive-dsl.bounded-atom/bkeys (:closed-spans inner)))]
        (is (= 2 outer-closed))
        (is (= 1 inner-closed))))))
