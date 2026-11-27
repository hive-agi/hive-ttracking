(ns hive-ttracking.time.golden-test
  "The golden runner: every row of test-resources/golden/iso.edn, executed.

   The rows are ENGLISH, generic, grouped by edge-case CATEGORY, and the ids are
   stable (febd7c04's parity test pins them). ttrack's tests never name a
   calendario row: the id namespace here is the edge case (day, month, time,
   period, zone, instant, age), and the DST cases use America/Sao_Paulo as TEST
   DATA, not as a funeraria name.

   The runner executes every row on both legs. Both legs read the SAME
   repo-relative file (test-resources/golden/iso.edn): the tests run with CWD =
   time/, so the path resolves identically on clj and cljs.

   A row is {:id :op :in :expect}; :expect is a value, or {:refused <reason>}
   when the op is meant to refuse."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-ttracking.time.age :as age]
            [hive-ttracking.time.clock :as clock]
            [hive-ttracking.time.codec :as codec]
            [hive-ttracking.time.day :as day]
            [hive-ttracking.time.error :as error]
            [hive-ttracking.time.instant :as instant]
            [hive-ttracking.time.period :as period]
            [hive-ttracking.time.zone :as zone]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

;; -- read the rows on both legs ---------------------------------------

(def ^:private golden-path "test-resources/golden/iso.edn")

(defn- read-rows
  "Read the golden EDN. Both legs read the SAME repo-relative file: the tests
   run with CWD = time/, so the path resolves identically on clj and cljs."
  []
  #?(:clj (edn/read-string (slurp golden-path))
     :cljs (reader/read-string
            (.readFileSync (js/require "fs") golden-path "utf8"))))

(def rows (read-rows))

;; -- dispatch one row's op --------------------------------------------

(defn- call
  "Apply the row's op to its :in. `today` is special: its :in carries a raw
   instant, wrapped in a fixed clock (a value, never a with-redefs)."
  [op in]
  (case op
    :parse-day                 (apply codec/parse-day in)
    :format-day                (apply codec/format-day in)
    :days-between              (apply day/days-between in)
    :add-days                  (apply day/add-days in)
    :parse-month               (apply codec/parse-month in)
    :format-month              (apply codec/format-month in)
    :parse-time                (apply codec/parse-time in)
    :format-time               (apply codec/format-time in)
    :format-period             (apply codec/format-period in)
    :current-month             (apply period/current-month in)
    :previous-month            (apply period/previous-month in)
    :period->instant-interval  (apply zone/period->instant-interval in)
    :period-contains-instant?  (apply zone/period-contains-instant? in)
    :local-time->instant       (apply zone/local-time->instant in)
    :day->instant              (apply zone/day->instant in)
    :instant->day              (apply zone/instant->day in)
    :parse-instant             (apply instant/parse-instant in)
    :format-instant            (apply instant/format-instant in)
    :completed-years           (apply age/completed-years in)
    :today                     (let [[ms z] in] (clock/today (clock/fixed-clock ms) z))))

(defn- actual
  "The row's result, or its refusal reason."
  [row]
  (try (call (:op row) (:in row))
       (catch #?(:clj Exception :cljs :default) e
         (if (error/refusal? e)
           {:refused (error/reason e)}
           (throw e)))))

;; -- the runner --------------------------------------------------------

(deftest every-golden-row
  (doseq [row rows]
    (testing (str (:id row))
      (is (= (:expect row) (actual row))
          (str (:id row) " " (:op row) " " (pr-str (:in row)))))))

;; -- coverage: every category, and the total --------------------------

(def ^:private categories
  "The edge-case categories a category-coverage test asserts. Each is
   [category-id row-pred], where the row carries :id-ns (its id's namespace)."
  [[:dst-gap-and-overlap
    (fn [r] (and (= :zone (:id-ns r)) (re-find #"sao-paulo" (name (:id r)))))]
   [:leap-days
    (fn [r] (and (= :parse-day (:op r)) (re-find #"leap" (name (:id r)))))]
   [:completed-years-both-policies
    (fn [r] (= :completed-years (:op r)))]
   [:range-edges
    (fn [r] (or (re-find #"range" (name (:id r)))
                (= :day/zero-go-refused (:id r))))]
   [:codecs-day-month-period
    (fn [r] (contains? #{:parse-day :format-day :parse-month :format-month
                         :format-period :current-month :previous-month}
                       (:op r)))]
   [:legacy-time-formats
    (fn [r] (= :parse-time (:op r)))]
   [:instant-vs-day
    (fn [r] (contains? #{:parse-instant :format-instant :instant->day} (:op r)))]
   [:today-three-zones
    (fn [r] (= :today (:op r)))]])

(defn- with-id-ns
  "The row with :id-ns added: :day/leap-2024-02-29 -> :id-ns :day."
  [r]
  (assoc r :id-ns (keyword (namespace (:id r)))))

(deftest golden-covers-every-category
  (let [hits (reduce (fn [acc row]
                       (let [r (with-id-ns row)]
                         (reduce (fn [a [cid pred]]
                                   (if (pred r) (conj a cid) a))
                                 acc categories)))
                     #{} rows)]
    (doseq [[cid _] categories]
      (testing (str cid)
        (is (contains? hits cid) (str "no golden row covers " cid))))))

(deftest golden-total-is-at-least-the-54-generic-cases
  (testing "ttrack's own golden must cover >= the 54 GENERIC edge cases"
    (is (<= 54 (count rows)) (str "only " (count rows) " rows")))
  (testing "every row is well-formed and its id is unique"
    (is (every? #(and (:id %) (:op %) (contains? % :in) (contains? % :expect)) rows))
    (is (= (count rows) (count (distinct (map :id rows))))))
  (testing "the ids are ENGLISH and namespaced, never funeraria row names"
    (is (every? #(and (keyword? (:id %)) (namespace (:id %))) rows))
    (is (not-any? #(re-find #"(?i)idade|competencia|vencimento|prazo|horario"
                            (name (:id %)))
                  rows))))
