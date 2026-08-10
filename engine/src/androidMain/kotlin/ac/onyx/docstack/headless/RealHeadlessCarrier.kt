package ac.onyx.docstack.headless

import ac.onyx.docstack.store.DocumentStore
import ac.onyx.docstack.store.StoredDoc
import ac.onyx.docstack.store.dispatcher.BridgeRequest
import ac.onyx.docstack.store.dispatcher.BridgeResponseSerializer
import ac.onyx.docstack.store.dispatcher.StorageDispatcher
import ac.onyx.docstack.store.toJsonElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Host-side [HeadlessCarrier]: delegates to the real [StorageDispatcher], unmodified
 * from the WebView carrier's own copy (ADR-0002 - "this class has no carrier branch in
 * it"). Bound via `Zipline.take`/`bind` on the host, taken guest-side and exposed as
 * `globalThis.__docstackHost` (see `Guest.kt`, jsMain).
 */
public class RealHeadlessCarrier(store: DocumentStore) : HeadlessCarrier {
    private val dispatcher = StorageDispatcher(store)

    override suspend fun dispatch(requestJson: String): String {
        // Left in deliberately: the only reliable diagnostic channel found while
        // bisecting the known blocker in SPIKE-NOTES.md ("Real module" section) - a
        // second JS-chained outbound call through this method never arrives. Plain
        // JVM stdout, unlike a second QuickJs.evaluate() call, survives the hang.
        println("TRACE: RealHeadlessCarrier.dispatch received: $requestJson")
        val request = Json.decodeFromString(BridgeRequest.serializer(), requestJson)
        val response = dispatcher.dispatch(request)
        val responseJson = Json.encodeToString(BridgeResponseSerializer, response)
        println("TRACE: RealHeadlessCarrier.dispatch returning: $responseJson")
        return responseJson
    }

    override fun subscribeChanges(db: String, since: Long): Flow<String> =
        dispatcher.subscribeChanges(db, since).map { doc -> encodeStoredDoc(doc).toString() }

    override fun close() {}
}

/**
 * Mirrors `StorageDispatcher.kt`'s own private `encodeStoredDoc` field-for-field -
 * `subscribeChanges` doesn't go through [StorageDispatcher.dispatch], so it needs its
 * own copy of the same encoding (that helper isn't visible outside its file).
 */
private fun encodeStoredDoc(doc: StoredDoc): JsonObject = JsonObject(
    mapOf(
        "id" to JsonPrimitive(doc.id),
        "rev" to JsonPrimitive(doc.rev),
        "seq" to JsonPrimitive(doc.seq),
        "deleted" to JsonPrimitive(doc.deleted),
        "body" to doc.body.toJsonElement(),
        "conflicts" to doc.conflicts.toJsonElement(),
    ),
)
