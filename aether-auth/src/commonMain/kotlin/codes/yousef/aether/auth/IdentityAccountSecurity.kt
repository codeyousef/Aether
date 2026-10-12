package codes.yousef.aether.auth

import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes

/** Versioned persisted verifier. Never a wire response or diagnostic value. */
@Serializable
class PasswordVerifier(val algorithm: String, val salt: String, val hash: String, val iterations: Int) {
    init {
        require(algorithm == "pbkdf2-sha256-v1" && iterations == 600_000) { "Unsupported password verifier" }
        require(Base64Url.decode(salt, 16).size == 16 && Base64Url.decode(hash, 32).size == 32) { "Invalid password verifier" }
    }
    override fun toString() = "PasswordVerifier(<redacted>)"
}

/** Implementations must bound CPU work and reject overload without an unbounded waiting queue. */
interface IdentityPasswordProvider {
    suspend fun hash(password: String): PasswordVerifier
    suspend fun verify(password: String, verifier: PasswordVerifier): Boolean
}

@Serializable
data class EmailVerificationChallenge(
    val digest: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val sessionEpoch: Long,
    val delivered: Boolean = false,
    val attemptsRemaining: Int = 5
) {
    init { require(Base64Url.decode(digest, 32).size == 32 && attemptsRemaining in 0..5 && sessionEpoch >= 0)
        require(expiresAt > createdAt && expiresAt - createdAt <= 15.minutes)
    }
    override fun toString() = "EmailVerificationChallenge(<redacted>)"
}

@Serializable
data class AccountSecurityState(
    val userId: UserId,
    val version: Long = 0,
    val password: PasswordVerifier? = null,
    val email: EmailAddress? = null,
    val emailVerifiedAt: Instant? = null,
    val emailChallenge: EmailVerificationChallenge? = null
) {
    init {
        require(version >= 0)
        require(email != null || emailVerifiedAt == null && emailChallenge == null)
    }
    override fun toString() = "AccountSecurityState(userId=$userId, version=$version, material=<redacted>)"
}

@Serializable
enum class AccountSecurityOperation { PASSWORD_REPLACE, PASSWORD_DISABLE, PASSWORD_LOGIN, EMAIL_REQUEST, EMAIL_DELIVERED, EMAIL_ATTEMPT, EMAIL_REVOKE, EMAIL_CHANGE }

/** One atomic CAS of account security, user epoch, optional login session, and redacted audit. */
@Serializable
data class MutateAccountSecurityCommand(
    val operation: AccountSecurityOperation,
    val expectedUserVersion: Long,
    val expectedSecurityVersion: Long,
    val replacement: AccountSecurityState,
    val actorSessionId: SessionId? = null,
    val issuedSession: IdentitySession? = null,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedUserVersion >= 0 && expectedSecurityVersion >= -1)
        require(replacement.version == expectedSecurityVersion + 1)
        require((operation == AccountSecurityOperation.PASSWORD_LOGIN) == (issuedSession != null))
        require(issuedSession == null || issuedSession.userId == replacement.userId &&
            issuedSession.authenticationMethod == SessionAuthenticationMethod.PASSWORD &&
            issuedSession.assurance == AuthenticationAssurance.SESSION && issuedSession.version == 0L)
        require(operation == AccountSecurityOperation.PASSWORD_LOGIN || actorSessionId != null)
        require(auditEvent.action == AuditAction.ACCOUNT_SECURITY_CHANGED && auditEvent.target?.id == replacement.userId.value)
    }
    override fun toString() = "MutateAccountSecurityCommand(operation=$operation, material=<redacted>)"
}

/** Validates the transition under the adapter's user/security/session locks. */
fun validateAccountSecurityMutation(
    command: MutateAccountSecurityCommand,
    user: User,
    previous: AccountSecurityState?,
    actor: IdentitySession?
): IdentityStoreErrorCode? {
    val next = command.replacement
    val now = command.auditEvent.occurredAt
    if (user.id != next.userId || user.version != command.expectedUserVersion ||
        (previous?.version ?: -1) != command.expectedSecurityVersion) return IdentityStoreErrorCode.VERSION_CONFLICT
    if (user.state != UserState.ACTIVE) return IdentityStoreErrorCode.INVALID_TRANSITION
    if (command.operation != AccountSecurityOperation.PASSWORD_LOGIN && (actor == null ||
        actor.id != command.actorSessionId || actor.userId != user.id || actor.state != SessionState.ACTIVE ||
        actor.userSessionEpoch != user.sessionEpoch || actor.idleExpiresAt <= now || actor.absoluteExpiresAt <= now))
        return IdentityStoreErrorCode.SESSION_NOT_ACTIVE
    val old = previous ?: AccountSecurityState(user.id)
    fun samePassword(a: PasswordVerifier?, b: PasswordVerifier?) = a?.algorithm == b?.algorithm &&
        a?.salt == b?.salt && a?.hash == b?.hash && a?.iterations == b?.iterations
    val emailSame = next.email == old.email && next.emailVerifiedAt == old.emailVerifiedAt && next.emailChallenge == old.emailChallenge
    return when (command.operation) {
        AccountSecurityOperation.PASSWORD_REPLACE, AccountSecurityOperation.PASSWORD_DISABLE -> if (
            (command.operation == AccountSecurityOperation.PASSWORD_REPLACE && next.password == null ||
             command.operation == AccountSecurityOperation.PASSWORD_DISABLE && next.password != null) || !emailSame ||
            actor?.assurance !in setOf(AuthenticationAssurance.PASSKEY, AuthenticationAssurance.STEP_UP, AuthenticationAssurance.RECOVERY))
            IdentityStoreErrorCode.INVALID_TRANSITION else null
        AccountSecurityOperation.PASSWORD_LOGIN -> if (old.password == null || !samePassword(next.password, old.password) || !emailSame ||
            command.issuedSession?.userSessionEpoch != user.sessionEpoch) IdentityStoreErrorCode.INVALID_TRANSITION else null
        AccountSecurityOperation.EMAIL_REQUEST -> if (!samePassword(next.password, old.password) || next.email != user.primaryEmail ||
            next.email == null || next.emailVerifiedAt != null || next.emailChallenge?.let {
                it.delivered || it.attemptsRemaining != 5 || it.sessionEpoch != user.sessionEpoch || it.createdAt != now || it.expiresAt <= now
            } != false) IdentityStoreErrorCode.INVALID_TRANSITION else null
        AccountSecurityOperation.EMAIL_DELIVERED -> if (!samePassword(next.password, old.password) || next.email != old.email ||
            next.emailVerifiedAt != old.emailVerifiedAt || old.emailChallenge == null || old.emailChallenge.delivered ||
            next.emailChallenge != old.emailChallenge.copy(delivered = true)) IdentityStoreErrorCode.INVALID_TRANSITION else null
        AccountSecurityOperation.EMAIL_ATTEMPT -> {
            val challenge = old.emailChallenge
            if (!samePassword(next.password, old.password) || next.email != old.email || next.email != user.primaryEmail ||
                challenge == null || !challenge.delivered || challenge.expiresAt <= now || challenge.sessionEpoch != user.sessionEpoch ||
                challenge.attemptsRemaining == 0 || user.updatedAt > challenge.createdAt || !(next.emailChallenge == null && next.emailVerifiedAt == now ||
                    next.emailVerifiedAt == null && next.emailChallenge == challenge.copy(attemptsRemaining = challenge.attemptsRemaining - 1)))
                IdentityStoreErrorCode.INVALID_TRANSITION else null
        }
        AccountSecurityOperation.EMAIL_CHANGE -> if (!samePassword(next.password, old.password) || next.email == null ||
            next.emailVerifiedAt != null || next.emailChallenge != null || actor?.assurance !in
            setOf(AuthenticationAssurance.PASSKEY, AuthenticationAssurance.STEP_UP)) IdentityStoreErrorCode.INVALID_TRANSITION else null
        AccountSecurityOperation.EMAIL_REVOKE -> if (!samePassword(next.password, old.password) || next.email != user.primaryEmail ||
            next.emailVerifiedAt != null || next.emailChallenge != null || actor?.assurance !in
            setOf(AuthenticationAssurance.PASSKEY, AuthenticationAssurance.STEP_UP)) IdentityStoreErrorCode.INVALID_TRANSITION else null
    }
}

/** Delivery sends a code to the address; no URL token and no authentication authority are granted. */
class IdentityEmailDelivery(val recipient: EmailAddress, private val value: String) {
    fun code(): String = value
    override fun toString() = "IdentityEmailDelivery(<redacted>)"
}
fun interface IdentityEmailDeliveryProvider {
    /** Return true only after provider acknowledgement. Provider failures must never self-verify. */
    suspend fun deliver(message: IdentityEmailDelivery): Boolean
}

/** Shared deployment limiter: keys are HMAC-SHA-256 pseudonyms; store no raw account or network values. */
fun interface IdentityAccountAttemptLimiter {
    suspend fun allow(key: String, attemptedAt: Instant): Boolean
}

@Serializable
data class AccountEmailStatus(val verified: Boolean)

@Serializable
data class AccountPasswordLoginRequest(val email: String, val password: String) {
    override fun toString() = "AccountPasswordLoginRequest(<redacted>)"
}
@Serializable
data class AccountPasswordReplaceRequest(val password: String) {
    override fun toString() = "AccountPasswordReplaceRequest(<redacted>)"
}
@Serializable
data class AccountEmailVerifyRequest(val code: String) {
    override fun toString() = "AccountEmailVerifyRequest(<redacted>)"
}

@Serializable
data class AccountEmailChangeRequest(val email: String) {
    override fun toString() = "AccountEmailChangeRequest(<redacted>)"
}
