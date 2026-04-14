(ns hive-ttracking.track
  "BC-1 Tracking — span lifecycle + track/track-race macros.

   Wave-1 skeleton: protocol + stubs. Wave-2 fills track*, Wave-3 fills track-race*.
   See PLAN.md §2 BC-1."
  (:require [hive-ttracking.budget :as budget]))

(defprotocol ITracker
  "Owns span lifecycle. Pure when backed by atom-sink; async when pool-backed."
  (start-span   [this span-name tags]
    "Return an open Span value with :id :parent :t0.")
  (end-span     [this span result]
    "Close span, emit :tt/span-ended, return Result.")
  (record-sample [this span-id k v]
    "Attach a typed sample to an open span (latency subcomponent, counter, ...)."))

;; ---------------------------------------------------------------------------
;; Value types
;; ---------------------------------------------------------------------------

(defrecord Span [id parent-id name t0 t1 tags result samples])

(defn make-span
  [{:keys [id parent-id name tags]}]
  (->Span id parent-id name (System/nanoTime) nil (or tags {}) nil []))

;; ---------------------------------------------------------------------------
;; Default tracker — no-op placeholder for Wave-1 wiring.
;; Wave-2 replaces with AtomTracker backed by hive-dsl bounded-atom.
;; ---------------------------------------------------------------------------

(def ^:dynamic *default-tracker* nil)

;; ---------------------------------------------------------------------------
;; Macros' runtime entry points — stubs.
;; ---------------------------------------------------------------------------

(defn track*
  "Wave-2. Wraps thunk with start-span/end-span + budget assertion."
  [opts thunk]
  ;; SKELETON — returns raw result, no budget, no span.
  {:tt/skeleton true :result ((or thunk (constantly nil)))})

(defn track-race*
  "Wave-3. Races branches-map under a single budget using hive-weave pool.
   Returns {branch-key Result}. Replaces the ad-hoc future/deref/cancel pattern
   seen in hive-mcp.tools.catchup.scope/query-axioms."
  [opts branches-map]
  {:tt/skeleton true
   :branches    (into {} (for [[k _] branches-map] [k :tt/not-implemented]))})
