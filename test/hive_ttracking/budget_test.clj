(ns hive-ttracking.budget-test
  "Pure tests for BC-2 Budgets. Wave-1 covers constructor + math."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.budget :as budget]))

(deftest make-budget-validates-policy-test
  (testing "invalid policy rejected"
    (is (thrown? AssertionError (budget/make-budget 1000 :invalid))))
  (testing "non-positive total-ms rejected"
    (is (thrown? AssertionError (budget/make-budget 0 :hard)))))

(deftest child-budget-clamps-to-parent-remaining-test
  (testing "nested span budget = min(declared, parent-remaining)"
    (let [parent (budget/make-budget 5000 :partial)
          child  (budget/child-budget parent 10000 :hard)]
      (is (<= (budget/remaining-ms child)
              (budget/remaining-ms parent)))
      (is (= :hard (budget/policy child))))))
