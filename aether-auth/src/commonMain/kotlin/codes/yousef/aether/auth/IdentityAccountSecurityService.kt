package codes.yousef.aether.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

/** Opt-in v1 lifecycle. Installing this service enables routes; omission preserves passkey defaults. */
class IdentityAccountSecurityService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    private val attempts: IdentityAccountAttemptLimiter,
    private val passwordProvider: IdentityPasswordProvider? = null,
    private val emailDelivery: IdentityEmailDeliveryProvider? = null,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime)
) {
    private val sessions = IdentitySessionIssuer(runtime, config)
    private val dummy = PasswordVerifier("pbkdf2-sha256-v1", Base64Url.encode(ByteArray(16)), Base64Url.encode(ByteArray(32)), 600_000)

    /** Enrollment/reset requires an existing passkey or constrained recovery session. No public signup. */
    suspend fun replacePassword(context: IdentityContext, password: String): IdentityOperationResult<Unit> = safe {
        val provider = passwordProvider ?: return@safe failure(IdentityErrorCode.NOT_FOUND)
        val user = authorizedUser(context, strong = true) ?: return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        if (!validIdentityPassword(password, enrollment = true)) return@safe failure(IdentityErrorCode.REQUEST_INVALID)
        if (!allowed("password-replace", user.id.value)) return@safe failure(IdentityErrorCode.RATE_LIMITED)
        val state = security(user.id)
        val verifier = provider.hash(password)
        commit(AccountSecurityOperation.PASSWORD_REPLACE, user, state,
            (state ?: AccountSecurityState(user.id)).copy(version = (state?.version ?: -1) + 1, password = verifier), context.session?.id)
        IdentityOperationResult.Success(Unit)
    }

    /** Valid, invalid, unknown, disabled and unenrolled accounts perform the same bounded KDF. */
    suspend fun login(email: String, password: String, networkKey: String): IdentityOperationResult<IssuedIdentitySession> = safe {
        val provider = passwordProvider ?: return@safe failure(IdentityErrorCode.NOT_FOUND)
        if (!validIdentityPassword(password, enrollment = false) || email.length !in 3..320 || networkKey.length > 512)
            return@safe failure(IdentityErrorCode.REQUEST_INVALID)
        if (!allowed("password-account", email.lowercase()) || !allowed("password-network", networkKey))
            return@safe failure(IdentityErrorCode.RATE_LIMITED)
        val address = runCatching { EmailAddress(email) }.getOrNull()
        val user = if (address == null) null else store.findUserByEmail(address).required()
        val state = user?.let { security(it.id) }
        val valid = provider.verify(password, state?.password ?: dummy)
        if (!valid || user == null || user.state != UserState.ACTIVE || state?.password == null)
            return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        val issued = sessions.issue(user, AuthenticationAssurance.SESSION, SessionAuthenticationMethod.PASSWORD, runtime.clock.now())
        val loginSession = issued.session
        val replacement = state.copy(version = state.version + 1)
        commit(AccountSecurityOperation.PASSWORD_LOGIN, user, state, replacement, issuedSession = loginSession)
        IdentityOperationResult.Success(issued)
    }

    /** Authenticated request sends only to the user's current contact address, never a caller-supplied recipient. */
    suspend fun requestEmail(context: IdentityContext): IdentityOperationResult<Unit> = safe {
        val delivery = emailDelivery ?: return@safe failure(IdentityErrorCode.NOT_FOUND)
        val user = authorizedUser(context) ?: return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        if (!allowed("email-request", user.id.value)) return@safe failure(IdentityErrorCode.RATE_LIMITED)
        val email = user.primaryEmail ?: return@safe IdentityOperationResult.Success(Unit)
        val state = security(user.id)
        val bytes = runtime.secureRandom.nextBytes(32)
        val token = try { Base64Url.encode(bytes) } finally { bytes.fill(0) }
        val now = runtime.clock.now()
        val challenge = EmailVerificationChallenge(digest(token), now + 15.minutes, now, user.sessionEpoch)
        val pending = (state ?: AccountSecurityState(user.id)).copy(version = (state?.version ?: -1) + 1,
            email = email, emailVerifiedAt = null, emailChallenge = challenge)
        commit(AccountSecurityOperation.EMAIL_REQUEST, user, state, pending, context.session?.id, occurredAt = now)
        if (!delivery.deliver(IdentityEmailDelivery(email, token))) return@safe failure(IdentityErrorCode.INTERNAL_ERROR)
        // A concurrent replacement, logout, reset or address change prevents stale delivery activation.
        commit(AccountSecurityOperation.EMAIL_DELIVERED, user, pending, pending.copy(version = pending.version + 1,
            emailChallenge = challenge.copy(delivered = true)), context.session?.id)
        IdentityOperationResult.Success(Unit)
    }

    suspend fun verifyEmail(context: IdentityContext, code: String): IdentityOperationResult<AccountEmailStatus> = safe {
        val user = authorizedUser(context) ?: return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        if (code.length != 43 || runCatching { Base64Url.decode(code, 32).size }.getOrNull() != 32)
            return@safe failure(IdentityErrorCode.REQUEST_INVALID)
        if (!allowed("email-verify", user.id.value)) return@safe failure(IdentityErrorCode.RATE_LIMITED)
        val state = security(user.id) ?: return@safe failure(IdentityErrorCode.REQUEST_INVALID)
        val challenge = state.emailChallenge ?: return@safe failure(IdentityErrorCode.REQUEST_INVALID)
        val now = runtime.clock.now()
        if (!challenge.delivered || challenge.expiresAt <= now || challenge.attemptsRemaining == 0 ||
            challenge.sessionEpoch != user.sessionEpoch || user.updatedAt > challenge.createdAt || state.email != user.primaryEmail)
            return@safe failure(IdentityErrorCode.REQUEST_INVALID)
        val expected = Base64Url.decode(challenge.digest, 32)
        val actual = Base64Url.decode(digest(code), 32)
        val valid = try { runtime.crypto.constantTimeEquals(expected, actual) } finally { expected.fill(0); actual.fill(0) }
        val next = state.copy(version = state.version + 1, emailVerifiedAt = if (valid) now else null,
            emailChallenge = if (valid) null else challenge.copy(attemptsRemaining = challenge.attemptsRemaining - 1))
        commit(AccountSecurityOperation.EMAIL_ATTEMPT, user, state, next, context.session?.id, occurredAt = now)
        if (valid) IdentityOperationResult.Success(AccountEmailStatus(true)) else failure(IdentityErrorCode.REQUEST_INVALID)
    }

    suspend fun emailStatus(context: IdentityContext): IdentityOperationResult<AccountEmailStatus> = safe {
        val user = authorizedUser(context) ?: return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        val state = security(user.id)
        IdentityOperationResult.Success(AccountEmailStatus(state?.email == user.primaryEmail && state?.emailVerifiedAt?.let { it >= user.updatedAt } == true))
    }

    suspend fun changeEmail(context: IdentityContext, address: EmailAddress): IdentityOperationResult<Unit> = safe {
        val user = authorizedUser(context, strong = true) ?: return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        if (context.session?.assurance == AuthenticationAssurance.RECOVERY) return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        val state = security(user.id)
        commit(AccountSecurityOperation.EMAIL_CHANGE, user, state,
            (state ?: AccountSecurityState(user.id)).copy(version = (state?.version ?: -1) + 1,
                email = EmailAddress(address.value.lowercase()), emailVerifiedAt = null, emailChallenge = null), context.session?.id)
        IdentityOperationResult.Success(Unit)
    }

    suspend fun disablePassword(context: IdentityContext): IdentityOperationResult<Unit> = safe {
        if (context.session?.assurance == AuthenticationAssurance.RECOVERY) return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        if (passwordProvider == null) return@safe failure(IdentityErrorCode.NOT_FOUND)
        val user = authorizedUser(context, strong = true) ?: return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        val state = security(user.id) ?: return@safe IdentityOperationResult.Success(Unit)
        commit(AccountSecurityOperation.PASSWORD_DISABLE, user, state, state.copy(version = state.version + 1, password = null), context.session?.id)
        IdentityOperationResult.Success(Unit)
    }

    suspend fun revokeEmail(context: IdentityContext): IdentityOperationResult<Unit> = safe {
        if (context.session?.assurance == AuthenticationAssurance.RECOVERY) return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        val user = authorizedUser(context, strong = true) ?: return@safe failure(IdentityErrorCode.AUTHENTICATION_REQUIRED)
        val state = security(user.id)
        commit(AccountSecurityOperation.EMAIL_REVOKE, user, state,
            (state ?: AccountSecurityState(user.id)).copy(version = (state?.version ?: -1) + 1,
                email = user.primaryEmail, emailVerifiedAt = null, emailChallenge = null), context.session?.id)
        IdentityOperationResult.Success(Unit)
    }

    private suspend fun authorizedUser(context: IdentityContext, strong: Boolean = false): User? {
        val session = context.session ?: return null
        if (strong && session.assurance !in setOf(AuthenticationAssurance.PASSKEY, AuthenticationAssurance.STEP_UP, AuthenticationAssurance.RECOVERY)) return null
        val user = store.findUser(session.userId).required() ?: return null
        val current = store.findSession(session.id).required() ?: return null
        val now = runtime.clock.now()
        if (strong && session.assurance != AuthenticationAssurance.RECOVERY &&
            (now - session.authenticatedAt).inWholeSeconds > config.lifetimes.recentPasskey.seconds) return null
        return user.takeIf { it.state == UserState.ACTIVE && current.copy(version = session.version, lastUsedAt = session.lastUsedAt, idleExpiresAt = session.idleExpiresAt) == session && current.state == SessionState.ACTIVE &&
            current.userSessionEpoch == it.sessionEpoch && current.idleExpiresAt > now && current.absoluteExpiresAt > now }
    }
    private suspend fun security(id: UserId) = store.findAccountSecurity(id).required()
    private suspend fun allowed(purpose: String, value: String): Boolean {
        val material = "$purpose\n$value".encodeToByteArray()
        val key = runtime.secrets.resolve(config.keys.auditPseudonymizationKey)
        return try {
            val hashed = runtime.crypto.hmacSha256(key, material)
            try { attempts.allow(Base64Url.encode(hashed), runtime.clock.now()) } finally { hashed.fill(0) }
        } finally { material.fill(0) }
    }
    private suspend fun digest(value: String): String {
        val bytes = value.encodeToByteArray()
        return try { val hash = runtime.crypto.sha256(bytes); try { Base64Url.encode(hash) } finally { hash.fill(0) } }
        finally { bytes.fill(0) }
    }
    private suspend fun commit(operation: AccountSecurityOperation, user: User, previous: AccountSecurityState?,
        next: AccountSecurityState, actorSession: SessionId? = null, issuedSession: IdentitySession? = null, occurredAt: kotlin.time.Instant = runtime.clock.now()) {
        val event = AuditEvent(id = ids.newAuditEventId(),
            action = AuditAction.ACCOUNT_SECURITY_CHANGED, outcome = AuditOutcome.SUCCEEDED,
            actor = AuditActor(type = AuditActorType.USER, userId = user.id),
            target = AuditTarget(type = AuditTargetType.USER, id = user.id.value), occurredAt = occurredAt,
            reasonCode = operation.name.lowercase())
        store.mutateAccountSecurity(MutateAccountSecurityCommand(operation, user.version, previous?.version ?: -1,
            next, actorSession, issuedSession, event)).required()
    }
    private suspend fun <T> safe(block: suspend () -> IdentityOperationResult<T>): IdentityOperationResult<T> = try { withTimeout(15_000) { block() } }
    catch (_: TimeoutCancellationException) { failure(IdentityErrorCode.SERVICE_UNAVAILABLE) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AccountSecurityStoreFailure) { failure(failure.error.toIdentityErrorCode()) }
    catch (_: Exception) { failure(IdentityErrorCode.INTERNAL_ERROR) }
}

fun validIdentityPassword(value: String, enrollment: Boolean): Boolean = value.length in (if (enrollment) 15 else 1)..1024 &&
    value.encodeToByteArray().size <= 1024 && value.none { it == '\u0000' }
private fun <T> failure(code: IdentityErrorCode): IdentityOperationResult<T> = IdentityOperationResult.Failure(code)
private class AccountSecurityStoreFailure(val error: IdentityStoreError) : Exception("Account security persistence failed")
private fun <T> StoreResult<T>.required(): T = when (this) {
    is StoreResult.Success -> value
    is StoreResult.Failure -> throw AccountSecurityStoreFailure(error)
}
