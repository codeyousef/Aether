package codes.yousef.aether.auth.firestore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class FirestoreValue(
    val nullValue: String? = null,
    val booleanValue: Boolean? = null,
    val integerValue: String? = null,
    val doubleValue: Double? = null,
    val timestampValue: String? = null,
    val stringValue: String? = null,
    val bytesValue: String? = null,
    val referenceValue: String? = null,
    val arrayValue: FirestoreArrayValue? = null,
    val mapValue: FirestoreMapValue? = null
)

@Serializable
internal data class FirestoreArrayValue(val values: List<FirestoreValue> = emptyList())

@Serializable
internal data class FirestoreMapValue(val fields: Map<String, FirestoreValue> = emptyMap())

@Serializable
internal data class FirestoreDocument(
    val name: String,
    val fields: Map<String, FirestoreValue>,
    val createTime: String? = null,
    val updateTime: String? = null
)

@Serializable
internal data class FirestorePrecondition(
    val exists: Boolean? = null,
    val updateTime: String? = null
) {
    init { require((exists == null) != (updateTime == null)) { "A Firestore precondition requires exactly one condition" } }
}

@Serializable
internal data class FirestoreWrite(
    val update: FirestoreDocument? = null,
    val delete: String? = null,
    val currentDocument: FirestorePrecondition? = null
) {
    init { require((update == null) != (delete == null)) { "A Firestore write requires exactly one operation" } }
}

@Serializable
internal data class BeginTransactionRequest(
    val options: FirestoreTransactionOptions = FirestoreTransactionOptions()
)

@Serializable
internal data class FirestoreTransactionOptions(
    val readWrite: FirestoreReadWriteOptions = FirestoreReadWriteOptions()
)

@Serializable
internal class FirestoreReadWriteOptions

@Serializable
internal data class BeginTransactionResponse(val transaction: String)

@Serializable
internal data class BatchGetDocumentsRequest(
    val documents: List<String>,
    val transaction: String? = null
)

@Serializable
internal data class BatchGetDocumentsResponse(
    val found: FirestoreDocument? = null,
    val missing: String? = null,
    val readTime: String? = null,
    val transaction: String? = null
)

@Serializable
internal data class RunQueryRequest(
    val structuredQuery: FirestoreStructuredQuery,
    val transaction: String? = null
)

@Serializable
internal data class RunQueryResponse(
    val document: FirestoreDocument? = null,
    val readTime: String? = null,
    val skippedResults: Int? = null,
    // The official emulator terminates an empty REST stream with this protobuf JSON field.
    val done: Boolean? = null
)

@Serializable
internal data class FirestoreStructuredQuery(
    val from: List<FirestoreCollectionSelector>,
    val where: FirestoreFilter? = null,
    val orderBy: List<FirestoreOrder> = emptyList(),
    val startAt: FirestoreCursor? = null,
    val limit: Int? = null
)

@Serializable
internal data class FirestoreCursor(
    val values: List<FirestoreValue>,
    val before: Boolean = false
)

@Serializable
internal data class FirestoreCollectionSelector(
    val collectionId: String,
    val allDescendants: Boolean = false
)

@Serializable
internal data class FirestoreFilter(
    val fieldFilter: FirestoreFieldFilter? = null,
    val compositeFilter: FirestoreCompositeFilter? = null
)

@Serializable
internal data class FirestoreCompositeFilter(
    val op: String,
    val filters: List<FirestoreFilter>
)

@Serializable
internal data class FirestoreFieldFilter(
    val field: FirestoreFieldReference,
    val op: String,
    val value: FirestoreValue
)

@Serializable
internal data class FirestoreFieldReference(val fieldPath: String)

@Serializable
internal data class FirestoreOrder(
    val field: FirestoreFieldReference,
    val direction: String
)

@Serializable
internal data class CommitRequest(
    val writes: List<FirestoreWrite>,
    val transaction: String? = null
)

@Serializable
internal data class CommitResponse(
    val writeResults: List<FirestoreWriteResult> = emptyList(),
    val commitTime: String? = null
)

@Serializable
internal data class FirestoreWriteResult(
    val updateTime: String? = null,
    val transformResults: List<FirestoreValue> = emptyList()
)

@Serializable
internal data class RollbackRequest(val transaction: String)

@Serializable
internal data class FirestoreErrorEnvelope(val error: FirestoreProviderError? = null)

@Serializable
internal data class FirestoreProviderError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
    val details: List<JsonElement> = emptyList()
)

internal fun stringValue(value: String): FirestoreValue = FirestoreValue(stringValue = value)
internal fun timestampValue(value: String): FirestoreValue = FirestoreValue(timestampValue = value)
internal fun integerValue(value: Long): FirestoreValue = FirestoreValue(integerValue = value.toString())
internal fun booleanValue(value: Boolean): FirestoreValue = FirestoreValue(booleanValue = value)
