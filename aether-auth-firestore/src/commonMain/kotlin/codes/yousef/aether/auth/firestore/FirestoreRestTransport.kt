package codes.yousef.aether.auth.firestore

import codes.yousef.aether.auth.IdentityHttpMethod
import codes.yousef.aether.auth.IdentityHttpRequest
import codes.yousef.aether.auth.IdentityHttpResponse
import codes.yousef.aether.auth.IdentityRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Internal document transport, implemented by the Firestore v1 REST API on every KMP target. */
internal interface FirestoreDocumentTransport {
    suspend fun get(documentName: String): FirestoreDocument?
    suspend fun beginTransaction(): String
    suspend fun batchGet(documentNames: List<String>, transaction: String): Map<String, FirestoreDocument?>
    suspend fun runQuery(parent: String, query: FirestoreStructuredQuery, transaction: String? = null): List<FirestoreDocument>
    suspend fun commit(transaction: String?, writes: List<FirestoreWrite>): CommitResponse
    suspend fun rollback(transaction: String)
}

/** Firestore v1 REST transport using injected HTTP and refreshable OAuth capabilities. */
internal class FirestoreRestTransport(
    private val config: FirestoreIdentityConfig,
    private val runtime: IdentityRuntime,
    private val accessTokens: FirestoreAccessTokenProvider,
    private val json: Json = defaultFirestoreJson()
) : FirestoreDocumentTransport {
    init {
        val emulator = config.normalizedApiBaseUrl.startsWith("http://")
        require(emulator || accessTokens !== FirestoreEmulatorAccessTokenProvider) {
            "The no-auth Firestore token provider is allowed only for a loopback emulator"
        }
    }

    override suspend fun get(documentName: String): FirestoreDocument? {
        val response = request(IdentityHttpMethod.GET, documentUrl(documentName), null, allowNotFound = true)
            ?: return null
        return decode(response)
    }

    override suspend fun beginTransaction(): String {
        val response = request(
            IdentityHttpMethod.POST,
            "${config.documentsRoot}:beginTransaction",
            json.encodeToString(BeginTransactionRequest()).encodeToByteArray()
        ) ?: throw FirestoreStoreException(FirestoreFailureMapper.internal())
        return decode<BeginTransactionResponse>(response).transaction.also(::requireTransactionToken)
    }

    override suspend fun batchGet(
        documentNames: List<String>,
        transaction: String
    ): Map<String, FirestoreDocument?> {
        if (documentNames.isEmpty()) return emptyMap()
        requireTransactionToken(transaction)
        val response = request(
            IdentityHttpMethod.POST,
            "${config.documentsRoot}:batchGet",
            json.encodeToString(BatchGetDocumentsRequest(documentNames, transaction)).encodeToByteArray()
        ) ?: throw FirestoreStoreException(FirestoreFailureMapper.internal())
        val decoded = decodeStream<BatchGetDocumentsResponse>(response)
        val result = documentNames.associateWith { null as FirestoreDocument? }.toMutableMap()
        decoded.forEach { item ->
            item.found?.let { result[it.name] = it }
            item.missing?.let { result[it] = null }
        }
        return result
    }

    override suspend fun runQuery(
        parent: String,
        query: FirestoreStructuredQuery,
        transaction: String?
    ): List<FirestoreDocument> {
        transaction?.let(::requireTransactionToken)
        val response = request(
            IdentityHttpMethod.POST,
            "${documentUrl(parent)}:runQuery",
            json.encodeToString(RunQueryRequest(query, transaction)).encodeToByteArray()
        ) ?: throw FirestoreStoreException(FirestoreFailureMapper.internal())
        return decodeStream<RunQueryResponse>(response).mapNotNull { it.document }
    }

    override suspend fun commit(transaction: String?, writes: List<FirestoreWrite>): CommitResponse {
        require(writes.isNotEmpty()) { "A Firestore commit requires at least one write" }
        transaction?.let(::requireTransactionToken)
        val response = request(
            IdentityHttpMethod.POST,
            "${config.documentsRoot}:commit",
            json.encodeToString(CommitRequest(writes, transaction)).encodeToByteArray()
        ) ?: throw FirestoreStoreException(FirestoreFailureMapper.internal())
        return decode(response)
    }

    override suspend fun rollback(transaction: String) {
        requireTransactionToken(transaction)
        runCatching {
            request(
                IdentityHttpMethod.POST,
                "${config.documentsRoot}:rollback",
                json.encodeToString(RollbackRequest(transaction)).encodeToByteArray()
            )
        }
    }

    private suspend fun request(
        method: IdentityHttpMethod,
        url: String,
        body: ByteArray?,
        allowNotFound: Boolean = false
    ): ByteArray? {
        if (body != null && body.size > config.maximumRequestBytes) {
            throw FirestoreStoreException(FirestoreFailureMapper.internal())
        }
        var attempt = execute(method, url, body)
        if (attempt.authenticated && attempt.isAuthenticationFailure()) {
            try {
                accessTokens.invalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw FirestoreStoreException(FirestoreFailureMapper.unavailable())
            }
            attempt = execute(method, url, body)
        }
        val response = attempt.response
        val responseBody = attempt.body
        if (allowNotFound && response.statusCode == 404) return null
        if (response.statusCode !in 200..299) {
            val status = attempt.providerStatus()
            val safe = FirestoreFailureMapper.fromProvider(status, response.statusCode)
            throw FirestoreStoreException(
                safeError = safe,
                transactionRetryable = status == "ABORTED" || status == "UNAVAILABLE" || status == "DEADLINE_EXCEEDED"
            )
        }
        return responseBody
    }

    private suspend fun execute(
        method: IdentityHttpMethod,
        url: String,
        body: ByteArray?
    ): HttpAttempt {
        val headers = mutableMapOf("Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json"
        val token = try {
            accessTokens.accessToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw FirestoreStoreException(FirestoreFailureMapper.unavailable())
        }
        token?.let { headers["Authorization"] = it.authorizationHeader() }
        val response: IdentityHttpResponse = try {
            runtime.http.execute(IdentityHttpRequest(method, url, headers, body ?: ByteArray(0)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw FirestoreStoreException(FirestoreFailureMapper.unavailable())
        } finally {
            headers.remove("Authorization")
        }
        val responseBody = response.bodyBytes()
        if (responseBody.size > config.maximumResponseBytes) {
            throw FirestoreStoreException(FirestoreFailureMapper.internal())
        }
        return HttpAttempt(response, responseBody, authenticated = token != null)
    }

    private fun HttpAttempt.providerStatus(): String? =
        runCatching { decode<FirestoreErrorEnvelope>(body).error?.status }.getOrNull()

    private fun HttpAttempt.isAuthenticationFailure(): Boolean =
        response.statusCode == 401 || providerStatus()?.equals("UNAUTHENTICATED", ignoreCase = true) == true

    private inline fun <reified T> decode(bytes: ByteArray): T = try {
        json.decodeFromString(bytes.decodeToString())
    } catch (_: SerializationException) {
        throw FirestoreStoreException(FirestoreFailureMapper.internal())
    } catch (_: IllegalArgumentException) {
        throw FirestoreStoreException(FirestoreFailureMapper.internal())
    }

    private inline fun <reified T> decodeStream(bytes: ByteArray): List<T> = try {
        val text = bytes.decodeToString().trim()
        if (text.isEmpty()) return emptyList()
        val element = json.parseToJsonElement(text)
        when (element) {
            is JsonArray -> element.map { json.decodeFromJsonElement(it) }
            is JsonObject -> listOf(json.decodeFromJsonElement(element))
            else -> throw SerializationException("Unexpected Firestore stream shape")
        }
    } catch (_: SerializationException) {
        // Some Google API proxies expose server-streamed responses as newline-delimited objects.
        try {
            bytes.decodeToString().lineSequence().filter { it.isNotBlank() }
                .map { json.decodeFromString<T>(it) }.toList()
        } catch (_: Throwable) {
            throw FirestoreStoreException(FirestoreFailureMapper.internal())
        }
    } catch (_: IllegalArgumentException) {
        throw FirestoreStoreException(FirestoreFailureMapper.internal())
    }

    private fun documentUrl(documentName: String): String {
        require(documentName.startsWith("projects/${config.projectId}/databases/${config.databaseId}/documents/")) {
            "Firestore document belongs to a different project or database"
        }
        return "${config.normalizedApiBaseUrl}/$documentName"
    }

    private data class HttpAttempt(
        val response: IdentityHttpResponse,
        val body: ByteArray,
        val authenticated: Boolean
    )
}

internal fun defaultFirestoreJson(): Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
    allowStructuredMapKeys = false
}

private fun requireTransactionToken(value: String) {
    require(value.isNotBlank() && value.length <= 16_384 && value.none(Char::isWhitespace)) {
        "Invalid Firestore transaction token"
    }
}
