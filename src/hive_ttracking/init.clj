(ns hive-ttracking.init
  "IAddon init hook — invoked by hive-di when hive-ttracking is on the
   classpath and config enables it. Wave-1 skeleton: no-op."
  (:require [hive-addon.protocol]))

(defn init-as-addon!
  "Wave-3+ wires default tracker + sink + event router into DI container.
   Wave-1 stub. Zero-arity per loader contract (extensions/loader.clj:78)."
  []
  {:ok true :addon :hive.ttracking :wave 1 :note "skeleton"})

(defn- make-addon-record
  "Create the `hive.ttracking` IAddon (hive-addon.protocol/IAddon). Wave-1
   skeleton: initialize! is a no-op mirroring init-as-addon!'s net-effect;
   shutdown! releases nothing. Returns nil when the IAddon protocol is off the
   classpath."
  []
  (when (try (requiring-resolve 'hive-addon.protocol/IAddon)
             (catch Throwable _ nil))
    (reify hive-addon.protocol/IAddon
      (addon-id [_] "hive.ttracking")
      (addon-type [_] :native)
      (capabilities [_] #{:tracking :budget-enforcement :span-sink :bench
                          :protocol-instrumentation})
      (initialize! [_ _config]
        {:success? true :errors [] :metadata {:wave 1 :note "skeleton"}})
      (shutdown! [_] nil)
      (tools [_] [])
      (schema-extensions [_] [])
      (excluded-tools [_] #{})
      (hooks [_] {})
      (health [_] {:status :ok :details {:wave 1 :note "skeleton"}}))))

(defn addon-ctor
  "Pure constructor for the `hive.ttracking` IAddon — (config -> IAddon | nil).
   The mounter (hive-addon.mount.compose) resolves this via :addon/init-fn; the
   host then drives register!/initialize!. Returns nil when the IAddon protocol
   is absent. Additive: the zero-arg init-as-addon! path stays for the legacy
   hive-mcp loader."
  [_config]
  (make-addon-record))