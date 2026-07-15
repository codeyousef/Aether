package codes.yousef.aether.auth

/** Public service result that never carries provider, storage, or exception details. */
sealed interface IdentityOperationResult<out T> {
    data class Success<T>(val value: T) : IdentityOperationResult<T>
    data class Failure(val code: IdentityErrorCode) : IdentityOperationResult<Nothing>

    fun valueOrNull(): T? = (this as? Success<T>)?.value
}

internal fun IdentityStoreError.toIdentityErrorCode(): IdentityErrorCode = when (code) {
    IdentityStoreErrorCode.NOT_FOUND,
    IdentityStoreErrorCode.CHALLENGE_NOT_PENDING,
    IdentityStoreErrorCode.CHALLENGE_EXPIRED -> IdentityErrorCode.CHALLENGE_INVALID
    IdentityStoreErrorCode.ALREADY_EXISTS,
    IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
    IdentityStoreErrorCode.INVALID_TRANSITION,
    IdentityStoreErrorCode.RECOVERY_CODE_NOT_ACTIVE,
    IdentityStoreErrorCode.SESSION_NOT_ACTIVE,
    IdentityStoreErrorCode.SESSION_EXPIRED,
    IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
    IdentityStoreErrorCode.LAST_OWNER,
    IdentityStoreErrorCode.REPLAY_DETECTED,
    IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT -> IdentityErrorCode.INVALID_CREDENTIALS
    IdentityStoreErrorCode.VERSION_CONFLICT -> IdentityErrorCode.CONFLICT
    IdentityStoreErrorCode.UNAVAILABLE,
    IdentityStoreErrorCode.INTERNAL -> IdentityErrorCode.SERVICE_UNAVAILABLE
}

internal fun <T> StoreResult<T>.toOperationResult(): IdentityOperationResult<T> = when (this) {
    is StoreResult.Success -> IdentityOperationResult.Success(value)
    is StoreResult.Failure -> IdentityOperationResult.Failure(error.toIdentityErrorCode())
}
