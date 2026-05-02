(ns hive-ttracking.init
  "IAddon init hook — invoked by hive-di when hive-ttracking is on the
   classpath and config enables it. Wave-1 skeleton: no-op.")

(defn init-as-addon!
  "Wave-3+ wires default tracker + sink + event router into DI container.
   Wave-1 stub. Zero-arity per loader contract (extensions/loader.clj:78)."
  []
  {:ok true :addon :hive.ttracking :wave 1 :note "skeleton"})
