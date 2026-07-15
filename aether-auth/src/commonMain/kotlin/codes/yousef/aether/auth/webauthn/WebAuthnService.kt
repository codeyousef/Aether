package codes.yousef.aether.auth.webauthn

import codes.yousef.aether.auth.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Opaque, same-site ceremony cookie value used to bind start and finish requests. */
class WebAuthnCeremonyBinding private constructor(private val bytes: ByteArray) {
    suspend fun <T> useBytes(block: suspend (ByteArray) -> T): T {
        val copy = bytes.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    override fun toString(): String = "WebAuthnCeremonyBinding(<redacted>)"

    companion object {
        fun fromEncoded(encoded: String): WebAuthnCeremonyBinding {
            val decoded = Base64Url.decode(encoded, maximumBytes = BINDING_BYTES)
            require(decoded.size == BINDING_BYTES) { "Invalid WebAuthn ceremony binding" }
            return WebAuthnCeremonyBinding(decoded)
        }

        internal fun fromBytes(bytes: ByteArray): WebAuthnCeremonyBinding {
            require(bytes.size == BINDING_BYTES) { "Invalid WebAuthn ceremony binding" }
            return WebAuthnCeremonyBinding(bytes.copyOf())
        }

        const val BINDING_BYTES: Int = 32
    }
}

/** Fresh binding material. Send [cookieValue] only as a Secure, HttpOnly, SameSite cookie. */
class IssuedWebAuthnCeremonyBinding internal constructor(
    val binding: WebAuthnCeremonyBinding,
    private val encoded: String
) {
    fun cookieValue(): String = encoded
    override fun toString(): String = "IssuedWebAuthnCeremonyBinding(<redacted>)"
}

fun issueWebAuthnCeremonyBinding(runtime: IdentityRuntime): IssuedWebAuthnCeremonyBinding {
    val bytes = runtime.secureRandom.nextBytes(WebAuthnCeremonyBinding.BINDING_BYTES)
    require(bytes.size == WebAuthnCeremonyBinding.BINDING_BYTES) {
        "Secure random provider returned an invalid ceremony binding"
    }
    return try {
        IssuedWebAuthnCeremonyBinding(
            binding = WebAuthnCeremonyBinding.fromBytes(bytes),
            encoded = Base64Url.encode(bytes)
        )
    } finally {
        bytes.fill(0)
    }
}

data class CompletedWebAuthnRegistration(
    val credential: Credential,
    val replacementRecoveryCodes: IssuedRecoveryCodeGeneration? = null,
    val clearRecoverySessionCookie: Boolean = false
) {
    init {
        require((replacementRecoveryCodes == null) == !clearRecoverySessionCookie) {
            "Recovery-code replacement and recovery-cookie clearing must be returned together"
        }
    }
}

data class CompletedWebAuthnAuthentication(
    val credential: Credential,
    val issuedSession: IssuedIdentitySession
)

/**
 * Storage-neutral WebAuthn Level 3 ceremony engine for discoverable, UV-required ES256 passkeys.
 * HTTP integrations must cap the complete request body before deserializing the DTOs in this file.
 */
class WebAuthnService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    private val decoder: WebAuthnProtocolDecoder = WebAuthnProtocolDecoder(),
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime),
    private val sessions: IdentitySessionIssuer = IdentitySessionIssuer(runtime, config, ids)
) {
    suspend fun startRegistration(
        userId: UserId,
        binding: WebAuthnCeremonyBinding,
        registrationSource: IdentityRegistrationSource
    ): IdentityOperationResult<WebAuthnRegistrationStartResponse> {
        if (!config.allowsRegistration(registrationSource)) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REGISTRATION_NOT_ALLOWED)
        }
        val user = when (val result = store.findUser(userId)) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
        } ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        if (user.state != UserState.ACTIVE) return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        val credentials = when (val result = store.listCredentialsForUser(userId)) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
        }
        val (challenge, encodedChallenge) = newChallenge(
            purpose = ChallengePurpose.WEBAUTHN_REGISTRATION,
            userId = user.id,
            binding = binding,
            registrationSource = registrationSource
        )
        return when (val created = store.createChallenge(CreateChallengeCommand(challenge))) {
            is StoreResult.Failure -> IdentityOperationResult.Failure(created.error.toIdentityErrorCode())
            is StoreResult.Success -> IdentityOperationResult.Success(
                WebAuthnRegistrationStartResponse(
                    ceremonyId = challenge.id,
                    publicKey = PublicKeyCredentialCreationOptions(
                        challenge = encodedChallenge,
                        rp = PublicKeyCredentialRpEntity(config.relyingParty.id, config.relyingParty.name),
                        user = PublicKeyCredentialUserEntity(
                            id = Base64Url.encode(user.id.value.encodeToByteArray()),
                            name = fitUtf8(user.primaryEmail?.value ?: user.id.value, 64),
                            displayName = fitUtf8(user.displayName, 64)
                        ),
                        timeout = config.lifetimes.challenge.seconds * 1_000,
                        excludeCredentials = credentials
                            .filter { it.state != CredentialState.REVOKED }
                            .sortedBy { it.id.value }
                            .map { credential ->
                                PublicKeyCredentialDescriptor(
                                    id = credential.webAuthnId.encoded,
                                    transports = credential.transports.map(::transportWireValue).sorted()
                                )
                            }
                    )
                )
            )
        }
    }

    /**
     * Fails a ceremony whose opaque ID was recovered from a bounded finish envelope even though the
     * full browser credential DTO was malformed or rejected before protocol decoding.
     *
     * Keeping this transition in the ceremony engine preserves the same consume-once store command
     * and audit behavior as every other finish failure. An unknown/already-consumed ID deliberately
     * returns [code] as well, so callers do not gain a challenge-existence oracle.
     */
    internal suspend fun rejectFinishAttempt(
        ceremonyId: ChallengeId,
        code: IdentityErrorCode,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Unit> {
        val now = runtime.clock.now()
        val challenge = pendingChallenge(ceremonyId)
            ?: return IdentityOperationResult.Failure(code)
        return failChallenge(
            challenge = challenge,
            at = now,
            code = code,
            request = request,
            terminalState = if (now >= challenge.expiresAt) ChallengeState.EXPIRED else ChallengeState.FAILED,
            reasonCode = if (now >= challenge.expiresAt) {
                "webauthn_challenge_expired"
            } else {
                code.wireName
            }
        )
    }

    suspend fun finishRegistration(
        ceremonyId: ChallengeId,
        credentialName: String,
        browserCredential: RegistrationPublicKeyCredentialDto,
        binding: WebAuthnCeremonyBinding,
        registrationSource: IdentityRegistrationSource,
        request: AuditRequestMetadata? = null,
        recoverySession: IdentitySession? = null,
        /** HTTP authorities should bind completion to the currently authenticated user. */
        expectedUserId: UserId? = null
    ): IdentityOperationResult<CompletedWebAuthnRegistration> {
        val now = runtime.clock.now()
        val challenge = pendingChallenge(ceremonyId)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.CHALLENGE_INVALID)
        if (now >= challenge.expiresAt) {
            return failChallenge(
                challenge = challenge,
                at = now,
                code = IdentityErrorCode.CHALLENGE_INVALID,
                request = request,
                terminalState = ChallengeState.EXPIRED,
                reasonCode = "webauthn_challenge_expired"
            )
        }
        if (!config.allowsRegistration(registrationSource)) {
            return failChallenge(
                challenge,
                now,
                IdentityErrorCode.REGISTRATION_NOT_ALLOWED,
                request,
                reasonCode = "registration_policy_rejected"
            )
        }
        if (challenge.purpose != ChallengePurpose.WEBAUTHN_REGISTRATION ||
            challenge.userId == null || expectedUserId?.let { it != challenge.userId } == true ||
            credentialName.isBlank() || credentialName.length > 200
        ) return failChallenge(challenge, now, IdentityErrorCode.CHALLENGE_INVALID, request)

        val decoded = try {
            requireValidBrowserCredentialEnvelope(
                browserCredential.id,
                browserCredential.rawId,
                browserCredential.type,
                browserCredential.authenticatorAttachment,
                browserCredential.clientExtensionResults
            )
            requireBoundedRegistrationResponse(browserCredential.response)
            val client = decoder.decodeClientData(
                browserCredential.response.clientDataJSON,
                WebAuthnCeremonyType.REGISTRATION
            )
            val attestation = decoder.decodeNoneAttestation(browserCredential.response.attestationObject)
            RegistrationInput(client, attestation.authenticatorData)
        } catch (_: WebAuthnDecodingException) {
            return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        } catch (_: IllegalArgumentException) {
            return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        }

        if (!validateCeremony(
                challenge,
                decoded.clientData,
                decoded.authenticatorData,
                binding,
                registrationSource
            ) ||
            !decoded.authenticatorData.userPresent || !decoded.authenticatorData.userVerified ||
            decoded.authenticatorData.extensions != null
        ) return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)

        val attested = decoded.authenticatorData.attestedCredentialData
            ?: return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        if (!runtime.crypto.validateP256PublicKey(attested.p256PublicKey)) {
            return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        }
        val rawId = runCatching { WebAuthnCredentialId(browserCredential.rawId) }.getOrNull()
            ?: return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        if (rawId != attested.credentialId) {
            return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        }
        val transports = runCatching { parseTransports(browserCredential.response.transports) }.getOrNull()
            ?: return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        val credentialId = ids.newCredentialId()
        val storedCredential = Credential(
            id = credentialId,
            webAuthnId = attested.credentialId,
            userId = challenge.userId,
            name = credentialName,
            publicKey = attested.cosePublicKey,
            signCount = decoded.authenticatorData.signCount,
            transports = transports,
            backupEligible = decoded.authenticatorData.backupEligible,
            backedUp = decoded.authenticatorData.backedUp,
            discoverable = true,
            createdAt = now,
            updatedAt = now
        )
        if (recoverySession != null) {
            if (recoverySession.userId != storedCredential.userId ||
                recoverySession.assurance != AuthenticationAssurance.RECOVERY ||
                recoverySession.state != SessionState.ACTIVE || now >= recoverySession.idleExpiresAt ||
                now >= recoverySession.absoluteExpiresAt
            ) return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
            val user = when (val found = store.findUser(storedCredential.userId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return failChallenge(challenge, now, found.error.toIdentityErrorCode(), request)
            } ?: return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
            if (user.state != UserState.ACTIVE || recoverySession.userSessionEpoch != user.sessionEpoch) {
                return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
            }
            val existingCodes = when (val found = store.listRecoveryCodesForUser(user.id)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return failChallenge(challenge, now, found.error.toIdentityErrorCode(), request)
            }
            val expectedGeneration = existingCodes.maxOfOrNull { it.generation }
            val newGeneration = (expectedGeneration ?: -1L) + 1L
            val generated = generateRecoveryCodes(user.id, newGeneration, now)
            val audit = AuditEvent(
                id = ids.newAuditEventId(),
                actor = AuditActor(AuditActorType.USER, userId = user.id),
                action = AuditAction.RECOVERY_ENROLLMENT_COMPLETED,
                target = AuditTarget(AuditTargetType.USER, user.id.value),
                outcome = AuditOutcome.SUCCEEDED,
                request = request,
                occurredAt = now
            )
            return when (val committed = store.completeRecoveryEnrollment(
                CompleteRecoveryEnrollmentCommand(
                    challengeId = challenge.id,
                    expectedChallengeVersion = challenge.version,
                    credential = storedCredential,
                    recoverySessionId = recoverySession.id,
                    expectedRecoverySessionVersion = recoverySession.version,
                    expectedUserVersion = user.version,
                    expectedSessionEpoch = user.sessionEpoch,
                    newSessionEpoch = user.sessionEpoch + 1,
                    expectedRecoveryGeneration = expectedGeneration,
                    newRecoveryGeneration = newGeneration,
                    replacementRecoveryCodes = generated.stored,
                    completedAt = now,
                    auditEvent = audit,
                    rejectionAuditEvent = storeRejectionAudit(challenge, now, request)
                )
            )) {
                is StoreResult.Success -> committed.value.completion?.let { completion ->
                    IdentityOperationResult.Success(
                        CompletedWebAuthnRegistration(
                            credential = completion.credential,
                            replacementRecoveryCodes = IssuedRecoveryCodeGeneration(newGeneration, generated.issued),
                            clearRecoverySessionCookie = true
                        )
                    )
                } ?: IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
                is StoreResult.Failure -> IdentityOperationResult.Failure(committed.error.toIdentityErrorCode())
            }
        }
        val audit = AuditEvent(
            id = ids.newAuditEventId(),
            actor = AuditActor(AuditActorType.USER, userId = challenge.userId),
            action = AuditAction.CREDENTIAL_REGISTERED,
            target = AuditTarget(AuditTargetType.CREDENTIAL, credentialId.value),
            outcome = AuditOutcome.SUCCEEDED,
            request = request,
            occurredAt = now
        )
        return when (val committed = store.completeCredentialRegistration(
            CompleteCredentialRegistrationCommand(
                challengeId = challenge.id,
                expectedChallengeVersion = challenge.version,
                credential = storedCredential,
                auditEvent = audit,
                rejectionAuditEvent = storeRejectionAudit(challenge, now, request)
            )
        )) {
            is StoreResult.Success -> committed.value.completion?.let { completion ->
                IdentityOperationResult.Success(CompletedWebAuthnRegistration(completion.credential))
            } ?: IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            is StoreResult.Failure -> IdentityOperationResult.Failure(committed.error.toIdentityErrorCode())
        }
    }

    private suspend fun generateRecoveryCodes(
        userId: UserId,
        generation: Long,
        now: Instant
    ): GeneratedRecoveryCodes {
        val issued = mutableListOf<IssuedRecoveryCode>()
        val stored = mutableListOf<RecoveryCode>()
        repeat(IdentityRecoveryService.RECOVERY_CODE_COUNT) {
            val selectorBytes = runtime.secureRandom.nextBytes(IdentityRecoveryService.RECOVERY_SELECTOR_BYTES)
            val secretBytes = runtime.secureRandom.nextBytes(IdentityRecoveryService.RECOVERY_SECRET_BYTES)
            require(selectorBytes.size == IdentityRecoveryService.RECOVERY_SELECTOR_BYTES &&
                secretBytes.size == IdentityRecoveryService.RECOVERY_SECRET_BYTES
            ) { "Secure random provider returned invalid recovery material" }
            try {
                val selector = Base64Url.encode(selectorBytes)
                val input = "aether-recovery-code-v1\u0000$selector\u0000".encodeToByteArray() + secretBytes
                val digest = try {
                    runtime.crypto.hmacSha256(runtime.secrets.resolve(config.keys.recoveryPepper), input)
                } finally {
                    input.fill(0)
                }
                try {
                    require(digest.size == 32) { "HMAC-SHA-256 provider returned an invalid digest" }
                    issued += IssuedRecoveryCode("$selector.${Base64Url.encode(secretBytes)}")
                    stored += RecoveryCode(
                        id = ids.newRecoveryCodeId(),
                        userId = userId,
                        generation = generation,
                        publicSelector = selector,
                        secretDigest = SecretDigest(
                            DigestAlgorithm.HMAC_SHA256,
                            Base64Url.encode(digest),
                            config.keys.recoveryPepper.version
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
        return GeneratedRecoveryCodes(issued, stored)
    }

    private data class GeneratedRecoveryCodes(
        val issued: List<IssuedRecoveryCode>,
        val stored: List<RecoveryCode>
    )

    suspend fun startAuthentication(
        binding: WebAuthnCeremonyBinding
    ): IdentityOperationResult<WebAuthnAuthenticationStartResponse> = startAuthenticationChallenge(
        purpose = ChallengePurpose.WEBAUTHN_AUTHENTICATION,
        userId = null,
        binding = binding,
        allowedCredentials = emptyList()
    )

    suspend fun startStepUp(
        userId: UserId,
        binding: WebAuthnCeremonyBinding
    ): IdentityOperationResult<WebAuthnAuthenticationStartResponse> {
        val credentials = when (val result = store.listCredentialsForUser(userId)) {
            is StoreResult.Success -> result.value.filter { it.state == CredentialState.ACTIVE && it.discoverable }
            is StoreResult.Failure -> return IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
        }
        if (credentials.isEmpty()) return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        return startAuthenticationChallenge(
            purpose = ChallengePurpose.STEP_UP,
            userId = userId,
            binding = binding,
            allowedCredentials = credentials
        )
    }

    suspend fun finishAuthentication(
        ceremonyId: ChallengeId,
        browserCredential: AuthenticationPublicKeyCredentialDto,
        binding: WebAuthnCeremonyBinding,
        rotatedFrom: IdentitySession? = null,
        device: DeviceMetadata = DeviceMetadata(),
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<CompletedWebAuthnAuthentication> {
        val now = runtime.clock.now()
        val challenge = pendingChallenge(ceremonyId)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.CHALLENGE_INVALID)
        if (now >= challenge.expiresAt) {
            return failChallenge(
                challenge = challenge,
                at = now,
                code = IdentityErrorCode.CHALLENGE_INVALID,
                request = request,
                terminalState = ChallengeState.EXPIRED,
                reasonCode = "webauthn_challenge_expired"
            )
        }
        if (challenge.purpose != ChallengePurpose.WEBAUTHN_AUTHENTICATION &&
            challenge.purpose != ChallengePurpose.STEP_UP
        ) return failChallenge(challenge, now, IdentityErrorCode.CHALLENGE_INVALID, request)

        val decoded = try {
            requireValidBrowserCredentialEnvelope(
                browserCredential.id,
                browserCredential.rawId,
                browserCredential.type,
                browserCredential.authenticatorAttachment,
                browserCredential.clientExtensionResults
            )
            requireBoundedAssertionResponse(browserCredential.response)
            val client = decoder.decodeClientData(
                browserCredential.response.clientDataJSON,
                WebAuthnCeremonyType.AUTHENTICATION
            )
            val authenticator = decoder.decodeAuthenticatorData(
                Base64Url.decode(browserCredential.response.authenticatorData, maximumBytes = 65_536)
            )
            val signature = decoder.decodeDerEs256Signature(browserCredential.response.signature)
            AuthenticationInput(client, authenticator, signature)
        } catch (_: WebAuthnDecodingException) {
            return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        } catch (_: IllegalArgumentException) {
            return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        }
        if (!validateCeremony(challenge, decoded.clientData, decoded.authenticatorData, binding) ||
            !decoded.authenticatorData.userPresent || !decoded.authenticatorData.userVerified ||
            decoded.authenticatorData.attestedCredentialData != null ||
            decoded.authenticatorData.extensions != null
        ) return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)

        val webAuthnId = runCatching { WebAuthnCredentialId(browserCredential.rawId) }.getOrNull()
            ?: return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        val credential = when (val found = store.findCredentialByWebAuthnId(webAuthnId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return failChallenge(challenge, now, found.error.toIdentityErrorCode(), request)
        } ?: return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        if (credential.state != CredentialState.ACTIVE || !credential.discoverable ||
            challenge.userId != null && challenge.userId != credential.userId
        ) return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)

        val userHandle = browserCredential.response.userHandle?.let { encoded ->
            runCatching { Base64Url.decode(encoded, maximumBytes = 64) }.getOrNull()
        } ?: return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        val expectedUserHandle = credential.userId.value.encodeToByteArray()
        val userHandleMatches = try {
            runtime.crypto.constantTimeEquals(expectedUserHandle, userHandle)
        } finally {
            expectedUserHandle.fill(0)
            userHandle.fill(0)
        }
        if (!userHandleMatches) return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)

        val clientHash = runtime.crypto.sha256(decoded.clientData.rawJson)
        val signedData = decoded.authenticatorData.rawBytes + clientHash
        clientHash.fill(0)
        val signatureValid = try {
            runtime.crypto.verifyEs256(
                decoder.decodeStoredEs256PublicKey(credential.publicKey),
                signedData,
                decoded.signature
            )
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            signedData.fill(0)
        }
        if (!signatureValid) return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)

        val user = when (val found = store.findUser(credential.userId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return failChallenge(challenge, now, found.error.toIdentityErrorCode(), request)
        } ?: return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        if (user.state != UserState.ACTIVE ||
            rotatedFrom != null && (rotatedFrom.userId != user.id || rotatedFrom.state != SessionState.ACTIVE ||
                rotatedFrom.userSessionEpoch != user.sessionEpoch || now >= rotatedFrom.absoluteExpiresAt)
        ) return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        if (challenge.purpose == ChallengePurpose.STEP_UP && rotatedFrom == null) {
            return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        }

        val counterAnomaly = credential.signCount != 0L && decoded.authenticatorData.signCount != 0L &&
            decoded.authenticatorData.signCount <= credential.signCount
        if (counterAnomaly) {
            val audit = credentialAudit(
                credential = credential,
                action = AuditAction.CREDENTIAL_QUARANTINED,
                outcome = AuditOutcome.DENIED,
                occurredAt = now,
                request = request,
                reasonCode = "signature_counter_anomaly"
            )
            return when (val quarantined = store.quarantineCredentialAuthentication(
                QuarantineCredentialAuthenticationCommand(
                    challengeId = challenge.id,
                    expectedChallengeVersion = challenge.version,
                    credentialId = credential.id,
                    expectedCredentialVersion = credential.version,
                    observedSignCount = decoded.authenticatorData.signCount,
                    backupEligible = decoded.authenticatorData.backupEligible,
                    backedUp = decoded.authenticatorData.backedUp,
                    detectedAt = now,
                    auditEvent = audit,
                    rejectionAuditEvent = storeRejectionAudit(challenge, now, request)
                )
            )) {
                is StoreResult.Success -> IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
                is StoreResult.Failure -> IdentityOperationResult.Failure(quarantined.error.toIdentityErrorCode())
            }
        }
        if (credential.backupEligible != decoded.authenticatorData.backupEligible) {
            return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        }

        val assurance = if (challenge.purpose == ChallengePurpose.STEP_UP) {
            AuthenticationAssurance.STEP_UP
        } else AuthenticationAssurance.PASSKEY
        val issued = try {
            sessions.issue(
                user = user,
                assurance = assurance,
                authenticationMethod = SessionAuthenticationMethod.PASSKEY,
                authenticatedAt = now,
                device = device,
                rotatedFrom = rotatedFrom
            )
        } catch (_: IllegalArgumentException) {
            return failChallenge(challenge, now, IdentityErrorCode.INVALID_CREDENTIALS, request)
        }
        val audit = credentialAudit(
            credential = credential,
            action = AuditAction.CREDENTIAL_AUTHENTICATED,
            outcome = AuditOutcome.SUCCEEDED,
            occurredAt = now,
            request = request
        )
        return when (val committed = store.completeCredentialAuthentication(
            CompleteCredentialAuthenticationCommand(
                challengeId = challenge.id,
                expectedChallengeVersion = challenge.version,
                credentialId = credential.id,
                expectedCredentialVersion = credential.version,
                newSignCount = decoded.authenticatorData.signCount,
                backupEligible = decoded.authenticatorData.backupEligible,
                backedUp = decoded.authenticatorData.backedUp,
                authenticatedAt = now,
                session = issued.session,
                replacedSessionId = rotatedFrom?.id,
                expectedReplacedSessionVersion = rotatedFrom?.version,
                auditEvent = audit,
                rejectionAuditEvent = storeRejectionAudit(challenge, now, request)
            )
        )) {
            is StoreResult.Success -> committed.value.completion?.let { completion ->
                IdentityOperationResult.Success(CompletedWebAuthnAuthentication(completion.credential, issued))
            } ?: IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            is StoreResult.Failure -> IdentityOperationResult.Failure(committed.error.toIdentityErrorCode())
        }
    }

    private suspend fun startAuthenticationChallenge(
        purpose: ChallengePurpose,
        userId: UserId?,
        binding: WebAuthnCeremonyBinding,
        allowedCredentials: List<Credential>
    ): IdentityOperationResult<WebAuthnAuthenticationStartResponse> {
        val (challenge, encodedChallenge) = newChallenge(purpose, userId, binding)
        return when (val created = store.createChallenge(CreateChallengeCommand(challenge))) {
            is StoreResult.Failure -> IdentityOperationResult.Failure(created.error.toIdentityErrorCode())
            is StoreResult.Success -> IdentityOperationResult.Success(
                WebAuthnAuthenticationStartResponse(
                    ceremonyId = challenge.id,
                    publicKey = PublicKeyCredentialRequestOptions(
                        challenge = encodedChallenge,
                        timeout = config.lifetimes.challenge.seconds * 1_000,
                        rpId = config.relyingParty.id,
                        allowCredentials = allowedCredentials.sortedBy { it.id.value }.map { credential ->
                            PublicKeyCredentialDescriptor(
                                id = credential.webAuthnId.encoded,
                                transports = credential.transports.map(::transportWireValue).sorted()
                            )
                        }
                    )
                )
            )
        }
    }

    private suspend fun newChallenge(
        purpose: ChallengePurpose,
        userId: UserId?,
        binding: WebAuthnCeremonyBinding,
        registrationSource: IdentityRegistrationSource? = null
    ): Pair<Challenge, String> {
        val now = runtime.clock.now()
        val challengeBytes = runtime.secureRandom.nextBytes(CHALLENGE_BYTES)
        require(challengeBytes.size == CHALLENGE_BYTES) { "Secure random provider returned an invalid challenge" }
        return try {
            val challengeHash = runtime.crypto.sha256(challengeBytes)
            val bindingHash = binding.useBytes { runtime.crypto.sha256(it) }
            val payloadHash = runtime.crypto.sha256(challengePayload(purpose, registrationSource))
            try {
                Challenge(
                    id = ids.newChallengeId(),
                    purpose = purpose,
                    challengeDigest = shaDigest(challengeHash),
                    bindingDigest = shaDigest(bindingHash),
                    payloadDigest = shaDigest(payloadHash),
                    userId = userId,
                    createdAt = now,
                    expiresAt = now + config.lifetimes.challenge.seconds.seconds
                ) to Base64Url.encode(challengeBytes)
            } finally {
                challengeHash.fill(0)
                bindingHash.fill(0)
                payloadHash.fill(0)
            }
        } finally {
            challengeBytes.fill(0)
        }
    }

    private suspend fun validateCeremony(
        challenge: Challenge,
        client: CollectedClientData,
        authenticator: AuthenticatorData,
        binding: WebAuthnCeremonyBinding,
        registrationSource: IdentityRegistrationSource? = null
    ): Boolean {
        if (client.crossOrigin || client.origin !in config.relyingParty.allowedOrigins) return false
        val challengeHash = runtime.crypto.sha256(client.challenge)
        val bindingHash = binding.useBytes { runtime.crypto.sha256(it) }
        val rpHash = runtime.crypto.sha256(config.relyingParty.id.encodeToByteArray())
        val payloadHash = runtime.crypto.sha256(challengePayload(challenge.purpose, registrationSource))
        return try {
            matchesDigest(challenge.challengeDigest, challengeHash) &&
                matchesDigest(challenge.bindingDigest, bindingHash) &&
                challenge.payloadDigest?.let { matchesDigest(it, payloadHash) } == true &&
                runtime.crypto.constantTimeEquals(rpHash, authenticator.rpIdHash)
        } finally {
            challengeHash.fill(0)
            bindingHash.fill(0)
            rpHash.fill(0)
            payloadHash.fill(0)
        }
    }

    private suspend fun matchesDigest(stored: SecretDigest, actual: ByteArray): Boolean {
        if (stored.algorithm != DigestAlgorithm.SHA256 || stored.keyVersion != null) return false
        val expected = runCatching { Base64Url.decode(stored.encoded, maximumBytes = 32) }.getOrNull()
            ?: return false
        return try {
            expected.size == 32 && actual.size == 32 && runtime.crypto.constantTimeEquals(expected, actual)
        } finally {
            expected.fill(0)
        }
    }

    private suspend fun pendingChallenge(id: ChallengeId): Challenge? =
        when (val result = store.findChallenge(id)) {
            is StoreResult.Failure -> null
            is StoreResult.Success -> result.value?.takeIf {
                it.state == ChallengeState.PENDING
            }
        }

    private suspend fun <T> failChallenge(
        challenge: Challenge,
        at: Instant,
        code: IdentityErrorCode,
        request: AuditRequestMetadata? = null,
        terminalState: ChallengeState = ChallengeState.FAILED,
        reasonCode: String = code.wireName
    ): IdentityOperationResult<T> {
        val audit = AuditEvent(
            id = ids.newAuditEventId(),
            actor = challenge.userId?.let { AuditActor(AuditActorType.USER, userId = it) }
                ?: AuditActor(AuditActorType.ANONYMOUS),
            action = AuditAction.WEBAUTHN_CEREMONY_REJECTED,
            target = AuditTarget(AuditTargetType.CHALLENGE, challenge.id.value),
            outcome = AuditOutcome.DENIED,
            reasonCode = reasonCode,
            request = request,
            occurredAt = at
        )
        val consumed = store.consumeChallenge(
            ConsumeChallengeCommand(
                challengeId = challenge.id,
                expectedVersion = challenge.version,
                terminalState = terminalState,
                consumedAt = at,
                auditEvent = audit
            )
        )
        return if (consumed is StoreResult.Failure &&
            (consumed.error.code == IdentityStoreErrorCode.UNAVAILABLE ||
                consumed.error.code == IdentityStoreErrorCode.INTERNAL)
        ) {
            IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
        } else IdentityOperationResult.Failure(code)
    }

    private fun storeRejectionAudit(
        challenge: Challenge,
        at: Instant,
        request: AuditRequestMetadata?
    ): AuditEvent = AuditEvent(
        id = ids.newAuditEventId(),
        actor = challenge.userId?.let { AuditActor(AuditActorType.USER, userId = it) }
            ?: AuditActor(AuditActorType.ANONYMOUS),
        action = AuditAction.WEBAUTHN_CEREMONY_REJECTED,
        target = AuditTarget(AuditTargetType.CHALLENGE, challenge.id.value),
        outcome = AuditOutcome.DENIED,
        reasonCode = WEBAUTHN_STORE_REJECTION_REASON_CODE,
        request = request,
        occurredAt = at
    )

    private fun credentialAudit(
        credential: Credential,
        action: AuditAction,
        outcome: AuditOutcome,
        occurredAt: Instant,
        request: AuditRequestMetadata?,
        reasonCode: String? = null
    ): AuditEvent = AuditEvent(
        id = ids.newAuditEventId(),
        actor = AuditActor(AuditActorType.USER, userId = credential.userId),
        action = action,
        target = AuditTarget(AuditTargetType.CREDENTIAL, credential.id.value),
        outcome = outcome,
        reasonCode = reasonCode,
        request = request,
        occurredAt = occurredAt
    )

    private fun challengePayload(
        purpose: ChallengePurpose,
        registrationSource: IdentityRegistrationSource? = null
    ): ByteArray =
        "${purpose.name}\u0000${registrationSource?.name ?: "none"}\u0000${config.relyingParty.id}"
            .encodeToByteArray()

    private fun shaDigest(bytes: ByteArray): SecretDigest = SecretDigest(
        algorithm = DigestAlgorithm.SHA256,
        encoded = Base64Url.encode(bytes)
    )

    private data class RegistrationInput(
        val clientData: CollectedClientData,
        val authenticatorData: AuthenticatorData
    )

    private data class AuthenticationInput(
        val clientData: CollectedClientData,
        val authenticatorData: AuthenticatorData,
        val signature: Es256Signature
    )

    companion object {
        const val CHALLENGE_BYTES: Int = 32
    }
}

private fun requireBoundedRegistrationResponse(response: AuthenticatorAttestationResponseDto) {
    if (response.clientDataJSON.length !in 2..87_384 ||
        response.attestationObject.length !in 2..174_764 || response.transports.size > 8 ||
        response.transports.any { it.length !in 2..32 }
    ) throw WebAuthnDecodingException()
}

private fun requireBoundedAssertionResponse(response: AuthenticatorAssertionResponseDto) {
    if (response.clientDataJSON.length !in 2..87_384 ||
        response.authenticatorData.length !in 2..87_384 || response.signature.length !in 8..108 ||
        response.userHandle != null && response.userHandle.length !in 2..86
    ) throw WebAuthnDecodingException()
}

private fun parseTransports(values: List<String>): Set<AuthenticatorTransport> {
    if (values.size > 8 || values.toSet().size != values.size) throw WebAuthnDecodingException()
    return values.map { value ->
        when (value) {
            "usb" -> AuthenticatorTransport.USB
            "nfc" -> AuthenticatorTransport.NFC
            "ble" -> AuthenticatorTransport.BLE
            "internal" -> AuthenticatorTransport.INTERNAL
            "hybrid" -> AuthenticatorTransport.HYBRID
            "smart-card" -> AuthenticatorTransport.SMART_CARD
            else -> throw WebAuthnDecodingException()
        }
    }.toSet()
}

private fun transportWireValue(value: AuthenticatorTransport): String = when (value) {
    AuthenticatorTransport.USB -> "usb"
    AuthenticatorTransport.NFC -> "nfc"
    AuthenticatorTransport.BLE -> "ble"
    AuthenticatorTransport.INTERNAL -> "internal"
    AuthenticatorTransport.HYBRID -> "hybrid"
    AuthenticatorTransport.SMART_CARD -> "smart-card"
}

private fun fitUtf8(value: String, maximumBytes: Int): String {
    require(maximumBytes > 0)
    if (value.encodeToByteArray().size <= maximumBytes) return value
    var end = value.length
    while (end > 0 && value.substring(0, end).encodeToByteArray().size > maximumBytes) end--
    return value.substring(0, end).ifBlank { "user" }
}
