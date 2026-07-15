package codes.yousef.aether.auth

import kotlin.time.Instant

/** Stable per-deployment pseudonym; raw connection addresses never cross this boundary. */
class IdentityRecoveryAttemptKey internal constructor(private val encoded: String) {
    init { require(encoded.isNotBlank() && encoded.length <= 128) { "Invalid recovery-attempt key" } }

    override fun equals(other: Any?): Boolean =
        other is IdentityRecoveryAttemptKey && encoded == other.encoded

    override fun hashCode(): Int = encoded.hashCode()

    override fun toString(): String = "IdentityRecoveryAttemptKey(<redacted>)"
}

data class IdentityRecoveryAttempt(
    val key: IdentityRecoveryAttemptKey,
    val attemptedAt: Instant
)

/** Returns false when the generic recovery-code endpoint must reject the attempt. */
fun interface IdentityRecoveryAttemptLimiter {
    suspend fun allow(attempt: IdentityRecoveryAttempt): Boolean
}
