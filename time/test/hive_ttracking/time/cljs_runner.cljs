(ns hive-ttracking.time.cljs-runner
  "The node entry point: run every ttrack test on the cljs leg.

   Built by `cljs.closure/build` in a REPL (no shadow, no npm): the tests are
   compiled and node runs the bundle. This ns is NOT a test ns itself; it just
   pulls the suites in and reports."
  (:require [cljs.test :as t :refer-macros [run-tests]]
            [hive-ttracking.time.core-test]
            [hive-ttracking.time.codec-test]
            [hive-ttracking.time.clock-test]
            [hive-ttracking.time.zone-test]
            [hive-ttracking.time.age-test]
            [hive-ttracking.time.coverage-test]
            [hive-ttracking.time.golden-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (if (t/successful? m)
    (do (println "CLJS-SUITE-PASS") (js/process.exit 0))
    (do (println "CLJS-SUITE-FAIL") (js/process.exit 1))))

(defn -main [& _]
  (run-tests 'hive-ttracking.time.core-test
             'hive-ttracking.time.codec-test
             'hive-ttracking.time.clock-test
             'hive-ttracking.time.zone-test
             'hive-ttracking.time.age-test
             'hive-ttracking.time.coverage-test
             'hive-ttracking.time.golden-test))

(set! *main-cli-fn* -main)
