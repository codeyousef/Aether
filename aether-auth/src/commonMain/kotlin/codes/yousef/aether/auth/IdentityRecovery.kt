package codes.yousef.aether.auth

import kotlin.time.Instant

/** A recovery code shown once. Its string representation is always redacted. */
class IssuedRecoveryCode internal constructor(private val encoded: String) {
    fun reveal(): String = encoded
    override fun toString(): String = "IssuedRecoveryCode(<redacted>)"
}

class IssuedRecoveryCodeGeneration internal constructor(
    val generation: Long,
    val codes: List<IssuedRecoveryCode>
) {
    init { require(codes.size == RECOVERY_CODE_COUNT) }
    override fun toString(): String = "IssuedRecoveryCodeGeneration(generation=$generation, codes=<redacted>)"
}

data class CompletedRecovery(
    val userId: UserId,
    val issuedSession: IssuedIdentitySession
)

/** Generates, verifies, and consumes 128-bit single-use account-recovery codes. */
class IdentityRecoveryService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime),
    private val sessions: IdentitySessionIssuer = IdentitySessionIssuer(runtime, config, ids)
) {
    suspend fun replaceCodes(
        userId: UserId,
        expectedGeneration: Long?,
        actor: AuditActor = AuditActor(AuditActorType.USER, userId = userId),
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<IssuedRecoveryCodeGeneration> {
        if (actor.type == AuditActorType.USER && actor.userId != userId) {
            return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        }
        val now = runtime.clock.now()
        val generation = (expectedGeneration ?: -1L) + 1L
        val issued = mutableListOf<IssuedRecoveryCode>()
        val stored = mutableListOf<RecoveryCode>()
        repeat(RECOVERY_CODE_COUNT) {
            val selectorBytes = runtime.secureRandom.nextBytes(RECOVERY_SELECTOR_BYTES)
            val secretBytes = runtime.secureRandom.nextBytes(RECOVERY_SECRET_BYTES)
            require(selectorBytes.size == RECOVERY_SELECTOR_BYTES && secretBytes.size == RECOVERY_SECRET_BYTES) {
                "Secure random provider returned invalid recovery material"
            }
            try {
                val selector = Base64Url.encode(selectorBytes)
                val pepper = runtime.secrets.resolve(config.keys.recoveryPepper)
                val digest = recoveryDigest(pepper, selector, secretBytes)
                try {
                    require(digest.size == 32) { "HMAC-SHA-256 provider returned an invalid digest" }
                    issued += IssuedRecoveryCode("$selector.${Base64Url.encode(secretBytes)}")
                    stored += RecoveryCode(
                        id = ids.newRecoveryCodeId(),
                        userId = userId,
                        generation = generation,
                        publicSelector = selector,
                        secretDigest = SecretDigest(
                            algorithm = DigestAlgorithm.HMAC_SHA256,
                            encoded = Base64Url.encode(digest),
                            keyVersion = config.keys.recoveryPepper.version
                        ),
                        createdAt = now
                    )
                } finally {
                    digest.fill(0)
                }
            } finally {
                selectorBytes.fill(0)
                secretBytes.fill(0)
            }
        }
        val audit = AuditEvent(
            id = ids.newAuditEventId(),
            actor = actor,
            action = AuditAction.RECOVERY_CODES_REPLACED,
            target = AuditTarget(AuditTargetType.USER, userId.value),
            outcome = AuditOutcome.SUCCEEDED,
            request = request,
            occurredAt = now
        )
        return when (val committed = store.replaceRecoveryCodes(
            ReplaceRecoveryCodesCommand(
                userId = userId,
                expectedGeneration = expectedGeneration,
                newGeneration = generation,
                codes = stored,
                auditEvent = audit
            )
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(
                IssuedRecoveryCodeGeneration(committed.value.generation, issued)
            )
            is StoreResult.Failure -> IdentityOperationResult.Failure(committed.error.toIdentityErrorCode())
        }
    }

    suspend fun recover(
        encodedCode: String,
        device: DeviceMetadata = DeviceMetadata(),
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<CompletedRecovery> {
        val parsed = parseRecoveryCode(encodedCode)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        try {
            val code = when (val found = store.findRecoveryCodeBySelector(parsed.selector)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return IdentityOperationResult.Failure(found.error.toIdentityErrorCode())
            } ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            val now = runtime.clock.now()
            if (code.state != RecoveryCodeState.ACTIVE || code.expiresAt?.let { now >= it } == true ||
                code.secretDigest.algorithm != DigestAlgorithm.HMAC_SHA256
            ) return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)

            val pepperReference = config.keys.recoveryPepper(code.secretDigest.keyVersion)
                ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            val expected = runCatching {
                Base64Url.decode(code.secretDigest.encoded, maximumBytes = 32)
            }.getOrNull() ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            val actual = recoveryDigest(
                runtime.secrets.resolve(pepperReference),
                parsed.selector,
                parsed.secret
            )
            val matches = try {
                expected.size == 32 && actual.size == 32 && runtime.crypto.constantTimeEquals(expected, actual)
            } finally {
                expected.fill(0)
                actual.fill(0)
            }
            if (!matches) return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)

            val user = when (val found = store.findUser(code.userId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return IdentityOperationResult.Failure(found.error.toIdentityErrorCode())
            } ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            if (user.state != UserState.ACTIVE) {
                return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            }
            val issued = sessions.issue(
                user = user,
                assurance = AuthenticationAssurance.RECOVERY,
                authenticationMethod = SessionAuthenticationMethod.RECOVERY_CODE,
                authenticatedAt = now,
                device = device,
                absoluteLifetime = config.lifetimes.recoverySession,
                idleLifetime = config.lifetimes.recoverySession
            )
            val audit = AuditEvent(
                id = ids.newAuditEventId(),
                actor = AuditActor(AuditActorType.USER, userId = user.id),
                action = AuditAction.RECOVERY_CODE_USED,
                target = AuditTarget(AuditTargetType.USER, user.id.value),
                outcome = AuditOutcome.SUCCEEDED,
                request = request,
                occurredAt = now
            )
            return when (val committed = store.consumeRecoveryCode(
                ConsumeRecoveryCodeCommand(
                    recoveryCodeId = code.id,
                    expectedVersion = code.version,
                    consumedAt = now,
                    recoverySession = issued.session,
                    auditEvent = audit
                )
            )) {
                is StoreResult.Success -> IdentityOperationResult.Success(CompletedRecovery(user.id, issued))
                is StoreResult.Failure -> IdentityOperationResult.Failure(committed.error.toIdentityErrorCode())
            }
        } finally {
            parsed.secret.fill(0)
        }
    }

    private class ParsedRecoveryCode(val selector: String, val secret: ByteArray)

    private fun parseRecoveryCode(value: String): ParsedRecoveryCode? {
        if (value.length !in 20..128 || value.count { it == '.' } != 1) return null
        val selector = value.substringBefore('.')
        if (!Regex("[A-Za-z0-9_-]{6,64}").matches(selector)) return null
        val selectorBytes = runCatching {
            Base64Url.decode(selector, maximumBytes = RECOVERY_SELECTOR_BYTES)
        }.getOrNull() ?: return null
        if (selectorBytes.size != RECOVERY_SELECTOR_BYTES) {
            selectorBytes.fill(0)
            return null
        }
        selectorBytes.fill(0)
        val secret = runCatching {
            Base64Url.decode(value.substringAfter('.'), maximumBytes = RECOVERY_SECRET_BYTES)
        }.getOrNull() ?: return null
        if (secret.size != RECOVERY_SECRET_BYTES) {
            secret.fill(0)
            return null
        }
        return ParsedRecoveryCode(selector, secret)
    }

    private suspend fun recoveryDigest(
        pepper: IdentitySecret,
        selector: String,
        secret: ByteArray
    ): ByteArray {
        val input = "aether-recovery-code-v1\u0000$selector\u0000".encodeToByteArray() + secret
        return try {
            runtime.crypto.hmacSha256(pepper, input)
        } finally {
            input.fill(0)
        }
    }

    companion object {
        const val RECOVERY_CODE_COUNT: Int = 10
        const val RECOVERY_SELECTOR_BYTES: Int = 8
        const val RECOVERY_SECRET_BYTES: Int = 16
    }
}

private const val RECOVERY_CODE_COUNT = 10
