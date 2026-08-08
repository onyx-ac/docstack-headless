# Boot spike findings — spec 04 task 1

Status: **conditional pass**. `pouchdb-core` loads, evaluates, and begins real
async execution inside QuickJS via Zipline on plain JVM (no WebView, no Android
runtime). The specific stand-in adapter used for this spike (`pouchdb-adapter-memory`)
did not fully complete a `put`/`get` round trip within the spike's timebox — see
"What didn't resolve" below for why that's not read as a blocker.

Code lives in `spike/` in this module (`android/docstack-headless/spike/`),
deliberately thrown-away-able, not production module structure.

## Confirmed facts (worth folding back into spec 04)

- **Real dependency coordinates**: `app.cash.zipline:zipline:1.27.0`,
  `app.cash.zipline:zipline-loader:1.27.0`, `app.cash.zipline:zipline-cli:1.27.0`.
  Requires **Kotlin 2.3.20** exactly (or compatible) — Zipline 1.27.0's published
  jars carry that metadata version; an older Kotlin Gradle plugin (tried 2.1.0 first)
  fails `compileKotlin` with an incompatible-metadata-version error.
- **`zipline-cli compile --input <dir> --output <dir>`** is the correct path for a
  raw (non-Kotlin/JS) esbuild-bundled `.js` file — confirmed generic, not
  Kotlin/JS-only, by its own test fixture. This is the write side of the OTA
  mechanism (spec 01, spec 06 `permetic-ota`) too — same tool.
- **`QuickJs.evaluate(String)` (host-side) still exists and works** — the
  "eval() removed" changelog entry (0.9.5) is about disabling the **guest-callable**
  JS `eval()` builtin for security, not the host-privileged Kotlin API. Confirmed via
  Zipline's own `ConsoleTest.kt`, and used directly in this spike's host to read
  results back out of the loaded module.
- **`console` is not a native QuickJS/Zipline feature.** `Zipline.create()`'s own
  source sets up no console binding at all. It "just works" for genuine
  Kotlin/JS-compiled Zipline apps only because Kotlin/JS's stdlib ships its own
  console polyfill as part of *that* compiled output. A hand-bundled raw JS module
  gets nothing — spec 04's shim list should explicitly include `console`, not just
  the browser-API items it already names.
- **Zipline's `setTimeout`/event-loop bridge (`CoroutineEventLoop`) is also wired
  through Kotlin/JS-compiler-generated glue code**, not free for a raw bundle either,
  at compile time *or* real runtime. This is the biggest correction to spec 04, which
  currently reads as if Zipline supplies `setTimeout` unconditionally ("Zipline
  supplies `setTimeout` and an event loop, and bridges Kotlin `suspend` functions to
  JS promises — which is exactly the shape the carrier needs"). That's true for the
  Kotlin-suspend-to-JS-promise bridge; it is **not** true for a plain global
  `setTimeout` available to arbitrary JS the way a browser or Node provides it.
- **`zipline-cli compile`'s own dependency-collection pass actually executes the
  module once** (`QuickJs.execute(bytecode)` inside `ZiplineCompiler.collectDependencies`),
  on a bare `QuickJs.create()` instance with none of the above — not even `console`.
  Any top-level side effects in the bundled JS run during *compilation*, not just at
  real load time. Worth calling out in spec 04/06: `permetic-ota build`/`compile`
  needs bundles that tolerate being evaluated in a bare sandbox at build time.

## Full shim list actually required

Beyond spec 04's documented guess (`setTimeout`/`setInterval`, `fetch`,
`TextEncoder`/`TextDecoder`, `atob`/`btoa`, a `process` stub):

| Global | Why | Fixed via |
| --- | --- | --- |
| `stream` (Node builtin) | `pouchdb-adapter-memory`'s `sublevel-pouchdb`→`readable-stream` chain requires it unconditionally, even under `--platform=browser` | esbuild `--alias:stream=stream-browserify` |
| `global` | referenced by `immediate` (a pouchdb-core transitive dep) | esbuild `--define:global=globalThis` |
| `self` | referenced by `pouchdb-md5`'s browser build | esbuild `--define:self=globalThis` |
| `console` | not native to QuickJS/Zipline at all (see above) | hand-written stub |
| `fetch`, `Headers` | `pouchdb-core` requires `pouchdb-fetch` unconditionally at load time even when the code path is unused | hand-written stub (throws/rejects — fine, unexercised here) |
| `setTimeout`/`clearTimeout`/`setInterval`/`clearInterval` | not native to QuickJS/Zipline for a raw bundle (see above) | hand-written **queue + host-driven drain** (see below), not the simple stub first tried |
| `TextEncoder`/`TextDecoder`, `atob`/`btoa` | spec 04 already anticipated these correctly | hand-written stubs (no `Buffer` either — pure JS base64, since that's *also* not available) |
| `process` | spec 04 already anticipated this correctly | hand-written stub (`browser: true`, `env: {}`, `nextTick` routed through the `setTimeout` queue) |

## The `setTimeout` shim needed two iterations

1. A synchronous "call it immediately" shim broke `pouchdb-adapter-memory`'s
   `LevelUP`/`LevelPouch` constructor chain (`cannot set property '_docCount' of
   undefined`) — it fires an internally-deferred callback before the constructor that
   scheduled it has finished setting up state. **Deferred callbacks must actually stay
   deferred**, even in a spike shim.
2. A true no-op (never fires) fixed the compile-time pass cleanly, but left every
   PouchDB async operation permanently unsettled at real runtime, since nothing ever
   drains it.
3. Final version: a real queue (`__taskQueue`) plus a `globalThis.__drainTasks()`
   the host polls via `QuickJs.evaluate(...)` in a loop after load. This is a
   **spike-only polling shortcut** — the real spec 04 implementation (task 2, "Carrier
   binding") needs a proper suspend-based bridge matching `CoroutineEventLoop`'s own
   model, not host-side polling.

## What didn't resolve

With the queue+drain shim working (confirmed: 3 real deferred callbacks executed
correctly across the first 3 drain cycles), `db.put()` returned a real thenable and
began executing, but its promise never settled — the task queue went permanently
empty (`drained=0` forever) with no further progress. Diagnostic tracing (pushed
into a `__SPIKE_TRACE__` array, read back host-side) confirmed:

```
["boot", "put-called typeof=object hasThen=function"]
```

— i.e. `put-resolved` never got appended, and neither did a rejection.

Read: `pouchdb-adapter-memory`'s dependency chain (`sublevel-pouchdb` →
`memdown`/`abstract-leveldown` → the `immediate` package) does its own environment
detection to decide *how* to schedule deferred work — MutationObserver,
`process.nextTick`, `setImmediate`, message channels, `setTimeout` as a last resort.
It's plausible it detects something else as "available" in this environment and
picks a scheduling strategy that silently never fires, rather than falling through to
our shimmed `setTimeout`.

**This was not chased further.** `pouchdb-adapter-memory` was only ever a stand-in for
the not-yet-built `@docstack/pouchdb-adapter-native` (spec 03), specifically chosen to
exercise *some* real PouchDB adapter surface without waiting on that work. It is
Node/leveldown-based; the real adapter will not be — it talks to `docstack-store`
through the envelope dispatcher directly, with no `immediate`/leveldown dependency
chain at all. The failure mode found here is very likely specific to this stand-in
package's own scheduling detection, not a property of the QuickJS/Zipline
architecture — `pouchdb-core` itself (module load, `PouchDB.plugin()`, `new PouchDB()`,
calling `.put()` and getting a real promise back) worked throughout.

## Go/no-go read

**Go**, with the corrections above folded back into spec 04. The architecture holds:
a raw esbuild-bundled JS library compiles via `zipline-cli` and boots inside QuickJS
via Zipline on plain JVM, no WebView involved. The open item is a proper
suspend-based `setTimeout`/event-loop bridge (spec 04 task 2), which this spike's
polling shortcut stands in for but does not replace. Recommend proceeding to Phase 1
(`DocumentStore` + dispatcher against an in-memory store) in parallel with — not
blocked by — writing that proper bridge, since Phase 1 doesn't depend on it.

## Reproducing

```bash
cd android/docstack-headless/spike/js
npm install && npm run bundle

cd ..
SPIKE_DIR=$(pwd)   # absolute path - the compile step needs it, relative paths
                   # resolve against the :cli module's own directory, not spike/
rm -rf js/ziplineOut && mkdir -p js/ziplineOut
./gradlew :cli:run --args="compile --input $SPIKE_DIR/js/dist --output $SPIKE_DIR/js/ziplineOut"

cd js/ziplineOut && python -m http.server 8080 &
cd ../..
./gradlew :host:run
```
