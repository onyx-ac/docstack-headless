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
via Zipline on plain JVM, no WebView involved. The open item at the time this section
was written was a proper suspend-based `setTimeout`/event-loop bridge (spec 04 task 2),
which this spike's polling shortcut stood in for but did not replace. Recommend
proceeding to Phase 1 (`DocumentStore` + dispatcher against an in-memory store) in
parallel with — not blocked by — writing that proper bridge, since Phase 1 doesn't
depend on it. **Resolved 2026-08-10 — see the section below.**

## Task 2 continuation: the real setTimeout/event-loop bridge (2026-08-10)

**Status: resolved.** The real Zipline event-loop bridge works, confirmed by an actual
Kotlin coroutine `delay()` round trip completing inside QuickJS via the exact
production mechanism every Zipline app relies on — not the host-side polling shim
above. This also answers the "what's ergonomic to bind through Zipline" open question
blocking task 7's CRUD-surface signatures (see spec 04's "Kotlin API surface" section
for the resulting `HeadlessCarrier`/`PouchDbFacade` design).

### The mechanism (read directly from `cashapp/zipline` tag `1.27.0` source, not guessed)

The original spike's own notes correctly identified that Zipline's `setTimeout` bridge
is "wired through Kotlin/JS-compiler-generated glue code" without being able to see
what that glue actually does. Reading the real source resolves it:

- `Zipline.kt` (jsMain)'s `Zipline.get()` is a singleton constructor. On first call it
  does `bind<GuestService>(ZIPLINE_GUEST_NAME, GlobalBridge)` — referencing the
  `GlobalBridge` Kotlin `object` for the first time runs its `init {}` block.
- `GlobalBridge.kt` (jsMain)'s `init {}` runs raw JS (via Kotlin/JS's `js("""...""")`
  interop) that installs `globalThis.setTimeout`, `globalThis.clearTimeout`, and
  `globalThis.console`, each wired through `zipline.host` (a taken `HostService` RPC
  proxy) to the **host-side** `CoroutineEventLoop`
  (`app.cash.zipline.internal.CoroutineEventLoop`, hostMain) — a real `CoroutineScope`
  doing real `delay()`/dispatch, not a stub.
- This only exists once real Kotlin/JS-compiled code (built with the
  `app.cash.zipline` Gradle plugin) runs in the guest and calls `Zipline.get()`. Our
  esbuild-bundled `pouchdb-core` + adapters are plain JS, never compiled by Kotlin/JS —
  nothing triggers it on its own. Confirms the original spike's own conclusion, now
  with the exact mechanism identified.

### What was built to prove it

A new `bootstrap/` Gradle module (`kotlin("multiplatform")` + `kotlin("plugin.serialization")`
+ `id("app.cash.zipline") version "1.27.0"`, `js { browser(); binaries.executable() }`,
`zipline { mainFunction.set("app.cash.zipline.docstack.spike.bootstrap.launchZipline") }`)
— the exact Gradle shape from `samples/trivia/trivia-js/build.gradle.kts`, confirmed to
work unmodified. `launchZipline()` (`bootstrap/src/jsMain/kotlin/.../Bootstrap.kt`) calls
`Zipline.get()`, then `GlobalScope.launch { delay(30); ... }` to actually exercise the
bridge, writing progress markers to `globalThis.__BOOTSTRAP_TRACE__` the host reads back.

Building this module (`./gradlew :bootstrap:compileProductionExecutableKotlinJsZipline`)
produces its own multi-module manifest (`bootstrap/build/zipline/Production/manifest.zipline.json`)
— separate `.zipline` files for Kotlin's stdlib, `kotlinx-coroutines-core`,
`kotlinx-serialization` (core + json), the `zipline` library itself, and our own module,
each with `dependsOnIds` giving the real dependency graph, `mainModuleId`/`mainFunction`
pointing at our module. Confirms Zipline's documented "modular applications" story
(README: "Each input module... is downloaded concurrently") is real infrastructure, not
marketing — exactly the mechanism needed to combine this with a separately
esbuild-compiled bundle.

**Combining with the existing esbuild pouchdb bundle**: manifest merging, not textual
concatenation — added the existing single-module `spike.js` (`js/ziplineOut/`) as one
more entry in `bootstrap`'s manifest, `dependsOnIds: ["./docstack-headless-boot-spike-bootstrap.js"]`
so it loads after the Kotlin/JS module, `mainModuleId`/`mainFunction` left pointing at
the bootstrap module (unchanged) — combined output lives in `spike/combined/`. Worked on
the first attempt; the textual-concatenation fallback in the original plan was never
needed.

**Sequencing correction found while building this**: `mainFunction` runs *after* every
listed module's own top-level code has already executed (all modules load/define first,
`mainFunction` is invoked last) — confirmed empirically, matching how `launchZipline` in
the trivia sample only calls `Zipline.get()` once the `zipline` library module itself has
already loaded. This means `entry.js`'s old top-level `db.put()`/`db.get()` call would
have executed *before* `GlobalBridge` installs the real `setTimeout`/`console` — same
"real setTimeout isn't there yet" problem, just moved earlier. Fixed by deferring it:
`entry.js` now only *defines* `globalThis.__runPouchTest = function() {...}` at module-load
time (no immediate call), and `launchZipline()` calls it explicitly after `Zipline.get()`
returns. The old defensive `console`/`setTimeout` stubs at the top of `entry.js` are
harmless leftovers for this module-load window — `GlobalBridge` unconditionally
overwrites both globals before `__runPouchTest()` ever runs, so they're dead code by the
time anything real happens, kept only so `require('pouchdb-core')` doesn't throw during
the load-time window before that overwrite.

**One Windows/Git-Bash gotcha, unrelated to Zipline**: `zipline-cli compile --input`/`--output`
takes a `java.io.File`, and `ZiplineCompiler.compile()` calls `inputDir.listFiles()!!` —
a POSIX-style path from Git Bash's `$(pwd)` (`/e/repos/...`) makes `File.listFiles()`
return `null` on the JVM on Windows, throwing a bare `NullPointerException` with a
one-frame stack trace and no other clue. Fix: use a Windows-style absolute path
(`E:/repos/...`, forward slashes are fine) when constructing `--input`/`--output`, not
whatever `$(pwd)` gives you in Git Bash.

### Result: the bridge works; the remaining stall is the same pre-existing, already-diverged issue

`BOOTSTRAP_TRACE` from a real run:
```
["launchZipline-start","zipline-get-done typeof-setTimeout=function typeof-console=object","before-delay","after-delay","invoking-runPouchTest typeof=function"]
```
`before-delay` → `after-delay` is a genuine Kotlin `delay(30)` resolving through the real
`CoroutineEventLoop`/`GlobalBridge` bridge — conclusive proof this mechanism works, no
host-side polling involved anywhere in that sequence.

`SPIKE_TRACE` from the same run:
```
["boot","__runPouchTest-start typeof-setTimeout=function","put-called typeof=object hasThen=function"]
```
`db.put()` is called (with a real `setTimeout` now visible in scope, confirmed by the
trace) and returns a real thenable, but `put-resolved` never appears — the exact same
symptom the original spike's "What didn't resolve" section already described and
attributed to `pouchdb-adapter-memory`'s own dependency chain
(`sublevel-pouchdb`→`memdown`/`abstract-leveldown`→`immediate`) doing its own environment
detection for how to schedule deferred work, independently of whatever real
`globalThis.setTimeout` is present. That diagnosis holds up even better now: the failure
persists with a **real, working** `setTimeout`, which rules out "the shim isn't good
enough" as the explanation and narrows it further to something specific to `immediate`'s
own detection logic (its priority order likely reaches `MutationObserver`/`setImmediate`
checks - neither stubbed here - before falling back to the `setTimeout`-backed path).
Not chased further here, same call as the original spike: `pouchdb-adapter-memory` is a
throwaway stand-in for the not-yet-built `@docstack/pouchdb-adapter-native` at the time
this spike was written (spec 03, since completed) — that real adapter talks to
`docstack-store` through the envelope dispatcher directly, with no `immediate`/leveldown
dependency chain at all, so this specific stall has no bearing on the real adapter.

### What this resolves for spec 04

- Task 2 ("Carrier binding... Task 2 needs to supply a real `setTimeout` implementation
  bridged to Kotlin's coroutine dispatcher") — the mechanism is proven. Production work
  remaining: wire a real `HeadlessCarrier`/`PouchDbFacade` (see spec 04) instead of the
  trivial round-trip proof here, and fold the Kotlin/JS bootstrap + manifest-merge
  pattern into the real (non-spike) `docstack-headless` module.
- The "Open questions to settle before/during Phase 3" item ("Write up the Kotlin CRUD
  surface's exact method signatures once the Phase 0 boot spike confirms what's
  ergonomic to bind through Zipline") — answered: `ZiplineService` interfaces bound via
  `bind`/`take` in either direction, `Flow<T>` supported as a bound return type per
  Zipline's own README. Signatures written up in spec 04 directly.

## Reproducing (original polling-shim spike)

```bash
cd android/docstack-headless/spike/js
npm install && npm run bundle

cd ..
SPIKE_DIR=E:/repos/docstack/android/docstack-headless/spike   # Windows-style absolute
                   # path - Git Bash's $(pwd) (/e/repos/...) makes java.io.File resolve
                   # to null on Windows, see the task-2-continuation section below
rm -rf js/ziplineOut && mkdir -p js/ziplineOut
./gradlew :cli:run --args="compile --input $SPIKE_DIR/js/dist --output $SPIKE_DIR/js/ziplineOut"

cd js/ziplineOut && python -m http.server 8080 &
cd ../..
./gradlew :host:run   # uses the old __drainTasks() polling loop's code path
```

## Real module: HeadlessCarrier/PouchDbFacade against the real stack (2026-08-10)

**Status: blocked on an apparent Zipline/QuickJS bug, not something fixable from this
module.** The real (non-spike) `docstack-headless/engine/` module was built: a
`com.android.kotlin.multiplatform.library` + `kotlin("multiplatform")` + `app.cash.zipline`
module with `androidTarget()` (host side, wired to the real `StorageDispatcher` via
`RealHeadlessCarrier`) and `js()` (guest side, `Guest.kt`), plus `engine/js-bundle/` —
a new esbuild setup bundling the *real* `pouchdb-core` + `@docstack/pouchdb-adapter-native`
(not the `pouchdb-adapter-memory` stand-in above). All of this compiles and links
correctly: `EngineIntegrationTest` (`androidHostTest`, plain JVM, no emulator) boots a
real `Zipline` via `ZiplineLoader`, binds `RealHeadlessCarrier` over a real
`InMemoryDocumentStore`, takes `PouchDbFacade`, and calls `facade.put(...)`.

The **first** outbound call the real adapter's `_bulkDocs` makes through
`globalThis.__docstackHost` (`getRevTrees`) reaches the host, gets processed by the real
`StorageDispatcher`, and returns successfully — confirmed via host-side `println` in
`RealHeadlessCarrier.dispatch` (the only diagnostic channel that survives the failure;
see below). The **second** chained call (`bulkWrite`, needed to actually persist the new
document) never arrives at the host at all — no exception, no rejection, just a
`TimeoutCancellationException` after `withTimeout(8_000)` in the test.

### Bisection trail

Exhaustively narrowed down over many iterations, each confirmed via a `diagX`-style
marker `id` visible (or not) in `RealHeadlessCarrier.dispatch`'s `println`s:

1. **Not the real adapter's own logic.** Reproduced with a synthetic plain-JS function
   making two sequential trivial `info` calls through `globalThis.__docstackHost` —
   `pouchdb-adapter-native`/`pouchdb-merge` code is not involved at all.
2. **Not a coroutine-dispatcher issue.** Reproduces identically regardless of how the
   Kotlin side of `dispatchFn` is implemented: `GlobalScope.promise{}`,
   `GlobalScope.promise(start = CoroutineStart.UNDISPATCHED)`, a raw `Continuation` on
   `EmptyCoroutineContext` (bypassing `Dispatchers.Default`/`kotlinx-coroutines-core`
   entirely), and a persistent single coroutine draining a `Channel` (started via
   `startCoroutine`, not `GlobalScope.launch{}` — see next point).
3. **`GlobalScope.launch{}`'s own start needs `Dispatchers.Default.dispatch()` to fire,
   and that never happens in this environment** — a `GlobalScope.launch{}` body never
   even begins executing here, regardless of what's inside it. `kotlin.coroutines.startCoroutine`
   on a raw `Continuation`, by contrast, always reliably starts (it runs synchronously up
   to the first suspension point, no dispatcher needed). This is a real, separate finding
   worth remembering for any future Zipline/JS work in this environment, but is not
   itself the cause of the main bug (see next point).
4. `kotlinx-coroutines-core`'s JS `Dispatchers.Default` resolves to `NodeDispatcher`
   (`process.nextTick`-based) whenever a `process` global with a `nextTick` function is
   present — our `js-bundle/entry.js` `process` shim had one (routed through
   `setTimeout` for `pouchdb-core`'s benefit). Fixed to route through
   `Promise.resolve().then()` instead (a real microtask) — confirmed via reading
   `kotlinx-coroutines-core.js`'s actual compiled output
   (`createDefaultDispatcher()`/`NodeDispatcher`/`SetTimeoutDispatcher`). **This was a
   real, independent bug, now fixed, but did not fix the main issue either** — it's now
   moot per point 2 above, since raw-`Continuation` dispatch (which needs no
   `Dispatchers.Default` at all) reproduces identically.
5. `delay(50)` chained twice in a row, purely in Kotlin (no JS boundary), **succeeds
   both times** — so `GlobalBridge`'s real cross-boundary `setTimeout` does handle
   repeat scheduling correctly. Rules out "any scheduled/deferred resumption is broken"
   as too broad a theory.
6. **The actual trigger: a second call to the JS-exposed `dispatchFn` closure, chained
   off the first call's own resolution (via `await` or `.then()`, micro- or macrotask,
   doesn't matter), never reaches the host.** But:
   - Two calls fired **concurrently** (neither awaiting the other before both start)
     **both succeed**.
   - Pure Kotlin code calling `carrier.dispatch()` repeatedly, sequentially, from an
     already-running coroutine **always succeeds** (3+ calls confirmed, with real
     `delay()`s between them).
   - It is specifically: JS calls the exported closure → gets a promise → chains more
     work off its resolution → that chained work calls the closure again → the second
     call is lost. Reproduces via `await`, via `.then()`, via a `.then()` deliberately
     deferred through a real `setTimeout(0)` macrotask (ruling out a microtask-nesting
     theory), and via the real adapter's actual `_bulkDocs` code path.
   - The rejection handler on the outer promise never fires either (checked explicitly
     by wiring it to report through the working host-`println` channel) — it's a
     genuine silent hang, not a swallowed synchronous exception.
   - Read Zipline's own `OutboundCallHandler.kt` source directly (`cashapp/zipline` tag
     `1.27.0`): outbound suspend calls use a single mutable `endpoint.callCodec.lastInboundCall!!`
     when a call's success/failure callback fires — a plausible-looking but unconfirmed
     candidate given it's a shared mutable field read with a forced non-null assertion,
     but no direct evidence (no exception ever surfaces) ties it to this specific
     symptom.
7. **Not fixable by version.** Reproduces identically on Zipline 1.25.0 (Kotlin 2.3.0)
   and 1.27.0 (Kotlin 2.3.20) — not a recent regression between those two releases.
8. **Not fixable by brute-force "always keep something in flight."** A background
   keep-alive loop (`while (true) { carrier.dispatch(...) }`, no delay) was tried to
   test whether the trigger is really "nothing else concurrently in flight" — this was
   actively dangerous: it monopolized the single-threaded JVM test executor so
   thoroughly that even `withTimeout(8_000)` never fired, and the JVM test worker had
   to be killed manually (`taskkill`/`Stop-Process`) after the fact. **Do not repeat
   this experiment without a real backoff/delay between iterations.**

### Current state

`engine/`'s production code (`RealHeadlessCarrier.kt`, `Guest.kt`, `PouchDbFacade.kt`,
`HeadlessCarrier.kt`) is left in its cleanest working form: the persistent-coroutine/
`Channel`-draining `dispatchFn` from point 3/6 above (not because it fixes the bug — it
doesn't — but because it's the most defensible implementation among everything tried,
and matches the one call shape empirically proven to always work for calls Kotlin
itself initiates). `EngineIntegrationTest` is left in place and **currently fails** —
its doc comment says so explicitly. `RealHeadlessCarrier.dispatch`'s two `println`
statements are kept deliberately (the only diagnostic channel that survives the hang;
a second `QuickJs.evaluate()` call after the timeout reliably throws
`QuickJsException: stack overflow`, so nothing else can be read back from the guest
once this happens).

### Next steps if this is picked back up

- File a minimal repro against `cashapp/zipline` (GitHub issue) — the synthetic
  two-sequential-calls repro from bisection step 1 is close to minimal already; would
  need trimming to a plain Zipline sample app (no `docstack-store`/PouchDB involved) to
  be a good upstream bug report. Not filed yet — needs a maintainer's own GitHub
  account/repo, not something done from this session.
- Worth reading `CallCodec.kt`, `Endpoint.kt`, and `InboundService.kt` in full (only
  `OutboundCallHandler.kt` was read end to end) to actually confirm or rule out the
  `lastInboundCall` theory from bisection step 6, rather than leaving it as an
  unconfirmed lead.
- The `EngineApi`-gated `Zipline.quickJs` escape hatch might allow reading Zipline's
  *own* internal event-listener/debug hooks (an `EventListener` override passed to
  `Zipline.create`) for more visibility into what happens between "host prints
  `returning`" and "the second call never arrives" — not tried.

## Reproducing (real module)

```bash
cd android/docstack-headless

# 1. Build the engine module's own Kotlin/JS Zipline output.
./gradlew :engine:compileProductionExecutableKotlinJsZipline

# 2. Rebuild the real pouchdb-core + @docstack/pouchdb-adapter-native esbuild bundle
#    and compile it to Zipline bytecode via the spike's :cli module (same tool, works
#    fine pointed at a different repo's paths - it's a generic zipline-cli wrapper).
cd engine/js-bundle && npm run bundle && cd ../..
cd spike
rm -rf "$(pwd)/../engine/js-bundle/ziplineOut"
mkdir -p "../engine/js-bundle/ziplineOut"
./gradlew :cli:run --args="compile --input E:/repos/docstack/android/docstack-headless/engine/js-bundle/dist --output E:/repos/docstack/android/docstack-headless/engine/js-bundle/ziplineOut"
cd ..

# 3. Merge engine's own manifest with js-bundle's into engine/combined/.
cd engine && python merge-manifest.py && cd ..

# 4. Run the (currently failing) integration test.
./gradlew :engine:testAndroidHostTest --tests "*EngineIntegrationTest*"
```

`merge-manifest.py` (`engine/merge-manifest.py`) is the reusable version of the
inline Python script from the original manifest-merge technique below — same idea,
just pointed at `engine/`'s paths instead of `spike/`'s.

## Reproducing (task 2 continuation - real event-loop bridge)

```bash
cd android/docstack-headless/spike
SPIKE_DIR=E:/repos/docstack/android/docstack-headless/spike

# 1. Build the Kotlin/JS bootstrap module (real Zipline.get()/GlobalBridge).
./gradlew :bootstrap:compileProductionExecutableKotlinJsZipline

# 2. Rebuild the esbuild pouchdb bundle and compile it to Zipline bytecode.
cd js && npm run bundle && cd ..
rm -rf js/ziplineOut && mkdir -p js/ziplineOut
./gradlew :cli:run --args="compile --input $SPIKE_DIR/js/dist --output $SPIKE_DIR/js/ziplineOut"

# 3. Combine both module sets into one directory + one hand-merged manifest (see
#    combined/manifest.zipline.json - the pouchdb module's entry has dependsOnIds
#    pointing at the bootstrap module; mainModuleId/mainFunction point at bootstrap).
rm -rf combined && mkdir -p combined
cp bootstrap/build/zipline/Production/*.zipline combined/
cp js/ziplineOut/spike.zipline combined/
# manifest.zipline.json in combined/ is currently hand-authored - regenerate its
# sha256 fields from the two source manifests if either input changes.

cd combined && python -m http.server 8080 &
cd ..
./gradlew :host:run   # no __drainTasks() call anywhere - real progress, host only polls to observe it
```
