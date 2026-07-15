package codes.yousef.aether.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable, non-sensitive error codes shared by middleware and wire clients. */
@Serializable
enum class IdentityErrorCode(
    val wireName: String,
    val httpStatus: Int,
    val publicMessage: String,
    val retryable: Boolean = false
) {
    @SerialName("authentication_required")
    AUTHENTICATION_REQUIRED("authentication_required", 401, "Authentication is required"),

    @SerialName("invalid_credentials")
    INVALID_CREDENTIALS("invalid_credentials", 401, "Authentication failed"),

    @SerialName("session_expired")
    SESSION_EXPIRED("session_expired", 401, "The session has expired"),

    @SerialName("session_revoked")
    SESSION_REVOKED("session_revoked", 401, "The session is no longer valid"),

    @SerialName("organization_required")
    ORGANIZATION_REQUIRED("organization_required", 403, "An organization scope is required"),

    @SerialName("wrong_organization")
    WRONG_ORGANIZATION("wrong_organization", 403, "Access to this organization is denied"),

    @SerialName("insufficient_role")
    INSUFFICIENT_ROLE("insufficient_role", 403, "The required organization role is missing"),

    @SerialName("insufficient_capability")
    INSUFFICIENT_CAPABILITY("insufficient_capability", 403, "The required capability is missing"),

    @SerialName("csrf_required")
    CSRF_REQUIRED("csrf_required", 403, "CSRF validation is required"),

    @SerialName("csrf_invalid")
    CSRF_INVALID("csrf_invalid", 403, "CSRF validation failed"),

    @SerialName("step_up_required")
    STEP_UP_REQUIRED("step_up_required", 403, "Recent passkey verification is required"),

    @SerialName("registration_not_allowed")
    REGISTRATION_NOT_ALLOWED("registration_not_allowed", 403, "Registration is not available"),

    @SerialName("challenge_invalid")
    CHALLENGE_INVALID("challenge_invalid", 400, "The challenge is invalid or expired"),

    @SerialName("request_invalid")
    REQUEST_INVALID("request_invalid", 400, "The request is invalid"),

    @SerialName("not_found")
    NOT_FOUND("not_found", 404, "The requested identity resource was not found"),

    @SerialName("conflict")
    CONFLICT("conflict", 409, "The identity resource changed; retry the operation", retryable = true),

    @SerialName("rate_limited")
    RATE_LIMITED("rate_limited", 429, "Too many attempts; retry later", retryable = true),

    @SerialName("service_unavailable")
    SERVICE_UNAVAILABLE("service_unavailable", 503, "Identity service is temporarily unavailable", retryable = true),

    @SerialName("internal_error")
    INTERNAL_ERROR("internal_error", 500, "Identity operation failed")
}

/**
 * Safe wire error. [message] is fixed by [code], preventing exception messages, tokens, digests,
 * provider responses, or database text from being copied into an HTTP response.
 */
@Serializable
data class IdentityErrorDto(
    val code: IdentityErrorCode,
    val message: String,
    val requestId: String? = null,
    val retryable: Boolean
) {
    init {
        require(message == code.publicMessage) { "Identity wire error messages are fixed by code" }
        require(retryable == code.retryable) { "Identity wire error retryability is fixed by code" }
        require(requestId == null || (requestId.isNotBlank() && requestId.length <= 255)) {
            "Invalid error request ID"
        }
    }

    constructor(code: IdentityErrorCode, requestId: String? = null) : this(
        code = code,
        message = code.publicMessage,
        requestId = requestId,
        retryable = code.retryable
    )
}

@Serializable
data class IdentityErrorEnvelope(
    val error: IdentityErrorDto
)
