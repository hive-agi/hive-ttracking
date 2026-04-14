(ns hive-ttracking.instrument
  "BC-6 Instrumentation — defrecord/reify pass-through wrappers for protocols.

   OCP-compliant: wrapped code is untouched. Zero call-site rewriting.
   Wave-1 skeleton. Wave-4 implements protocol reflection + wrapper gen.
   See PLAN.md §2 BC-6.")

(defn wrap
  "Return a pass-through wrapper implementing `proto` that forwards to
   `target` and emits a span per call.

   opts:
     :span-name (fn [method-kw args] => keyword)
     :tags      (fn [method-kw args] => map)

   Wave-4 implementation uses `clojure.core/extend` + protocol method
   reflection to generate the wrapper at runtime. Wave-1 stub returns
   `target` unchanged so consumers can wire the call site today."
  [_proto target _opts]
  ;; SKELETON — no-op pass-through.
  target)
