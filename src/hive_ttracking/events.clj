(ns hive-ttracking.events
  "BC-5 Events — pure event builders for the :tt/* namespace.

   Wave-1 skeleton: event constructors. Wave-3 wires them through
   hive.events.router. See PLAN.md §2 BC-5.")

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

;; ---------------------------------------------------------------------------
;; Router hookup — Wave-3.
;; ---------------------------------------------------------------------------

(defn emit!
  "Wave-3 dispatches via hive.events.router. Wave-1 stub is a no-op."
  [_event]
  :tt/skeleton)
