package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.IdentityHttpMethod
import codes.yousef.aether.auth.IdentityHttpRequest
import codes.yousef.aether.auth.IdentityRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun interface PostgresqlRpcTransport {
    suspend fun execute(request: PostgresqlRpcRequestEnvelope): PostgresqlRpcResponseEnvelope
}

/** All-target PostgREST transport backed by the application's injected identity HTTP capability. */
class PostgrestPostgresqlRpcTransport(
    private val config: PostgresqlIdentityConfig,
    private val runtime: IdentityRuntime,
    private val json: Json = defaultPostgresqlJson()
) : PostgresqlRpcTransport {
    init {
        require(config.normalizedPostgrestBaseUrl != null) { "PostgREST transport requires a base URL" }
    }

    override suspend fun execute(request: PostgresqlRpcRequestEnvelope): PostgresqlRpcResponseEnvelope {
        val operation = operationFor(request)
        val encoded = json.encodeToString(PostgrestFunctionRequest(request)).encodeToByteArray()
        if (encoded.size > config.maximumRequestBytes) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
        }

        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Accept-Profile" to config.schema,
            "Content-Profile" to config.schema
        )
        config.postgrestAuthorizationSecret?.let { reference ->
            headers["Authorization"] = try {
                runtime.secrets.resolve(reference).useBytes(::bearerAuthorizationValue)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PostgresqlStoreException) {
                throw failure
            } catch (_: Throwable) {
                throw PostgresqlStoreException(PostgresqlFailureMapper.unavailable())
            }
        }

        val response = try {
            runtime.http.execute(
                IdentityHttpRequest(
                    method = IdentityHttpMethod.POST,
                    url = "${config.normalizedPostgrestBaseUrl}/rpc/${operation.functionName}",
                    headers = headers,
                    body = encoded
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.unavailable())
        } finally {
            headers.remove("Authorization")
        }

        val body = response.bodyBytes()
        if (body.size > config.maximumResponseBytes) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
        }
        if (response.statusCode !in 200..299) {
            val providerCode = runCatching {
                json.decodeFromString<PostgrestErrorDocument>(body.decodeToString()).code
            }.getOrNull()
            throw PostgresqlStoreException(
                PostgresqlFailureMapper.fromProviderCode(providerCode, response.statusCode)
            )
        }

        val decoded = try {
            json.decodeFromString<PostgresqlRpcResponseEnvelope>(body.decodeToString())
        } catch (_: SerializationException) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
        } catch (_: IllegalArgumentException) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
        }
        validateResponse(request, decoded)
        return decoded
    }
}

internal fun operationFor(request: PostgresqlRpcRequestEnvelope): PostgresqlRpcOperation =
    PostgresqlRpcOperation.entries.singleOrNull { it.wireName == request.operation }
        ?: throw PostgresqlStoreException(PostgresqlFailureMapper.internal())

internal fun validateResponse(
    request: PostgresqlRpcRequestEnvelope,
    response: PostgresqlRpcResponseEnvelope
) {
    if (response.protocolVersion != request.protocolVersion || response.operation != request.operation) {
        throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
    }
}

internal fun defaultPostgresqlJson(): Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
    allowStructuredMapKeys = false
}

private fun bearerAuthorizationValue(secret: ByteArray): String {
    if (secret.isEmpty() || secret.size > MAXIMUM_BEARER_TOKEN_BYTES || secret.any { !it.isBearerTokenByte() }) {
        throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
    }
    return "Bearer ${secret.decodeToString()}"
}

private fun Byte.isBearerTokenByte(): Boolean {
    val character = toInt() and 0xff
    return character in 'A'.code..'Z'.code ||
        character in 'a'.code..'z'.code ||
        character in '0'.code..'9'.code ||
        character == '-'.code || character == '.'.code || character == '_'.code ||
        character == '~'.code || character == '+'.code || character == '/'.code || character == '='.code
}

private const val MAXIMUM_BEARER_TOKEN_BYTES: Int = 8 * 1_024
