(ns hive-ttracking.time.age-test
  "Unit tests of completed-years, both leap policies.

   Moved from calendario's negocio tests and rewritten with the English API.
   The 7 idade-* golden rows bind :leap/mar-01 (Codigo Civil art. 132
   paragraph 3); here the unit tests pin BOTH policies, and the leap-day cases
   are the point: a 29 February birth completes on a different day under
   :leap/feb-28 and :leap/mar-01, and the test asserts exactly that."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.time.age :as age]
            [hive-ttracking.time.error :as error]))

(defn- m [f & args]
  (try (apply f args)
       (catch #?(:clj Exception :cljs :default) e (error/reason e))))

(defn- d [y mo dy] {:year y :month mo :day dy})

(deftest ordinary-completed-years
  (is (= 25 (age/completed-years (d 2000 6 15) (d 2025 6 15) :leap/mar-01)))
  (is (= 25 (age/completed-years (d 2000 6 15) (d 2025 6 16) :leap/mar-01)))
  (testing "the day before the anniversary is one year short"
    (is (= 24 (age/completed-years (d 2000 6 15) (d 2025 6 14) :leap/mar-01))))
  (is (= 0 (age/completed-years (d 2000 6 15) (d 2000 6 15) :leap/mar-01)))
  (testing "1 January and 1 March boundaries"
    (is (= 25 (age/completed-years (d 2000 1 1) (d 2025 1 1) :leap/mar-01)))
    (is (= 25 (age/completed-years (d 2000 1 1) (d 2025 3 1) :leap/mar-01)))
    (is (= 24 (age/completed-years (d 2000 1 1) (d 2024 12 31) :leap/mar-01))))
  (testing "both policies agree when the birth is not 29 February"
    (is (= (age/completed-years (d 1990 4 10) (d 2025 4 9) :leap/feb-28)
           (age/completed-years (d 1990 4 10) (d 2025 4 9) :leap/mar-01)))))

(deftest leap-day-birth-differs-by-policy
  (testing "birth 2000-02-29, read 2025-02-28: feb-28 observes it on the 28th, mar-01 has not reached the 29th"
    (is (= 25 (age/completed-years (d 2000 2 29) (d 2025 2 28) :leap/feb-28)))
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2025 2 28) :leap/mar-01))))
  (testing "birth 2000-02-29, read 2025-03-01: both policies have completed the year"
    (is (= 25 (age/completed-years (d 2000 2 29) (d 2025 3 1) :leap/feb-28)))
    (is (= 25 (age/completed-years (d 2000 2 29) (d 2025 3 1) :leap/mar-01))))
  (testing "birth 2000-02-29, read 2024-02-29 (a leap year): the 29th has arrived"
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2024 2 29) :leap/feb-28)))
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2024 2 29) :leap/mar-01))))
  (testing "the day before completion"
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2025 2 27) :leap/mar-01)))))

(deftest leap-year-birth-completes-on-the-29th-under-both-policies
  (testing "birth 2000-02-29 read in a LEAP year: the 29th exists, so BOTH policies wait for it"
    (is (= 23 (age/completed-years (d 2000 2 29) (d 2024 2 28) :leap/feb-28))
        "2024-02-28: the birthday is the 29th, so the year is not complete")
    (is (= 23 (age/completed-years (d 2000 2 29) (d 2024 2 28) :leap/mar-01))
        "2024-02-28: same under :leap/mar-01 -- the policies agree in a leap year")
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2024 2 29) :leap/feb-28)))
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2024 2 29) :leap/mar-01))))
  (testing "birth 2000-02-29 read in a COMMON year: the policies differ"
    (is (= 25 (age/completed-years (d 2000 2 29) (d 2025 2 28) :leap/feb-28)))
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2025 2 28) :leap/mar-01)))
    (is (= 25 (age/completed-years (d 2000 2 29) (d 2025 3 1) :leap/feb-28)))
    (is (= 25 (age/completed-years (d 2000 2 29) (d 2025 3 1) :leap/mar-01)))))

(deftest mirrors-the-oracle-golden-rows-under-mar-01
  (testing "calendario binds :leap/mar-01 (CC art. 132 paragraph 3); these are the 7 golden rows"
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2025 2 28) :leap/mar-01)))
    (is (= 25 (age/completed-years (d 2000 2 29) (d 2025 3 1) :leap/mar-01)))
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2024 2 29) :leap/mar-01)))
    (is (= 24 (age/completed-years (d 2000 2 29) (d 2025 2 27) :leap/mar-01)))
    (is (= 1 (age/completed-years (d 2001 1 1) (d 2002 1 1) :leap/mar-01)))
    (is (= 3 (age/completed-years (d 2001 3 1) (d 2004 3 1) :leap/mar-01)))
    (is (= 2 (age/completed-years (d 2001 3 1) (d 2004 2 29) :leap/mar-01)))))

(deftest refusals
  (testing "a missing policy has its own reason"
    (is (= :time/leap-policy-missing (m age/completed-years (d 2000 2 29) (d 2025 3 1) nil))))
  (testing "an unknown policy has its own reason"
    (is (= :time/leap-policy-unknown (m age/completed-years (d 2000 2 29) (d 2025 3 1) :leap/nope))))
  (testing "a day before birth"
    (is (= :time/age-before-birth (m age/completed-years (d 2000 6 15) (d 1999 6 15) :leap/mar-01)))))

(deftest policy-predicate-and-enum
  (is (age/leap-policy? :leap/feb-28))
  (is (age/leap-policy? :leap/mar-01))
  (is (not (age/leap-policy? :leap/nope)))
  (is (not (age/leap-policy? nil)))
  (is (= [:enum :leap/feb-28 :leap/mar-01] age/policies)))
