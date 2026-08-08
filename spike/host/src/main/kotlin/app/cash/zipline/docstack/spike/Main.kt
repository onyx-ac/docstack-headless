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

        // put()/get() are deferred through entry.js's own setTimeout queue (see
        // entry.js - Zipline's real event-loop bridge isn't available to a raw JS
        // module). Drive it from the host until the result settles or we time out.
        var value: Any?
        var attempts = 0
        do {
          val drained = quickJs.evaluate("globalThis.__drainTasks && globalThis.__drainTasks()", "drain.js")
          value = quickJs.evaluate("globalThis.__SPIKE_RESULT__", "readResult.js")
          if (attempts < 10 || attempts % 25 == 0) {
            println("  [attempt $attempts] drained=$drained result=$value")
          }
          if (value != "SPIKE_PENDING") break
          delay(20)
          attempts++
        } while (attempts < 250) // ~5s ceiling

        val trace = quickJs.evaluate("JSON.stringify(globalThis.__SPIKE_TRACE__)", "trace.js")
        println("SPIKE_TRACE: $trace")
        println("SPIKE_RESULT: $value (after $attempts drain attempts)")
      }
      is LoadResult.Failure -> {
        println("SPIKE: load failed")
        result.exception.printStackTrace()
      }
    }
  }

  exitProcess(0)
}
