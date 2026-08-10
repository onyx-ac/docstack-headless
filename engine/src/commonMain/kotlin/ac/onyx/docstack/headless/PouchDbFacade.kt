package ac.onyx.docstack.headless

import app.cash.zipline.ZiplineService
import kotlinx.coroutines.flow.Flow

/**
 * The CRUD surface for apps with no WebView at all (spec 04 task 7, primary-carrier
 * mode). Guest binds, host takes - opposite direction from [HeadlessCarrier], since the
 * app's ViewModels are the caller here. A thin wrapper: the guest-side implementation
 * calls the real `PouchDB` instance's own `get`/`put`/`bulkDocs`/`allDocs`/`changes`
 * (itself running the real `@docstack/pouchdb-adapter-native`, which in turn calls
 * [HeadlessCarrier]) - it never touches a revision tree itself, so ADR-0001 still holds.
 *
 * All payloads are JSON strings, same reasoning as [HeadlessCarrier]: PouchDB doc
 * bodies are arbitrary JSON, not typed Kotlin data classes, and nothing here needs to
 * compile domain types for both `androidMain` and `jsMain`.
 */
public interface PouchDbFacade : ZiplineService {
    public suspend fun get(db: String, id: String, optionsJson: String): String
    public suspend fun put(db: String, docJson: String): String
    public suspend fun bulkDocs(db: String, docsJson: String): String
    public suspend fun query(db: String, optionsJson: String): String
    public fun changes(db: String, optionsJson: String): Flow<String>
}
