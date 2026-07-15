package codes.yousef.aether.auth.firestore

import codes.yousef.aether.auth.IdentityStoreError
import codes.yousef.aether.auth.IdentityStoreErrorCode

internal class FirestoreStoreException(
    val safeError: IdentityStoreError,
    val transactionRetryable: Boolean = false
) : RuntimeException("Firestore identity operation failed") {
    override fun toString(): String =
        "FirestoreStoreException(code=${safeError.code}, retryable=${safeError.retryable})"
}

internal object FirestoreFailureMapper {
    fun fromProvider(status: String?, httpStatus: Int? = null): IdentityStoreError {
        val normalized = status?.trim()?.uppercase()
        return when {
            normalized == "ALREADY_EXISTS" -> failure(IdentityStoreErrorCode.ALREADY_EXISTS)
            normalized == "NOT_FOUND" -> failure(IdentityStoreErrorCode.NOT_FOUND)
            normalized == "ABORTED" || normalized == "FAILED_PRECONDITION" -> versionConflict()
            normalized == "RESOURCE_EXHAUSTED" || normalized == "UNAVAILABLE" ||
                normalized == "DEADLINE_EXCEEDED" -> unavailable()
            normalized == "PERMISSION_DENIED" || normalized == "UNAUTHENTICATED" ||
                normalized == "INVALID_ARGUMENT" -> internal()
            httpStatus == 404 -> failure(IdentityStoreErrorCode.NOT_FOUND)
            httpStatus == 409 || httpStatus == 412 -> versionConflict()
            httpStatus == 429 || (httpStatus != null && httpStatus >= 500) -> unavailable()
            else -> internal()
        }
    }

    fun versionConflict(): IdentityStoreError =
        failure(IdentityStoreErrorCode.VERSION_CONFLICT, retryable = true)

    fun unavailable(): IdentityStoreError =
        failure(IdentityStoreErrorCode.UNAVAILABLE, retryable = true)

    fun internal(): IdentityStoreError = failure(IdentityStoreErrorCode.INTERNAL)

    private fun failure(code: IdentityStoreErrorCode, retryable: Boolean = false): IdentityStoreError =
        IdentityStoreError(code, retryable)
}
