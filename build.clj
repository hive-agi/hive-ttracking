(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def lib 'io.github.hive-agi/hive-ttracking)
(def version (str/trim (slurp "VERSION")))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

;; deps.edn :paths — everything that ships inside the jar.
(def paths ["src" "resources"])
;; The pom's <sourceDirectory> names code roots only, not resource dirs.
(def src-dirs ["src"])

(def readme "README.md")

(defn coord-patterns
  "Regexes matching the README install coordinates as (prefix)(version)(suffix).
   Derived from `lib`, so a group/artifact rename can't silently orphan them."
  []
  (let [l (java.util.regex.Pattern/quote (str lib))]
    [;; deps.edn:   io.github.hive-agi/hive-ttracking {:mvn/version "X"}
     (re-pattern (str "(" l " \\{:mvn/version \")([^\"]+)(\")"))
     ;; Leiningen:  [io.github.hive-agi/hive-ttracking "X"]
     (re-pattern (str "(\\[" l " \")([^\"]+)(\")"))]))

(defn readme-versions
  "Every version currently pinned in the README's install coordinates."
  [source]
  (into [] (mapcat #(map second (re-seq % source))) (coord-patterns)))

(defn- sync-readme
  "Rewrite the README install coordinates to `version`. Returns the new source."
  [source]
  (reduce (fn [s re] (str/replace s re (str "$1" version "$3")))
          source
          (coord-patterns)))

(def pom-data
  [[:description "Call-wrapping instrumentation library for the Hive stack: budget-aware time tracking, spans, benchmarking and protocol instrumentation (short alias: tt)."]
   [:url "https://github.com/hive-agi/hive-ttracking"]
   [:licenses
    [:license
     [:name "MIT"]
     [:url "https://opensource.org/license/mit"]]]
   [:scm
    [:url "https://github.com/hive-agi/hive-ttracking"]
    [:connection "scm:git:git://github.com/hive-agi/hive-ttracking.git"]
    [:developerConnection "scm:git:ssh://git@github.com/hive-agi/hive-ttracking.git"]
    [:tag (str "v" version)]]
   [:developers
    [:developer
     [:name "Pedro G. Branquinho"]]]])

(defn clean [_]
  (b/delete {:path "target"}))

(defn sync-version
  "Propagate the canonical top-level VERSION to everything that restates it:
   currently just the README install coordinates. No-ops when the repo has no
   README yet."
  [_]
  (if-not (.exists (io/file readme))
    (println (str "Skipped " readme " sync — file does not exist"))
    (let [before (slurp readme)
          after  (sync-readme before)]
      (when (not= before after)
        (spit readme after))
      (println (str "Synced " readme " install coords -> " version
                    (when (= before after) " (already current)"))))))

(defn jar
  "Build the library thin jar + pom for Clojars/Maven consumption."
  [_]
  (clean nil)
  (sync-version nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs src-dirs
                :pom-data pom-data})
  (b/copy-dir {:src-dirs paths
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (println (str "Built " jar-file)))

(defn deploy
  "Deploy the library jar to Clojars. Requires CLOJARS_USERNAME + CLOJARS_PASSWORD
   (a Clojars deploy token) in the environment."
  [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact  jar-file
    :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
  (println (str "Deployed " lib " " version " to Clojars")))
