package codes.yousef.aether.auth

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Platform-level policy hook. Organization roles and capabilities are intentionally not consulted. */
fun interface AdministrativeRecoveryAuthorizer {
    suspend fun authorize(actor: IdentityContext, target: User): Boolean
}

@Serializable
data class AdministrativeRecoveryTicketView(
    val id: ChallengeId,
    val userId: UserId,
    val state: ChallengeState,
    val createdAt: Instant,
    val expiresAt: Instant
)

class AdministrativeRecoveryTicketDelivery internal constructor(
    val ticket: AdministrativeRecoveryTicketView,
    val target: User,
    private val token: String
) {
    fun revealToken(): String = token
    override fun toString(): String =
        "AdministrativeRecoveryTicketDelivery(ticket=$ticket, target=${target.id}, token=<redacted>)"
}

@Serializable
data class RecoveryNotificationOutcome(
    val delivered: Boolean,
    val reasonCode: String? = null
) {
    init {
        require(reasonCode == null || (reasonCode.isNotBlank() && reasonCode.length <= 100 &&
            Regex("[a-z0-9_]+(?:[.-][a-z0-9_]+)*").matches(reasonCode)
        )) { "Recovery notification reason codes must be bounded lowercase identifiers" }
    }
}

fun interface AdministrativeRecoveryNotificationSink {
    suspend fun deliver(delivery: AdministrativeRecoveryTicketDelivery): RecoveryNotificationOutcome
}

/**
 * Optional administrative recovery. The service remains disabled unless both an independent
 * platform authorizer and a notification sink are explicitly configured.
 */
class IdentityAdministrativeRecoveryService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    private val authorizer: AdministrativeRecoveryAuthorizer?,
    private val notificationSink: AdministrativeRecoveryNotificationSink?,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime),
    private val sessions: IdentitySessionIssuer = IdentitySessionIssuer(runtime, config, ids)
) {
    val enabled: Boolean get() = authorizer != null && notificationSink != null

    suspend fun issueTicket(
        actor: IdentityContext,
        targetUserId: UserId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<AdministrativeRecoveryTicketView> {
        val policy = authorizer ?: return notFound()
        val sink = notificationSink ?: return notFound()
        val now = runtime.clock.now()
        val actorUserId = actor.principal?.takeIf { it.kind == IdentityPrincipalKind.USER }?.userId
            ?: return notFound()
        if (!isRecentPasskey(actor, now)) return IdentityOperationResult.Failure(IdentityErrorCode.STEP_UP_REQUIRED)
        val target = when (val found = store.findUser(targetUserId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return operationFailure(found.error)
        } ?: return notFound()
        if (target.state != UserState.ACTIVE || !safeAuthorize(policy, actor, target)) return notFound()

        val challengeId = ids.newChallengeId()
        val secret = runtime.secureRandom.nextBytes(TICKET_SECRET_BYTES)
        require(secret.size == TICKET_SECRET_BYTES) { "Secure random provider returned invalid recovery ticket material" }
        try {
            val challenge = Challenge(
                id = challengeId,
                purpose = ChallengePurpose.ACCOUNT_RECOVERY,
                challengeDigest = ticketDigest(challengeId, secret, config.keys.recoveryPepper),
                bindingDigest = userBindingDigest(target.id, config.keys.recoveryPepper),
                userId = target.id,
                createdAt = now,
                expiresAt = now + config.lifetimes.recoveryChallenge.seconds.seconds
            )
            val createdAudit = audit(
                actor = AuditActor(AuditActorType.USER, userId = actorUserId),
                action = AuditAction.RECOVERY_ADMIN_TICKET_CREATED,
                targetUserId = target.id,
                outcome = AuditOutcome.SUCCEEDED,
                at = now,
                request = request
            )
            val storedChallenge = when (val created = store.createChallenge(CreateChallengeCommand(challenge, createdAudit))) {
                is StoreResult.Failure -> return operationFailure(created.error)
                is StoreResult.Success -> created.value
            }

            val view = storedChallenge.toAdministrativeRecoveryView()
            val token = "${challenge.id.value}.${Base64Url.encode(secret)}"
            val deliveryOutcome = try {
                sink.deliver(AdministrativeRecoveryTicketDelivery(view, target, token))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                RecoveryNotificationOutcome(false, "notification_exception")
            }
            val deliveredAt = runtime.clock.now()
            val deliveryAudit = audit(
                actor = AuditActor(AuditActorType.USER, userId = actorUserId),
                action = AuditAction.RECOVERY_ADMIN_TICKET_DELIVERED,
                targetUserId = target.id,
                outcome = if (deliveryOutcome.delivered) AuditOutcome.SUCCEEDED else AuditOutcome.FAILED,
                at = deliveredAt,
                request = request,
                reasonCode = deliveryOutcome.reasonCode
            )
            if (!deliveryOutcome.delivered) {
                store.appendAuditEvent(deliveryAudit)
                cancelCreatedTicket(
                    storedChallenge,
                    actorUserId,
                    deliveredAt,
                    request,
                    deliveryOutcome.reasonCode ?: "delivery_failed"
                )
                return IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
            }
            return when (val activated = store.activateAdministrativeRecoveryTicket(
                ActivateAdministrativeRecoveryTicketCommand(
                    challengeId = storedChallenge.id,
                    expectedChallengeVersion = storedChallenge.version,
                    activatedAt = deliveredAt,
                    auditEvent = deliveryAudit
                )
            )) {
                is StoreResult.Success -> IdentityOperationResult.Success(
                    activated.value.challenge.toAdministrativeRecoveryView()
                )
                is StoreResult.Failure -> {
                    // If the activation did not commit, this version-zero ticket remains
                    // cryptographically valid but non-redeemable because activatedAt is absent.
                    cancelCreatedTicket(
                        storedChallenge,
                        actorUserId,
                        deliveredAt,
                        request,
                        "delivery_audit_failed"
                    )
                    IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
                }
            }
        } finally {
            secret.fill(0)
        }
    }

    suspend fun cancelTicket(
        actor: IdentityContext,
        ticketId: ChallengeId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<AdministrativeRecoveryTicketView> {
        val policy = authorizer ?: return notFound()
        if (notificationSink == null) return notFound()
        val now = runtime.clock.now()
        val actorUserId = actor.principal?.takeIf { it.kind == IdentityPrincipalKind.USER }?.userId
            ?: return notFound()
        if (!isRecentPasskey(actor, now)) return IdentityOperationResult.Failure(IdentityErrorCode.STEP_UP_REQUIRED)
        val ticket = when (val found = store.findChallenge(ticketId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return operationFailure(found.error)
        } ?: return notFound()
        if (ticket.purpose != ChallengePurpose.ACCOUNT_RECOVERY || ticket.state != ChallengeState.PENDING ||
            ticket.userId == null
        ) return notFound()
        val target = when (val found = store.findUser(ticket.userId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return operationFailure(found.error)
        } ?: return notFound()
        if (!safeAuthorize(policy, actor, target)) return notFound()
        val audit = audit(
            actor = AuditActor(AuditActorType.USER, userId = actorUserId),
            action = AuditAction.RECOVERY_ADMIN_TICKET_CANCELLED,
            targetUserId = target.id,
            outcome = AuditOutcome.SUCCEEDED,
            at = now,
            request = request,
            reasonCode = "administrator_cancelled"
        )
        return when (val result = store.consumeChallenge(
            ConsumeChallengeCommand(ticket.id, ticket.version, ChallengeState.FAILED, now, audit)
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value.toAdministrativeRecoveryView())
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun redeemTicket(
        token: String,
        device: DeviceMetadata = DeviceMetadata(),
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<CompletedRecovery> {
        if (!enabled) return notFound()
        val parsed = parseTicket(token) ?: return invalidCredentials()
        try {
            val challenge = when (val found = store.findChallenge(parsed.id)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return operationFailure(found.error)
            } ?: return invalidCredentials()
            val activatedAt = challenge.activatedAt
            if (challenge.purpose != ChallengePurpose.ACCOUNT_RECOVERY || challenge.userId == null ||
                challenge.state != ChallengeState.PENDING ||
                activatedAt == null ||
                !verifyTicketDigest(parsed, challenge.challengeDigest) ||
                !verifyBindingDigest(challenge.userId, challenge.bindingDigest)
            ) return invalidCredentials()
            val now = runtime.clock.now()
            if (activatedAt > now || now >= challenge.expiresAt) {
                val expiredAudit = audit(
                    actor = AuditActor(AuditActorType.ANONYMOUS),
                    action = AuditAction.RECOVERY_ADMIN_TICKET_EXPIRED,
                    targetUserId = challenge.userId,
                    outcome = AuditOutcome.DENIED,
                    at = now,
                    request = request,
                    reasonCode = "ticket_expired"
                )
                store.consumeChallenge(
                    ConsumeChallengeCommand(challenge.id, challenge.version, ChallengeState.EXPIRED, now, expiredAudit)
                )
                return invalidCredentials()
            }
            val user = when (val found = store.findUser(challenge.userId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return operationFailure(found.error)
            } ?: return invalidCredentials()
            if (user.state != UserState.ACTIVE) return invalidCredentials()
            val issued = sessions.issue(
                user = user,
                assurance = AuthenticationAssurance.RECOVERY,
                authenticationMethod = SessionAuthenticationMethod.ADMINISTRATIVE_RECOVERY,
                authenticatedAt = now,
                device = device,
                absoluteLifetime = config.lifetimes.recoverySession,
                idleLifetime = config.lifetimes.recoverySession
            )
            val usedAudit = audit(
                actor = AuditActor(AuditActorType.ANONYMOUS),
                action = AuditAction.RECOVERY_ADMIN_TICKET_USED,
                targetUserId = user.id,
                outcome = AuditOutcome.SUCCEEDED,
                at = now,
                request = request
            )
            return when (val redeemed = store.redeemAdministrativeRecoveryTicket(
                RedeemAdministrativeRecoveryTicketCommand(
                    challenge.id,
                    challenge.version,
                    now,
                    issued.session,
                    usedAudit
                )
            )) {
                is StoreResult.Success -> IdentityOperationResult.Success(CompletedRecovery(user.id, issued))
                is StoreResult.Failure -> operationFailure(redeemed.error)
            }
        } finally {
            parsed.secret.fill(0)
        }
    }

    private suspend fun cancelCreatedTicket(
        challenge: Challenge,
        actorUserId: UserId,
        at: Instant,
        request: AuditRequestMetadata?,
        reasonCode: String
    ) {
        val cancellation = audit(
            actor = AuditActor(AuditActorType.USER, userId = actorUserId),
            action = AuditAction.RECOVERY_ADMIN_TICKET_CANCELLED,
            targetUserId = requireNotNull(challenge.userId),
            outcome = AuditOutcome.SUCCEEDED,
            at = at,
            request = request,
            reasonCode = reasonCode
        )
        store.consumeChallenge(
            ConsumeChallengeCommand(challenge.id, challenge.version, ChallengeState.FAILED, at, cancellation)
        )
    }

    private suspend fun safeAuthorize(
        policy: AdministrativeRecoveryAuthorizer,
        actor: IdentityContext,
        target: User
    ): Boolean = try {
        policy.authorize(actor, target)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    private suspend fun ticketDigest(
        id: ChallengeId,
        secret: ByteArray,
        reference: SecretReference
    ): SecretDigest = digest("$TICKET_CONTEXT${id.value}\u0000".encodeToByteArray() + secret, reference)

    private suspend fun userBindingDigest(userId: UserId, reference: SecretReference): SecretDigest =
        digest("$BINDING_CONTEXT${userId.value}".encodeToByteArray(), reference)

    private suspend fun digest(input: ByteArray, reference: SecretReference): SecretDigest = try {
        val bytes = runtime.crypto.hmacSha256(runtime.secrets.resolve(reference), input)
        try {
            require(bytes.size == 32) { "HMAC-SHA-256 provider returned an invalid digest" }
            SecretDigest(DigestAlgorithm.HMAC_SHA256, Base64Url.encode(bytes), reference.version)
        } finally {
            bytes.fill(0)
        }
    } finally {
        input.fill(0)
    }

    private suspend fun verifyTicketDigest(parsed: ParsedTicket, stored: SecretDigest): Boolean {
        val reference = config.keys.recoveryPepper(stored.keyVersion) ?: return false
        return compareDigests(stored, ticketDigest(parsed.id, parsed.secret, reference))
    }

    private suspend fun verifyBindingDigest(userId: UserId, stored: SecretDigest): Boolean {
        val reference = config.keys.recoveryPepper(stored.keyVersion) ?: return false
        return compareDigests(stored, userBindingDigest(userId, reference))
    }

    private suspend fun compareDigests(expected: SecretDigest, actual: SecretDigest): Boolean {
        if (expected.algorithm != DigestAlgorithm.HMAC_SHA256 || actual.algorithm != expected.algorithm ||
            actual.keyVersion != expected.keyVersion
        ) return false
        val left = runCatching { Base64Url.decode(expected.encoded, maximumBytes = 32) }.getOrNull() ?: return false
        val right = runCatching { Base64Url.decode(actual.encoded, maximumBytes = 32) }.getOrNull() ?: return false
        return try {
            left.size == 32 && right.size == 32 && runtime.crypto.constantTimeEquals(left, right)
        } finally {
            left.fill(0)
            right.fill(0)
        }
    }

    private fun parseTicket(value: String): ParsedTicket? {
        if (value.length !in 20..512 || value.count { it == '.' } != 1) return null
        val id = ChallengeId.parseOrNull(value.substringBefore('.')) ?: return null
        val secret = runCatching {
            Base64Url.decode(value.substringAfter('.'), maximumBytes = TICKET_SECRET_BYTES)
        }.getOrNull() ?: return null
        if (secret.size != TICKET_SECRET_BYTES) {
            secret.fill(0)
            return null
        }
        return ParsedTicket(id, secret)
    }

    private fun isRecentPasskey(context: IdentityContext, now: Instant): Boolean {
        val principal = context.principal ?: return false
        return context.session?.state == SessionState.ACTIVE &&
            principal.assurance.satisfies(AuthenticationAssurance.PASSKEY) &&
            principal.authenticatedAt <= now &&
            now - principal.authenticatedAt <= config.lifetimes.recentPasskey.seconds.seconds
    }

    private fun audit(
        actor: AuditActor,
        action: AuditAction,
        targetUserId: UserId,
        outcome: AuditOutcome,
        at: Instant,
        request: AuditRequestMetadata?,
        reasonCode: String? = null
    ): AuditEvent = AuditEvent(
        id = ids.newAuditEventId(),
        actor = actor,
        action = action,
        target = AuditTarget(AuditTargetType.USER, targetUserId.value),
        outcome = outcome,
        reasonCode = reasonCode,
        request = request,
        occurredAt = at
    )

    private fun <T> invalidCredentials(): IdentityOperationResult<T> =
        IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)

    private fun <T> notFound(): IdentityOperationResult<T> = IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)

    private fun <T> operationFailure(error: IdentityStoreError): IdentityOperationResult<T> =
        IdentityOperationResult.Failure(when (error.code) {
            IdentityStoreErrorCode.NOT_FOUND -> IdentityErrorCode.NOT_FOUND
            IdentityStoreErrorCode.VERSION_CONFLICT -> IdentityErrorCode.CONFLICT
            IdentityStoreErrorCode.UNAVAILABLE,
            IdentityStoreErrorCode.INTERNAL -> IdentityErrorCode.SERVICE_UNAVAILABLE
            else -> IdentityErrorCode.INVALID_CREDENTIALS
        })

    private data class ParsedTicket(val id: ChallengeId, val secret: ByteArray)

    companion object {
        const val TICKET_SECRET_BYTES: Int = 32
        private const val TICKET_CONTEXT = "aether-admin-recovery-ticket-v1\u0000"
        private const val BINDING_CONTEXT = "aether-admin-recovery-user-v1\u0000"
    }
}

private fun Challenge.toAdministrativeRecoveryView(): AdministrativeRecoveryTicketView =
    AdministrativeRecoveryTicketView(
        id = id,
        userId = requireNotNull(userId),
        state = state,
        createdAt = createdAt,
        expiresAt = expiresAt
    )
