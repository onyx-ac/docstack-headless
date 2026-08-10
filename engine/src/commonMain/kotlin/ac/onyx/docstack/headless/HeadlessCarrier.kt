package ac.onyx.docstack.headless

import app.cash.zipline.ZiplineService
import kotlinx.coroutines.flow.Flow

/**
 * Satisfies ADR-0002's "Headless: one bound Zipline suspending function." Host binds
 * (wraps the real `StorageDispatcher`, `docstack-store`), guest takes and exposes as
 * `globalThis.__docstackHost` for the real `@docstack/pouchdb-adapter-native` bundle to
 * call - see spec 03's "Carrier injection" and spec 04's "Kotlin API surface."
 *
 * `requestJson`/the return value are JSON-encoded `BridgeRequest`/`BridgeResponse`
 * (`docstack-store`'s `Envelope.kt`), not typed Kotlin DTOs - those types aren't
 * compiled for a JS target, and `docstack-store` shouldn't become multiplatform just
 * for this. `String` crosses the Zipline boundary with no custom serializer needed;
 * each side decodes/encodes using its own copy of the real JSON shape.
 */
public interface HeadlessCarrier : ZiplineService {
    public suspend fun dispatch(requestJson: String): String

    /** One JSON-encoded `StoredDoc` per emission, same reasoning as [dispatch]. */
    public fun subscribeChanges(db: String, since: Long): Flow<String>
}
