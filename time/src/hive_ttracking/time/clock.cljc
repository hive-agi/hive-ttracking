(ns hive-ttracking.time.clock
  "The Clock port: the one way the library reads the current instant.

   A port is the only way the library reaches the world. The clock comes from a
   Clock, never from a direct System/currentTimeMillis call: the protocol IS the
   seam, so a test swaps behaviour with a VALUE (fixed-clock), never a
   with-redefs and never a dynamic var.

   now returns epoch milliseconds, the same unit the instant codec uses.

   today(clock, zone) does NOT live here: a clock read is not calendar
   arithmetic, and turning an instant into a Day needs the zone mechanics of
   step-5 (time/zone.cljc). It is therefore in step-5's scope."
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
