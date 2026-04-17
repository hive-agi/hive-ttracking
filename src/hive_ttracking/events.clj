(ns hive-ttracking.events
  "BC-5 Events — pure event builders for the :tt/* namespace + defensive
   publish wrapper over hive.events/dispatch.

   Wave-2: builders + publish. Full router wiring stays in consumers'
   init. See PLAN.md §2 BC-5."
  (:require [hive.events :as ev]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Pure builders — no side effects. Consumers pipe these into hive-events.
;; ---------------------------------------------------------------------------

(defn span-started
  [span]
  {:event/type :tt/span-started
   :span       span
   :at         (System/currentTimeMillis)})

(defn span-ended
  [span result]
  {:event/type :tt/span-ended
   :span       span
   :result     result
   :at         (System/currentTimeMillis)})

(defn budget-violation
  [span budget]
  {:event/type :tt/budget-violation
   :span       span
   :budget     budget
   :at         (System/currentTimeMillis)})

(defn bench-completed
  [bench-result]
  {:event/type :tt/bench-completed
   :result     bench-result
   :at         (System/currentTimeMillis)})

(defn bench-run
  [bench-result]
  {:event/type :tt/bench-run
   :result     bench-result
   :at         (System/currentTimeMillis)})

;; ---------------------------------------------------------------------------
;; Defensive publish — forwards to hive.events/dispatch.
;;
;; Tracking must never corrupt caller control flow. If the router is not
;; init'd or a handler throws, publish swallows + logs at :debug.
;; ---------------------------------------------------------------------------

(defn publish
  "Dispatch a tt event via hive.events/dispatch. Event must be a map with
   :event/type. Returns the event (for pipelines). Swallows all throwables
   so tracking never breaks the traced code."
  [event]
  (try
    (ev/dispatch [(:event/type event) event])
    (catch Throwable t
      (log/debug t "tt/publish swallowed" (:event/type event))
      nil))
  event)

(defn emit!
  "Deprecated alias for `publish`. Retained for Wave-1 call sites."
  [event]
  (publish event))
