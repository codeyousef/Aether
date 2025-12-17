package codes.yousef.aether.db.wasm

import codes.yousef.aether.db.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wasm implementation of DatabaseDriver that uses HTTP fetch() to communicate with a backend.
 * Serializes QueryAST to JSON and sends to a backend endpoint.
 *
 * Note: This is a stub implementation for WasmJs. In a real application,
 * you would need to implement proper JS interop using @JsModule or external declarations.
 */
class HttpDriver(
    private val endpoint: String
) : DatabaseDriver {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun executeQuery(query: QueryAST): List<Row> {
        val request = QueryRequest(
            query = query,
            operation = "query"
        )

        val response = sendRequest(request)

        return response.rows.map { HttpRow(it) }
    }

    override suspend fun executeUpdate(query: QueryAST): Int {
        val request = QueryRequest(
            query = query,
            operation = "update"
        )

        val response = sendRequest(request)

        return response.affectedRows
    }

    override suspend fun executeDDL(query: QueryAST) {
        val request = QueryRequest(
            query = query,
            operation = "ddl"
        )

        sendRequest(request)
    }

    override suspend fun close() {
        // HTTP driver doesn't maintain persistent connections
    }

    private suspend fun sendRequest(request: QueryRequest): QueryResponse {
        return suspendCancellableCoroutine { continuation ->
            try {
                val requestBody = json.encodeToString(request)

                // Create a callback that will be called from JS
                val callback: (String?, String?) -> Unit = { responseText, errorMessage ->
                    if (errorMessage != null) {
                        continuation.resumeWithException(
                            DatabaseException("Failed to execute HTTP database request: $errorMessage")
                        )
                    } else {
                        try {
                            val response = json.decodeFromString<QueryResponse>(responseText ?: "{}")
                            continuation.resume(response)
                        } catch (e: Exception) {
                            continuation.resumeWithException(
                                DatabaseException("Failed to parse response", e)
                            )
                        }
                    }
                }

                // Call the JS fetch function
                performFetch(endpoint, requestBody, callback)
            } catch (e: Exception) {
                continuation.resumeWithException(
                    DatabaseException("Failed to prepare HTTP request", e)
                )
            }
        }
    }

    companion object {
        /**
         * Creates an HttpDriver with the default endpoint.
         */
        fun create(endpoint: String = "/api/db"): HttpDriver {
            return HttpDriver(endpoint)
        }
    }
}

/**
 * External function to perform fetch operation.
 * This would be implemented in JavaScript and exposed to Wasm.
 */
@JsFun("(url, body, callback) => { " +
    "fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body })" +
    ".then(response => response.text())" +
    ".then(text => callback(text, null))" +
    ".catch(error => callback(null, error.toString())); " +
    "}")
private external fun performFetch(url: String, body: String, callback: (String?, String?) -> Unit)

/**
 * HTTP request payload containing the QueryAST and operation type.
 */
@Serializable
private data class QueryRequest(
    val query: QueryAST,
    val operation: String
)

/**
 * HTTP response payload containing the query results.
 */
@Serializable
private data class QueryResponse(
    val rows: List<Map<String, String?>> = emptyList(),
    val affectedRows: Int = 0,
    val error: String? = null
)

/**
 * Implementation of Row interface for HTTP-based queries.
 */
class HttpRow(
    private val data: Map<String, String?>
) : Row {

    override fun getString(column: String): String? {
        return data[column]
    }

    override fun getInt(column: String): Int? {
        return data[column]?.toIntOrNull()
    }

    override fun getLong(column: String): Long? {
        return data[column]?.toLongOrNull()
    }

    override fun getDouble(column: String): Double? {
        return data[column]?.toDoubleOrNull()
    }

    override fun getBoolean(column: String): Boolean? {
        return when (data[column]?.lowercase()) {
            "true", "t", "1" -> true
            "false", "f", "0" -> false
            else -> null
        }
    }

    override fun getValue(column: String): Any? {
        return data[column]
    }

    override fun getColumnNames(): List<String> {
        return data.keys.toList()
    }

    override fun hasColumn(column: String): Boolean {
        return data.containsKey(column)
    }
}
