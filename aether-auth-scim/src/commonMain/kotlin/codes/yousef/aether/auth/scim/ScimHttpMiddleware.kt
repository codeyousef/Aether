package codes.yousef.aether.auth.scim

import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.ScimOperationId
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.Headers
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.RequestConnection
import codes.yousef.aether.core.pipeline.Middleware
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val SCIM_HTTP_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")

/** Safe identity established for a SCIM client. Authentication material is never retained here. */
class ScimClientPrincipal(val subject: String) {
    init { require(subject.isNotBlank() && subject.length <= 255) { "SCIM client subject must be bounded" } }

    override fun equals(other: Any?): Boolean = other is ScimClientPrincipal && subject == other.subject
    override fun hashCode(): Int = subject.hashCode()
    override fun toString(): String = "ScimClientPrincipal(subject=<redacted>)"
}

/** Immutable request metadata presented to the injected authenticator before the body is read. */
class ScimAuthenticationRequest(
    val method: HttpMethod,
    val path: String,
    val headers: Headers,
    val connection: RequestConnection
) {
    override fun toString(): String =
        "ScimAuthenticationRequest(method=$method, path=$path, headers=<redacted>, connection=$connection)"
}

sealed interface ScimAuthenticationResult {
    data class Authenticated(val principal: ScimClientPrincipal) : ScimAuthenticationResult
    data object Rejected : ScimAuthenticationResult
    data object Unavailable : ScimAuthenticationResult
}

fun interface ScimAuthenticator {
    suspend fun authenticate(request: ScimAuthenticationRequest): ScimAuthenticationResult
}

enum class ScimAuthorizationDecision { ALLOW, DENY, UNAVAILABLE }

fun interface ScimTenantAuthorizer {
    suspend fun authorize(
        principal: ScimClientPrincipal,
        organizationId: OrganizationId
    ): ScimAuthorizationDecision
}

fun interface ScimRequestHandler {
    suspend fun handle(request: ScimRequest): ScimResponse
}

data class ScimHttpMiddlewareConfig(
    val organizationId: OrganizationId,
    val maximumBodyBytes: Int = 1_048_576,
    val operationIdHeader: String = "Idempotency-Key",
    val requestIdHeader: String = "X-Request-ID"
) {
    init {
        require(maximumBodyBytes in 1_024..4_194_304) { "SCIM body limit must be 1 KiB..4 MiB" }
        require(SCIM_HTTP_HEADER_NAME.matches(operationIdHeader)) { "Invalid SCIM operation-ID header" }
        require(SCIM_HTTP_HEADER_NAME.matches(requestIdHeader)) { "Invalid SCIM request-ID header" }
        require(!operationIdHeader.equals(requestIdHeader, ignoreCase = true)) {
            "SCIM operation-ID and request-ID headers must differ"
        }
    }
}

/**
 * Framework-neutral `/scim/v2` transport adapter.
 *
 * Authentication and tenant authorization are mandatory constructor dependencies. They run before
 * request-body I/O. Unknown non-SCIM paths fall through; every path under `/scim/v2` is handled and
 * never reaches the application pipeline unauthenticated.
 */
class ScimHttpMiddleware internal constructor(
    private val handler: ScimRequestHandler,
    private val authenticator: ScimAuthenticator,
    private val authorizer: ScimTenantAuthorizer,
    private val config: ScimHttpMiddlewareConfig
) {
    constructor(
        engine: ScimEngine,
        authenticator: ScimAuthenticator,
        authorizer: ScimTenantAuthorizer,
        config: ScimHttpMiddlewareConfig
    ) : this(ScimRequestHandler(engine::handle), authenticator, authorizer, config) {
        require(engine.configuredOrganizationId == config.organizationId) {
            "SCIM middleware and engine must use the same organization"
        }
    }

    fun asMiddleware(): Middleware = middleware@{ exchange, next ->
        if (!isScimPath(exchange.request.path)) {
            next()
            return@middleware
        }

        try {
            if (exchange.request.headers.getAll("Authorization").size > 1) {
                respondError(exchange, 400, ScimErrorType.INVALID_SYNTAX, "The SCIM request is invalid.")
                return@middleware
            }
            val authentication = authenticator.authenticate(
                ScimAuthenticationRequest(
                    method = exchange.request.method,
                    path = exchange.request.path,
                    headers = exchange.request.headers,
                    connection = exchange.request.connection
                )
            )
            val principal = when (authentication) {
                is ScimAuthenticationResult.Authenticated -> authentication.principal
                ScimAuthenticationResult.Rejected -> {
                    respondError(exchange, 401, null, "SCIM client authentication is required.")
                    return@middleware
                }
                ScimAuthenticationResult.Unavailable -> {
                    unavailable(exchange)
                    return@middleware
                }
            }
            when (authorizer.authorize(principal, config.organizationId)) {
                ScimAuthorizationDecision.ALLOW -> Unit
                ScimAuthorizationDecision.DENY -> {
                    respondError(exchange, 403, null, "The SCIM client is not authorized for this tenant.")
                    return@middleware
                }
                ScimAuthorizationDecision.UNAVAILABLE -> {
                    unavailable(exchange)
                    return@middleware
                }
            }

            val mapped = mapRequest(exchange)
            if (mapped == null) return@middleware
            writeResponse(exchange, handler.handle(mapped))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            unavailable(exchange)
        }
    }

    private suspend fun mapRequest(exchange: Exchange): ScimRequest? = try {
        mapRequestValidated(exchange)
    } catch (_: InvalidTransportRequest) {
        respondError(exchange, 400, ScimErrorType.INVALID_SYNTAX, "The SCIM request is invalid.")
        null
    }

    private suspend fun mapRequestValidated(exchange: Exchange): ScimRequest? {
        if (exchange.request.path.length > 2_048 || '?' in exchange.request.path || '#' in exchange.request.path) {
            respondError(exchange, 400, ScimErrorType.INVALID_SYNTAX, "The SCIM request is invalid.")
            return null
        }
        val method = when (exchange.request.method) {
            HttpMethod.GET -> ScimHttpMethod.GET
            HttpMethod.POST -> ScimHttpMethod.POST
            HttpMethod.PUT -> ScimHttpMethod.PUT
            HttpMethod.PATCH -> ScimHttpMethod.PATCH
            HttpMethod.DELETE -> ScimHttpMethod.DELETE
            else -> {
                respondError(exchange, 405, null, "The SCIM method is not supported.")
                return null
            }
        }
        val requestId = singleHeader(exchange, config.requestIdHeader)
        if (requestId != null && !SAFE_REQUEST_ID.matches(requestId)) {
            respondError(exchange, 400, ScimErrorType.INVALID_SYNTAX, "The SCIM request is invalid.")
            return null
        }
        val operationValue = singleHeader(exchange, config.operationIdHeader)
        val mutating = method != ScimHttpMethod.GET
        if (mutating && operationValue == null) {
            respondError(exchange, 400, ScimErrorType.INVALID_VALUE, "A stable operation ID is required.", requestId)
            return null
        }
        val operationId = operationValue?.let {
            ScimOperationId.parseOrNull(it) ?: run {
                respondError(exchange, 400, ScimErrorType.INVALID_VALUE, "The SCIM operation ID is invalid.", requestId)
                return null
            }
        }

        val ifMatch = singleHeader(exchange, "If-Match")
        val ifNoneMatch = singleHeader(exchange, "If-None-Match")
        val userAgent = singleHeader(exchange, "User-Agent")
        val contentType = singleHeader(exchange, "Content-Type")
        val declaredLength = singleHeader(exchange, "Content-Length")
        if ((method == ScimHttpMethod.GET && ifMatch != null) ||
            (method != ScimHttpMethod.GET && ifNoneMatch != null) ||
            (method == ScimHttpMethod.POST && ifMatch != null)
        ) {
            respondError(exchange, 400, ScimErrorType.INVALID_VALUE, "The SCIM conditional headers are invalid.", requestId)
            return null
        }
        val needsJsonBody = method == ScimHttpMethod.POST || method == ScimHttpMethod.PUT || method == ScimHttpMethod.PATCH
        if (needsJsonBody && !isScimJsonContentType(contentType)) {
            respondError(exchange, 415, ScimErrorType.INVALID_SYNTAX, "SCIM requests require application/scim+json.", requestId)
            return null
        }
        val length = declaredLength?.let { value ->
            if (value.isEmpty() || value.any { !it.isDigit() }) {
                respondError(exchange, 400, ScimErrorType.INVALID_SYNTAX, "The SCIM request is invalid.", requestId)
                return null
            }
            value.toLongOrNull() ?: run {
                respondError(exchange, 413, ScimErrorType.TOO_MANY, "The SCIM request is too large.", requestId)
                return null
            }
        }
        if (length != null && length > config.maximumBodyBytes) {
            respondError(exchange, 413, ScimErrorType.TOO_MANY, "The SCIM request is too large.", requestId)
            return null
        }
        val body = exchange.request.bodyBytes()
        if (body.size > config.maximumBodyBytes) {
            respondError(exchange, 413, ScimErrorType.TOO_MANY, "The SCIM request is too large.", requestId)
            return null
        }
        if (length != null && length != body.size.toLong()) {
            respondError(exchange, 400, ScimErrorType.INVALID_SYNTAX, "The SCIM request is invalid.", requestId)
            return null
        }
        if (!needsJsonBody && body.isNotEmpty()) {
            respondError(exchange, 400, ScimErrorType.INVALID_SYNTAX, "The SCIM request body is invalid.", requestId)
            return null
        }
        val query = try {
            parseQuery(exchange.request.query)
        } catch (_: IllegalArgumentException) {
            respondError(exchange, 400, ScimErrorType.INVALID_VALUE, "The SCIM query is invalid.", requestId)
            return null
        }
        val headers = buildMap {
            ifMatch?.let { put("If-Match", it) }
            ifNoneMatch?.let { put("If-None-Match", it) }
            userAgent?.let { put("User-Agent", it) }
        }
        return ScimRequest(
            method = method,
            path = exchange.request.path,
            query = query,
            headers = headers,
            body = body,
            operationId = operationId,
            requestId = requestId
        )
    }

    private fun singleHeader(exchange: Exchange, name: String): String? {
        val values = exchange.request.headers.getAll(name)
        if (values.size > 1) throw InvalidTransportRequest()
        val value = values.singleOrNull()
        if (value?.let { it.length > 8_192 || it.any(::isUnsafeHeaderCharacter) } == true) {
            throw InvalidTransportRequest()
        }
        return value
    }

    private suspend fun writeResponse(exchange: Exchange, response: ScimResponse) {
        exchange.response.statusCode = response.status
        response.headers.forEach { (name, value) -> exchange.response.setHeader(name, value) }
        val body = response.bodyBytes()
        if (body.isNotEmpty()) exchange.response.write(body)
        exchange.response.end()
    }

    private suspend fun unavailable(exchange: Exchange) {
        respondError(exchange, 503, null, "The SCIM service is unavailable.")
    }

    private suspend fun respondError(
        exchange: Exchange,
        status: Int,
        type: ScimErrorType?,
        detail: String,
        requestId: String? = null
    ) {
        val body = ERROR_JSON.encodeToString(
            ScimErrorResponse(
                status = status.toString(),
                scimType = type?.wireName,
                detail = detail
            )
        ).encodeToByteArray()
        exchange.response.statusCode = status
        exchange.response.setHeader("Content-Type", "application/scim+json")
        requestId?.let { exchange.response.setHeader(config.requestIdHeader, it) }
        exchange.response.write(body)
        exchange.response.end()
    }

    private fun isScimPath(path: String): Boolean = path == SCIM_BASE || path.startsWith("$SCIM_BASE/")

    private fun isScimJsonContentType(value: String?): Boolean {
        if (value == null) return false
        val parts = value.split(';').map(String::trim)
        if (!parts.first().equals("application/scim+json", ignoreCase = true)) return false
        return parts.drop(1).all { it.equals("charset=utf-8", ignoreCase = true) }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        require(raw.length <= MAXIMUM_QUERY_BYTES)
        val result = linkedMapOf<String, String>()
        raw.split('&').forEach { field ->
            require(field.isNotEmpty())
            val separator = field.indexOf('=')
            require(separator > 0)
            val name = decodeQueryComponent(field.substring(0, separator))
            val value = decodeQueryComponent(field.substring(separator + 1))
            require(name.isNotBlank() && name.length <= 100 && value.length <= 2_048)
            require(name.none(::isUnsafeQueryCharacter) && value.none(::isUnsafeQueryCharacter))
            require(result.put(name, value) == null) { "Duplicate SCIM query parameter" }
        }
        return result
    }

    private fun decodeQueryComponent(value: String): String {
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < value.length) {
            when (val character = value[index]) {
                '%' -> {
                    require(index + 2 < value.length)
                    val high = value[index + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException()
                    val low = value[index + 2].digitToIntOrNull(16) ?: throw IllegalArgumentException()
                    bytes += ((high shl 4) or low).toByte()
                    index += 3
                }
                '+' -> {
                    bytes += ' '.code.toByte()
                    index += 1
                }
                else -> {
                    require(character.code !in 0xD800..0xDFFF)
                    bytes += character.toString().encodeToByteArray().toList()
                    index += 1
                }
            }
        }
        return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private companion object {
        const val SCIM_BASE = "/scim/v2"
        const val MAXIMUM_QUERY_BYTES = 8_192
        val SAFE_REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,254}")
        val ERROR_JSON = Json { encodeDefaults = true; explicitNulls = false }

        fun isUnsafeHeaderCharacter(character: Char): Boolean = character.code < 0x20 || character.code == 0x7F
        fun isUnsafeQueryCharacter(character: Char): Boolean = character.code < 0x20 || character.code == 0x7F
    }

    private class InvalidTransportRequest : IllegalArgumentException()
}
