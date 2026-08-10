package ac.onyx.docstack.headless

import ac.onyx.docstack.store.InMemoryDocumentStore
import app.cash.zipline.Zipline
import app.cash.zipline.loader.DefaultFreshnessCheckerNotFresh
import app.cash.zipline.loader.LoadResult
import app.cash.zipline.loader.ManifestVerifier.Companion.NO_SIGNATURE_CHECKS
import app.cash.zipline.loader.ZiplineLoader
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * End-to-end proof this module's whole point exists to establish: a `put`/`get` round
 * trip through the REAL stack - Kotlin host -> Zipline -> the real `PouchDB` instance
 * -> the real `@docstack/pouchdb-adapter-native` -> `globalThis.__docstackHost` ->
 * [HeadlessCarrier] -> the real `StorageDispatcher` -> [InMemoryDocumentStore] and
 * back - not a memory-adapter stand-in (the spike) and not a self-consistency check
 * within PouchDB alone. Runs on the plain JVM (`androidHostTest`), no emulator - same
 * "nothing here touches Android APIs" reasoning `InMemoryDocumentStoreTest` already
 * established for [InMemoryDocumentStore] itself.
 *
 * Requires `engine/combined/` to exist: built from `engine/build/zipline/Production/`
 * (this module's own Kotlin/JS output) plus `engine/js-bundle/`'s separately
 * `zipline-cli`-compiled `bundle.zipline`, merged the same way
 * `docstack-headless/SPIKE-NOTES.md`'s "Reproducing (task 2 continuation)" documents.
 *
 * KNOWN FAILING as of this writing - see SPIKE-NOTES.md "Real module" section for the
 * full bisection. `getRevTrees` (the first outbound call `_bulkDocs` makes through
 * `globalThis.__docstackHost`) succeeds; the second chained call (`bulkWrite`) never
 * reaches the host, and the test times out. Reproduces identically across every
 * coroutine/dispatcher strategy tried and across Zipline 1.25.0/1.27.0 - looks like a
 * genuine Zipline/QuickJS bug in outbound suspend-call handling, not something fixable
 * from this module alone.
 */
public class EngineIntegrationTest {

    private lateinit var httpServer: HttpServer

    @Before
    fun startFileServer() {
        val combinedDir = listOf(File("combined"), File("engine/combined"))
            .firstOrNull { it.isDirectory }
            ?: error(
                "engine/combined/ not found - build it first: compile the engine module's " +
                    "production Zipline output, bundle+compile js-bundle/, and merge the two " +
                    "manifests (see docstack-headless/SPIKE-NOTES.md).",
            )

        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.executor = Executors.newCachedThreadPool()
        httpServer.createContext("/") { exchange ->
            val requestedPath = exchange.requestURI.path.removePrefix("/")
            val file = File(combinedDir, requestedPath)
            if (file.isFile) {
                val bytes = file.readBytes()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(404, 0)
                exchange.responseBody.close()
            }
        }
        httpServer.start()
    }

    @After
    fun stopFileServer() {
        httpServer.stop(0)
    }

    @Test
    fun `put then get round-trips through the real stack, and lands in the real DocumentStore`() {
        val store = InMemoryDocumentStore()
        val executorService = Executors.newFixedThreadPool(1) { Thread(it, "Zipline") }
        val dispatcher = executorService.asCoroutineDispatcher()

        try {
            runBlocking(dispatcher) {
                withTimeout(8_000) {
                    val manifestUrl = "http://127.0.0.1:${httpServer.address.port}/manifest.zipline.json"
                    val loader = ZiplineLoader(dispatcher, NO_SIGNATURE_CHECKS, OkHttpClient())

                    println("TRACE: loading manifest")
                    val result = loader.loadOnce(
                        applicationName = "docstack-headless-engine-test",
                        freshnessChecker = DefaultFreshnessCheckerNotFresh,
                        manifestUrl = manifestUrl,
                        initializer = { zipline: Zipline ->
                            println("TRACE: initializer - binding HeadlessCarrier")
                            zipline.bind<HeadlessCarrier>("carrier", RealHeadlessCarrier(store))
                        },
                    )
                    println("TRACE: load complete: $result")

                    val success = result as? LoadResult.Success ?: error("load failed: $result")
                    val facade = success.zipline.take<PouchDbFacade>("pouchdb")
                    println("TRACE: took PouchDbFacade, calling put")

                    val putResultJson = facade.put("testdb", """{"_id":"doc1","hello":"world"}""")
                    println("TRACE: put returned: $putResultJson")
                    val putResult = Json.parseToJsonElement(putResultJson).jsonObject
                    assertEquals("doc1", putResult.getValue("id").jsonPrimitive.content)
                    val putRev = putResult.getValue("rev").jsonPrimitive.content

                    println("TRACE: calling get")
                    val getResultJson = facade.get("testdb", "doc1", "{}")
                    println("TRACE: get returned: $getResultJson")
                    val doc = Json.parseToJsonElement(getResultJson).jsonObject
                    assertEquals("world", doc.getValue("hello").jsonPrimitive.content)
                    assertEquals(putRev, doc.getValue("_rev").jsonPrimitive.content)

                    // Independent proof: reading directly from the SAME InMemoryDocumentStore
                    // instance the carrier delegated to (not through Zipline/PouchDB at all)
                    // confirms this is really wired end to end, not just self-consistent
                    // within PouchDB's own view of the world.
                    val direct = store.getDoc("testdb", "doc1", null)
                    assertNotNull(direct.body)
                    assertEquals("world", direct.body?.get("hello"))
                    assertEquals(putRev, direct.rev)

                    success.zipline.close()
                }
            }
        } finally {
            executorService.shutdown()
        }
    }
}
