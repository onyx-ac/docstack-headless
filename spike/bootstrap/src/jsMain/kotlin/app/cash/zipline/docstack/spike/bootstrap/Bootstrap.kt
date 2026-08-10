// Boot spike continuation, not production code (see build.gradle.kts).
//
// The only thing this proves: does calling the real Zipline.get() (from a genuine
// Kotlin/JS-compiled module built with the app.cash.zipline Gradle plugin) actually
// install a working globalThis.setTimeout/console/event-loop - the GlobalBridge.kt
// mechanism confirmed by reading zipline's own jsMain source - as opposed to the
// original spike's host-side polling shim. Writes progress markers to
// globalThis.__BOOTSTRAP_TRACE__/__BOOTSTRAP_READY__ so the host can read them back
// via QuickJs.evaluate(), the same observability approach entry.js already uses for
// __SPIKE_TRACE__/__SPIKE_RESULT__.
package app.cash.zipline.docstack.spike.bootstrap

import app.cash.zipline.Zipline
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun trace(message: String) {
    js("globalThis.__BOOTSTRAP_TRACE__.push(message)")
}

@OptIn(DelicateCoroutinesApi::class, ExperimentalJsExport::class)
@JsExport
fun launchZipline() {
    js("globalThis.__BOOTSTRAP_TRACE__ = [];")
    trace("launchZipline-start")

    // Referencing Zipline.get() for the first time in this JS runtime triggers
    // GlobalBridge's init{} block (zipline/src/jsMain/kotlin/app/cash/zipline/GlobalBridge.kt),
    // which installs globalThis.setTimeout/clearTimeout/console for real, wired through
    // zipline.host (an RPC proxy) to the HOST-side CoroutineEventLoop. Nothing else in
    // this function depends on the returned instance.
    Zipline.get()
    trace("zipline-get-done typeof-setTimeout=" + js("typeof globalThis.setTimeout") + " typeof-console=" + js("typeof globalThis.console"))

    // Exercise the real event loop: delay() only resolves if GlobalBridge's setTimeout
    // is actually wired to a live host-side CoroutineEventLoop, not a stub.
    GlobalScope.launch {
        trace("before-delay")
        delay(30)
        trace("after-delay")
        js("globalThis.__BOOTSTRAP_READY__ = true;")

        // entry.js (the separately esbuild-compiled pouchdb module, loaded before this
        // mainFunction runs) only defines globalThis.__runPouchTest - it never calls it
        // at module-load time, since setTimeout/console weren't real yet at that point.
        // Real ones are installed now (Zipline.get() above), so it's safe to run for real.
        trace("invoking-runPouchTest typeof=" + js("typeof globalThis.__runPouchTest"))
        js("if (typeof globalThis.__runPouchTest === 'function') { globalThis.__runPouchTest(); }")
    }
}
