(ns hive-ttracking.instrument-test
  "Golden tests for instrument/wrap and wrap-fn, using a recording stub sink."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-ttracking.instrument :as instrument]
            [hive-ttracking.metrics :as m]))

(defprotocol IStore
  (fetch [this k] [this k default])
  (put! [this k v])
  (boom [this]))

(defrecord MapStore [a]
  IStore
  (fetch [_ k] (get @a k))
  (fetch [_ k d] (get @a k d))
  (put! [_ k v] (swap! a assoc k v) :stored)
  (boom [_] (throw (ex-info "boom" {}))))

(def ^:private obs (atom []))

(def ^:private recording-sink
  (reify m/IMetricsSink
    (observe! [_ k ns outcome] (swap! obs conj [k outcome (int? ns)]))
    (snapshot [_] {})))

(use-fixtures :each (fn [f]
                      (reset! obs [])
                      (m/install! recording-sink)
                      (try (f) (finally (m/install! nil)))))

(deftest wrap-forwards-and-records
  (let [w (instrument/wrap #'IStore (->MapStore (atom {})) {:area :store/test :label :mem})]
    (is (satisfies? IStore w))
    (is (= :stored (put! w :a 1)))
    (is (= 1 (fetch w :a)))
    (is (= :d (fetch w :zz :d)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom" (boom w)))
    (is (= [[[:store/test :mem "put!"] :ok true]
            [[:store/test :mem "fetch"] :ok true]
            [[:store/test :mem "fetch"] :ok true]
            [[:store/test :mem "boom"] :error true]]
           @obs))))

(deftest wrap-label-fn-and-default-area
  (let [w (instrument/wrap #'IStore (->MapStore (atom {})) {:label (fn [t] (-> t class .getSimpleName))})]
    (fetch w :x)
    (is (= [[:proto/call "MapStore" "fetch"] :ok true] (first @obs))))
  (reset! obs [])
  (let [w (instrument/wrap #'IStore (->MapStore (atom {})))]
    (fetch w :x)
    (is (= [[:proto/call "fetch"] :ok true] (first @obs)))))

(deftest wrap-nil-target-and-cache
  (is (nil? (instrument/wrap #'IStore nil {})))
  (let [a (instrument/wrap #'IStore (->MapStore (atom {})) {:label :a})
        b (instrument/wrap #'IStore (->MapStore (atom {})) {:label :b})]
    (is (= (class a) (class b)) "one generated class per protocol")))

(deftest wrap-survives-throwing-sink
  (m/install! (reify m/IMetricsSink
                (observe! [_ _ _ _] (throw (RuntimeException. "sink down")))
                (snapshot [_] {})))
  (let [w (instrument/wrap #'IStore (->MapStore (atom {:k 9})) {:area :s})]
    (is (= 9 (fetch w :k)))))

(deftest wrap-fn-records
  (let [f (instrument/wrap-fn [:proc/shell "echo"] (fn [& xs] (apply + xs)))]
    (testing "every arity forwards"
      (is (= 0 (f)))
      (is (= 1 (f 1)))
      (is (= 15 (f 1 2 3 4 5))))
    (is (= 3 (count @obs)))
    (is (every? #(= [[:proc/shell "echo"] :ok true] %) @obs)))
  (reset! obs [])
  (let [g (instrument/wrap-fn [:x] (fn [] (throw (ex-info "no" {}))))]
    (is (thrown? clojure.lang.ExceptionInfo (g)))
    (is (= [[[:x] :error true]] @obs))))
