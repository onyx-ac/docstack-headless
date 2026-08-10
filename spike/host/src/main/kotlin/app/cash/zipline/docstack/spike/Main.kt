// Boot spike only: does the pouchdb-core + adapter bundle run inside QuickJS via
// Zipline, with no WebView and no Android runtime involved? Not production code.
package app.cash.zipline.docstack.spike

import app.cash.zipline.EngineApi
import app.cash.zipline.loader.DefaultFreshnessCheckerNotFresh
import app.cash.zipline.loader.LoadResult
import app.cash.zipline.loader.ManifestVerifier.Companion.NO_SIGNATURE_CHECKS
import app.cash.zipline.loader.ZiplineLoader
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

@OptIn(EngineApi::class)
fun main() {
  val executorService = Executors.newFixedThreadPool(1) { Thread(it, "Zipline") }
  val dispatcher = executorService.asCoroutineDispatcher()

  runBlocking(dispatcher) {
    val manifestUrl = "http://localhost:8080/manifest.zipline.json"
    val loader = ZiplineLoader(dispatcher, NO_SIGNATURE_CHECKS, OkHttpClient())

    when (
      val result = loader.loadOnce("spike", DefaultFreshnessCheckerNotFresh, manifestUrl)
    ) {
      is LoadResult.Success -> {
        println("SPIKE: module loaded and evaluated without a load-time exception.")
        val quickJs = result.zipline.quickJs

        // No host-side drain loop anymore. The manifest's mainFunction
        // (bootstrap/Bootstrap.kt's launchZipline) already ran as part of loading:
        // it called the real Zipline.get() (installing GlobalBridge's real
        // setTimeout/console, wired to the host's own CoroutineEventLoop) and then
        // invoked entry.js's deferred globalThis.__runPouchTest(). Progress now
        // happens on its own via the dispatcher thread processing real scheduled
        // coroutine jobs - this loop only polls to observe when it's done, it does
        // not drive it.
        var value: Any?
        var attempts = 0
        do {
          value = quickJs.evaluate("globalThis.__SPIKE_RESULT__", "readResult.js")
          if (attempts < 10 || attempts % 25 == 0) {
            println("  [attempt $attempts] result=$value")
          }
          if (value != "SPIKE_PENDING") break
          delay(20)
          attempts++
        } while (attempts < 250) // ~5s ceiling

        val bootstrapTrace = quickJs.evaluate("JSON.stringify(globalThis.__BOOTSTRAP_TRACE__)", "bootstrapTrace.js")
        val spikeTrace = quickJs.evaluate("JSON.stringify(globalThis.__SPIKE_TRACE__)", "spikeTrace.js")
        println("BOOTSTRAP_TRACE: $bootstrapTrace")
        println("SPIKE_TRACE: $spikeTrace")
        println("SPIKE_RESULT: $value (after $attempts read attempts, no drain calls)")
      }
      is LoadResult.Failure -> {
        println("SPIKE: load failed")
        result.exception.printStackTrace()
      }
    }
  }

  exitProcess(0)
}
