package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.IdentityStoreError
import codes.yousef.aether.auth.IdentityStoreErrorCode

internal class PostgresqlStoreException(
    val safeError: IdentityStoreError
) : RuntimeException("PostgreSQL identity operation failed") {
    override fun toString(): String =
        "PostgresqlStoreException(code=${safeError.code}, retryable=${safeError.retryable})"
}

internal object PostgresqlFailureMapper {
    fun fromProviderCode(providerCode: String?, httpStatus: Int? = null): IdentityStoreError {
        val normalized = providerCode?.trim()?.uppercase()
        return when {
            normalized == "A0001" -> failure(IdentityStoreErrorCode.INTERNAL)
            normalized == "A0002" -> failure(IdentityStoreErrorCode.VERSION_CONFLICT, retryable = true)
            normalized == "A0003" -> failure(IdentityStoreErrorCode.INVALID_TRANSITION)
            normalized == "A0004" -> failure(IdentityStoreErrorCode.LAST_OWNER)
            normalized == "A0005" -> failure(IdentityStoreErrorCode.REPLAY_DETECTED)
            normalized == "A0006" -> failure(IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT)
            normalized == "A0007" -> failure(IdentityStoreErrorCode.CHALLENGE_NOT_PENDING)
            normalized == "A0008" -> failure(IdentityStoreErrorCode.CHALLENGE_EXPIRED)
            normalized == "A0009" -> failure(IdentityStoreErrorCode.SESSION_NOT_ACTIVE)
            normalized == "A0010" -> failure(IdentityStoreErrorCode.SESSION_EXPIRED)
            normalized == "A0011" -> failure(IdentityStoreErrorCode.RECOVERY_CODE_NOT_ACTIVE)
            normalized == "A0012" -> failure(IdentityStoreErrorCode.NOT_FOUND)
            normalized == "A0013" -> failure(IdentityStoreErrorCode.ALREADY_EXISTS)
            normalized == "A0014" -> failure(IdentityStoreErrorCode.INTERNAL)
            normalized == "A0015" -> failure(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED)
            normalized == "23505" -> failure(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            normalized == "23503" || normalized == "23514" || normalized == "22P02" ->
                failure(IdentityStoreErrorCode.INVALID_TRANSITION)
            normalized == "40001" || normalized == "40P01" ->
                failure(IdentityStoreErrorCode.VERSION_CONFLICT, retryable = true)
            normalized?.startsWith("08") == true || normalized == "53300" || normalized == "57P01" ||
                normalized == "57P02" || normalized == "57P03" || normalized == "PGRST000" ||
                normalized == "PGRST001" || normalized == "PGRST002" ->
                failure(IdentityStoreErrorCode.UNAVAILABLE, retryable = true)
            normalized == "PGRST116" -> failure(IdentityStoreErrorCode.NOT_FOUND)
            normalized == "42501" || normalized == "42P01" || normalized == "42883" || normalized == "PGRST202" ->
                failure(IdentityStoreErrorCode.INTERNAL)
            httpStatus == 404 -> failure(IdentityStoreErrorCode.INTERNAL)
            httpStatus == 409 -> failure(IdentityStoreErrorCode.VERSION_CONFLICT, retryable = true)
            httpStatus == 429 || (httpStatus != null && httpStatus >= 500) ->
                failure(IdentityStoreErrorCode.UNAVAILABLE, retryable = true)
            httpStatus != null && httpStatus in 400..499 -> failure(IdentityStoreErrorCode.INTERNAL)
            else -> failure(IdentityStoreErrorCode.INTERNAL)
        }
    }

    fun unavailable(): IdentityStoreError = failure(IdentityStoreErrorCode.UNAVAILABLE, retryable = true)

    fun internal(): IdentityStoreError = failure(IdentityStoreErrorCode.INTERNAL)

    private fun failure(code: IdentityStoreErrorCode, retryable: Boolean = false): IdentityStoreError =
        IdentityStoreError(code, retryable)
}
