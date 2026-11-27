(ns hive-ttracking.time.clock
  "The Clock port: the one way the library reads the current instant.

   A port is the only way the library reaches the world. The clock comes from a
   Clock, never from a direct System/currentTimeMillis call: the protocol IS the
   seam, so a test swaps behaviour with a VALUE (fixed-clock), never a
   with-redefs and never a dynamic var.

   now returns epoch milliseconds, the same unit the instant codec uses.

   today(clock, zone) is the ONE place a clock read meets a zone: it reads the
   clock and turns the instant into the Day in `zone`. It lives here (not in
   zone.cljc) because it is a clock read first; zone is required only to
   interpret the instant."
  (:require [hive-ttracking.time.zone :as zone])
  )

(defprotocol Clock
  (now [this] "The current instant, in epoch ms."))

(defn system-clock
  "The system clock -- the ONLY clock read of the library."
  []
  (reify Clock
    (now [_] #?(:clj (System/currentTimeMillis)
                :cljs (.now js/Date)))))

(defn fixed-clock
  "A clock frozen at ms. This is how a test swaps behaviour: a value, never a
   with-redefs."
  [ms]
  (reify Clock (now [_] ms)))

(defn today
  "The Day of `clock`'s current instant in `zone`.\n\n   The ONE place a clock read meets a zone. Deferred here from step-6: it\n   requires the zone mechanics, and a clock read is not calendar arithmetic, so\n   it belongs with the Clock port, not in zone.cljc.\n\n   ttrack has no default zone: `zone` is required, explicitly. A fixed clock\n   makes it deterministic on both legs (no with-redefs)."
  [clock zone-id]
  (zone/instant->day (now clock) zone-id))
