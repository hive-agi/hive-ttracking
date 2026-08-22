# hive-ttracking

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/hive-ttracking.svg)](https://clojars.org/io.github.hive-agi/hive-ttracking)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/hive-ttracking)](https://cljdoc.org/d/io.github.hive-agi/hive-ttracking/CURRENT)
[![release](https://github.com/hive-agi/hive-ttracking/actions/workflows/release.yml/badge.svg)](https://github.com/hive-agi/hive-ttracking/actions/workflows/release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

<!-- /hive-badges -->

**Time tracking, budgets and benchmarking for Clojure, as values.** Spans,
declarative budgets, statistical benchmarks and `:tt/*` events — every public
call returns a `Result`, and nothing in the domain layer reads the system clock
directly.

## Coordinates

```clojure
;; deps.edn
io.github.hive-agi/hive-ttracking {:mvn/version "0.1.10"}
```

## Usage

```clojure
(require '[hive-ttracking.core :as tt])

(tt/with-tracker [t {}]
  (tt/track {:name :fetch} (fetch! url))
  (tt/track {:name :parse} (parse body)))   ;; both land in `t`

(tt/track-race {:name :either} (a) (b))
```

## Bounded contexts

| Namespace | Provides |
|---|---|
| `hive-ttracking.core` | Thin facade — `with-tracker`, `track`, `track-race`. All fns return `Result` |
| `hive-ttracking.clock` | The clock facade domain code must route through |
| `hive-ttracking.budget` | Declarative budgets, policy and assertion. Pure — no I/O, no deps on hive-weave or hive-events |
| `hive-ttracking.span` | Span sink abstraction plus pure helpers over span values |
| `hive-ttracking.bench` | Warmup + N timed iterations, p50/p95/p99/min/max/mean/stddev, and a `defbench` macro wrapping `deftest` with a p95 threshold assertion |
| `hive-ttracking.events` | Pure builders for the `:tt/*` event namespace, with a defensive publish wrapper over `hive.events/dispatch` |
| `hive-ttracking.instrument` | Pass-through `defrecord`/`reify` wrappers for protocols — wrapped code is untouched, no call sites rewritten |
| `hive-ttracking.init` | `IAddon` init hook, invoked when the library is on the classpath and enabled by config |

## Two design constraints worth knowing

**Domain code must not call `java.time.*` or `System/currentTimeMillis`.** It
routes through `hive-ttracking.clock`, so a test can pin time without
`with-redefs` on a JVM static.

**Instrumentation is open-closed.** Wrapping a protocol implementation adds a
pass-through record around it; the wrapped code is not edited and no call site
changes.

## License

MIT.
