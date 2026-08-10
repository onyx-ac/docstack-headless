// Guest side (QuickJS): takes the real HeadlessCarrier the host bound, exposes it to
// the separately esbuild-compiled `@docstack/pouchdb-adapter-native` bundle
// (js-bundle/) as `globalThis.__docstackHost`, then binds the real PouchDbFacade
// implementation - a thin wrapper over an actual PouchDB instance running the real
// native adapter. See android/specs/04-docstack-headless.md "Kotlin API surface."
package ac.onyx.docstack.headless

import app.cash.zipline.Zipline
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.js.JSON
import kotlin.js.Promise

@OptIn(DelicateCoroutinesApi::class, ExperimentalJsExport::class)
@JsExport
fun launchZipline() {
    // Referencing Zipline.get() installs GlobalBridge's real setTimeout/console (see
    // docstack-headless/SPIKE-NOTES.md "Task 2 continuation") before anything below -
    // including js-bundle's own require()'d pouchdb-core, loaded earlier in the
    // manifest's module order - runs real async work.
    val zipline = Zipline.get()
    val carrier = zipline.take<HeadlessCarrier>("carrier")

    installDocstackHost(carrier)

    val pouchDBCtor = js("globalThis.__createPouchDB(globalThis.__docstackHost)")
    zipline.bind<PouchDbFacade>("pouchdb", RealPouchDbFacade(pouchDBCtor))
}

private class PendingDispatch(val requestJson: String, val resolve: (dynamic) -> Unit, val reject: (dynamic) -> Unit)

/**
 * `Carrier` (`permetic-web/src/index.d.ts`) is a callable object with an attached
 * `subscribeChanges` property, not a plain interface - `__docstackHost(req)` for the
 * envelope, `__docstackHost.subscribeChanges(db, since, listener)` for the live-push
 * carve-out.
 *
 * A JS caller that awaits `__docstackHost(req)` twice in a row (exactly
 * `pouchdb-adapter-native`'s own `_bulkDocs` shape) only ever gets its FIRST call to
 * reach the host if each call starts a fresh coroutine - bisected extensively (see
 * SPIKE-NOTES.md "Real module" section): reproduces identically regardless of
 * coroutine-dispatcher choice (`GlobalScope.promise{}`, `UNDISPATCHED`, a raw
 * `Continuation` on `EmptyCoroutineContext`), regardless of `await` vs `.then()`
 * chaining, and regardless of micro- vs macrotask deferral. It reproduces ONLY when
 * the second call is causally chained off the first call's own resolution; two calls
 * fired concurrently (neither waiting on the other) both succeed, and pure Kotlin code
 * calling `carrier.dispatch()` repeatedly from an already-running coroutine always
 * succeeds too. So instead of starting a fresh coroutine per JS-initiated call, ONE
 * persistent coroutine (started here, via `startCoroutine` - `GlobalScope.launch{}`'s
 * own start needs `Dispatchers.Default.dispatch()` to fire, which never happens in
 * this environment) drains a channel and issues every dispatch itself, sequentially,
 * from the SAME long-lived coroutine - matching the one call shape proven to always
 * work. `dispatchFn` just enqueues and returns a `Promise` that this loop settles.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun installDocstackHost(carrier: HeadlessCarrier) {
    val pending = Channel<PendingDispatch>(Channel.UNLIMITED)

    val consumerCont = object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {}
    }
    val consumerBlock: suspend () -> Unit = {
        for (item in pending) {
            try {
                val resultJson = carrier.dispatch(item.requestJson)
                item.resolve(JSON.parse<dynamic>(resultJson))
            } catch (e: Throwable) {
                item.reject(e)
            }
        }
    }
    consumerBlock.startCoroutine(consumerCont)

    val dispatchFn: (dynamic) -> Promise<dynamic> = { req ->
        Promise { resolve, reject ->
            pending.trySend(PendingDispatch(JSON.stringify(req), resolve, reject))
        }
    }
    val subscribeChangesFn: (String, dynamic, (dynamic) -> Unit) -> (() -> Unit) = { db, since, listener ->
        val job = GlobalScope.launch {
            carrier.subscribeChanges(db, (since as Double).toLong()).collect { docJson ->
                listener(JSON.parse<dynamic>(docJson))
            }
        }
        val unsubscribe: () -> Unit = { job.cancel() }
        unsubscribe
    }
    js("globalThis.__docstackHost = dispatchFn; globalThis.__docstackHost.subscribeChanges = subscribeChangesFn;")
}

/**
 * Guest-side [PouchDbFacade]: a thin wrapper over a real `PouchDB` instance (opened
 * lazily per `db` name, cached) running the real `@docstack/pouchdb-adapter-native`
 * adapter that `js-bundle/entry.js`'s `globalThis.__createPouchDB` already registered.
 * Never touches a revision tree itself - ADR-0001 still holds.
 */
private class RealPouchDbFacade(private val pouchDBCtor: dynamic) : PouchDbFacade {
    private val openDbs = mutableMapOf<String, dynamic>()

    private fun openDb(name: String): dynamic = openDbs.getOrPut(name) {
        // js() only substitutes local bindings by name, not implicit `this` member
        // access (same reason GlobalBridge.kt captures `val globalBridge = this`
        // before its own js() block) - pouchDBCtor is a constructor property, so it
        // needs a local alias here.
        val ctor = pouchDBCtor
        js("new ctor(name, { adapter: 'native' })")
    }

    override suspend fun get(db: String, id: String, optionsJson: String): String {
        val opts = JSON.parse<dynamic>(optionsJson)
        val doc = openDb(db).get(id, opts).unsafeCast<Promise<dynamic>>().await()
        return JSON.stringify(doc)
    }

    override suspend fun put(db: String, docJson: String): String {
        val doc = JSON.parse<dynamic>(docJson)
        val result = openDb(db).put(doc).unsafeCast<Promise<dynamic>>().await()
        return JSON.stringify(result)
    }

    override suspend fun bulkDocs(db: String, docsJson: String): String {
        val docs = JSON.parse<dynamic>(docsJson)
        val results = openDb(db).bulkDocs(docs).unsafeCast<Promise<dynamic>>().await()
        return JSON.stringify(results)
    }

    override suspend fun query(db: String, optionsJson: String): String {
        val opts = JSON.parse<dynamic>(optionsJson)
        val result = openDb(db).allDocs(opts).unsafeCast<Promise<dynamic>>().await()
        return JSON.stringify(result)
    }

    override fun changes(db: String, optionsJson: String): Flow<String> = callbackFlow {
        val opts = JSON.parse<dynamic>(optionsJson)
        opts.live = true
        val onChange: (dynamic) -> Unit = { change -> trySend(JSON.stringify(change)) }
        val changesFeed = openDb(db).changes(opts)
        changesFeed.on("change", onChange)
        awaitClose { changesFeed.cancel() }
    }

    override fun close() {}
}
