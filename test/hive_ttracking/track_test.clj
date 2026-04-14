(ns hive-ttracking.track-test
  "Wave-2 target: trifecta tests for tt/track + budget assertion.
   Wave-1 placeholder — one smoke test that the skeleton loads."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.core :as tt]
            [hive-ttracking.track :as track]
            [hive-ttracking.budget :as budget]))

(deftest ^:smoke skeleton-loads-test
  (testing "Wave-1 skeleton — namespaces load and core API resolves"
    (is (some? #'tt/track))
    (is (some? #'tt/track-race))
    (is (some? track/make-span))
    (is (some? budget/make-budget))))

(deftest ^:smoke budget-math-test
  (testing "Wave-1 budget value type is constructible + policies validate"
    (let [b (budget/make-budget 1000 :partial)]
      (is (budget/within? b))
      (is (<= 0 (budget/remaining-ms b) 1000))
      (is (= :partial (budget/policy b))))))

;; Wave-2 adds:
;;   - golden: tt/track returns result + emits :tt/span-started + :tt/span-ended
;;   - property: tt/track budget budget-ms >= elapsed on :hard policy
;;   - mutation: drop end-span call → property fails
