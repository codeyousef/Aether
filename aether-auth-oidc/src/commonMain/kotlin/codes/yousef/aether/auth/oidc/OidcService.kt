package codes.yousef.aether.auth.oidc

import codes.yousef.aether.auth.AuditAction
import codes.yousef.aether.auth.AuditActor
import codes.yousef.aether.auth.AuditActorType
import codes.yousef.aether.auth.AuditEvent
import codes.yousef.aether.auth.AuditOutcome
import codes.yousef.aether.auth.AuditTarget
import codes.yousef.aether.auth.AuditTargetType
import codes.yousef.aether.auth.AcquireFederationProviderLeaseCommand
import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.Challenge
import codes.yousef.aether.auth.ChallengePurpose
import codes.yousef.aether.auth.ChallengeState
import codes.yousef.aether.auth.ConsumeChallengeCommand
import codes.yousef.aether.auth.CreateChallengeCommand
import codes.yousef.aether.auth.DigestAlgorithm
import codes.yousef.aether.auth.ExternalIdentity
import codes.yousef.aether.auth.ExternalIdentityReplayReceipt
import codes.yousef.aether.auth.ExternalIdentityState
import codes.yousef.aether.auth.FederationJitProvisioning
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.IdentityHttpMethod
import codes.yousef.aether.auth.IdentityHttpRequest
import codes.yousef.aether.auth.IdentityIdFactory
import codes.yousef.aether.auth.IdentityRuntime
import codes.yousef.aether.auth.IdentityStore
import codes.yousef.aether.auth.IdentityStoreErrorCode
import codes.yousef.aether.auth.LinkExternalIdentityCommand
import codes.yousef.aether.auth.Membership
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.RecordExternalIdentityReplayCommand
import codes.yousef.aether.auth.SecretDigest
import codes.yousef.aether.auth.StoreResult
import codes.yousef.aether.auth.User
import codes.yousef.aether.auth.UserId
import codes.yousef.aether.auth.UserState
import kotlin.coroutines.cancellation.CancellationException

/** Tenant-scoped Authorization Code + PKCE OIDC adapter. */
class OidcIdentityProvider(
    private val config: OidcProviderConfig,
    private val runtime: IdentityRuntime,
    private val store: IdentityStore
) : OidcFederationProvider {
    override val configuredTenantId get() = config.tenantId
    override val configuredProviderId get() = config.providerId
    private val ids = IdentityIdFactory(runtime)
    private val documents = OidcProviderDocuments(config, runtime)
    private val tokenVerifier = OidcIdTokenVerifier(config, runtime, documents)

    override suspend fun beginAuthorization(request: OidcAuthorizationRequest): OidcResult<OidcAuthorizationStart> =
        runOidc {
            requireConfiguredEnabled()
            val now = runtime.clock.now()
            val providerKey = providerStorageKey(config, runtime.crypto)
            val providerLease = acquireProviderLease(providerKey, now)
            val metadata = documents.metadata()
            val expiresAt = now + config.transactionLifetime
            val challengeId = ids.newChallengeId()
            val stateEntropy = runtime.secureRandom.nextBytes(32)
            val nonceBytes = runtime.secureRandom.nextBytes(32)
            val verifierBytes = runtime.secureRandom.nextBytes(32)
            if (stateEntropy.size != 32 || nonceBytes.size != 32 || verifierBytes.size != 32) {
                stateEntropy.fill(0); nonceBytes.fill(0); verifierBytes.fill(0)
                oidcAbort(OidcErrorCode.STORE_UNAVAILABLE)
            }
            try {
                val state = "${challengeId.value}.${Base64Url.encode(stateEntropy)}"
                val nonce = Base64Url.encode(nonceBytes)
                val verifier = Base64Url.encode(verifierBytes)
                val codeChallengeDigest = runtime.crypto.sha256(verifier.encodeToByteArray())
                val codeChallenge = try {
                    if (codeChallengeDigest.size != 32) oidcAbort(OidcErrorCode.STORE_UNAVAILABLE)
                    Base64Url.encode(codeChallengeDigest)
                } finally {
                    codeChallengeDigest.fill(0)
                }
                val bindingBytes = request.callbackBindingBytes()
                val challenge = try {
                    Challenge(
                        id = challengeId,
                        purpose = ChallengePurpose.EXTERNAL_IDENTITY_LINK,
                        challengeDigest = sha256Digest(state.encodeToByteArray()),
                        bindingDigest = sha256Digest(lengthPrefixed(providerKey.encodeToByteArray(), bindingBytes)),
                        payloadDigest = sha256Digest(nonce.encodeToByteArray()),
                        userId = request.linkToUserId,
                        organizationId = config.tenantId,
                        federationProviderLease = providerLease,
                        createdAt = now,
                        expiresAt = expiresAt
                    )
                } finally {
                    bindingBytes.fill(0)
                }
                when (val created = store.createChallenge(CreateChallengeCommand(challenge))) {
                    is StoreResult.Failure -> mapStoreFailure(created.error.code)
                    is StoreResult.Success -> Unit
                }
                val authorizationUrl = appendQuery(
                    metadata.authorizationEndpoint,
                    listOf(
                        "response_type" to "code",
                        "client_id" to config.clientId,
                        "redirect_uri" to config.redirectUri,
                        "scope" to config.scopes.sorted().joinToString(" "),
                        "state" to state,
                        "nonce" to nonce,
                        "code_challenge" to codeChallenge,
                        "code_challenge_method" to "S256"
                    )
                )
                OidcAuthorizationStart(
                    authorizationUrl = authorizationUrl,
                    callbackSecret = OidcCallbackSecret(challengeId, verifierBytes),
                    providerLease = providerLease,
                    expiresAt = expiresAt
                )
            } finally {
                stateEntropy.fill(0)
                nonceBytes.fill(0)
                verifierBytes.fill(0)
            }
        }

    override suspend fun completeAuthorization(request: OidcCallbackRequest): OidcResult<OidcAuthenticationResult> =
        runOidc {
            requireConfiguredEnabled()
            validateRequestLease(request.providerLease)
            validateProviderLease(request.providerLease)
            val challengeId = parseChallengeId(request.state)
            if (request.callbackSecret.challengeId != challengeId) oidcAbort(OidcErrorCode.INVALID_STATE)
            val challenge = loadPendingChallenge(challengeId, request.providerLease)
            validateChallengeBinding(challenge, request, request.providerLease)

            // Every correctly bound callback attempt is single-use before network or assertion
            // work. This prevents concurrent exchanges and makes malformed assertions fail closed.
            val consumedAt = runtime.clock.now()
            when (val consumed = store.consumeChallenge(
                ConsumeChallengeCommand(
                    challengeId = challenge.id,
                    expectedVersion = challenge.version,
                    terminalState = ChallengeState.CONSUMED,
                    consumedAt = consumedAt,
                    federationProviderLease = request.providerLease
                )
            )) {
                is StoreResult.Success -> Unit
                is StoreResult.Failure -> when (consumed.error.code) {
                    IdentityStoreErrorCode.CHALLENGE_EXPIRED -> oidcAbort(OidcErrorCode.TRANSACTION_EXPIRED)
                    IdentityStoreErrorCode.CHALLENGE_NOT_PENDING,
                    IdentityStoreErrorCode.NOT_FOUND,
                    IdentityStoreErrorCode.VERSION_CONFLICT,
                    IdentityStoreErrorCode.INVALID_TRANSITION -> oidcAbort(OidcErrorCode.INVALID_STATE)
                    else -> mapStoreFailure(consumed.error.code)
                }
            }

            val metadata = documents.metadata()
            val idToken = request.callbackSecret.useVerifier { verifier ->
                exchangeCode(metadata, request.authorizationCode, verifier)
            }

            val nonceDigest = challenge.payloadDigest?.decodeSha256Digest()
                ?: oidcAbort(OidcErrorCode.INVALID_STATE)
            val verified = try {
                tokenVerifier.verify(idToken, nonceDigest)
            } finally {
                nonceDigest.fill(0)
            }
            try {
                validateProviderLease(request.providerLease)
                resolveIdentity(challenge.userId, verified, request, request.providerLease)
            } finally {
                verified.assertionDigest.fill(0)
            }
        }

    private suspend fun resolveIdentity(
        linkToUserId: UserId?,
        verified: VerifiedIdToken,
        request: OidcCallbackRequest,
        providerLease: FederationProviderLease
    ): OidcAuthenticationResult {
        val providerKey = providerLease.storageKey
        val subject = verified.claims.subject
        val existing = when (val found = store.findExternalIdentity(providerKey, subject)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> mapStoreFailure(found.error.code)
        }
        val receivedAt = runtime.clock.now()
        val replayExpiresAt = verified.claims.expiresAt + config.clockSkew
        if (replayExpiresAt <= receivedAt) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        val receipt = ExternalIdentityReplayReceipt(
            id = ids.newExternalReplayReceiptId(),
            provider = providerKey,
            assertionDigest = SecretDigest(DigestAlgorithm.SHA256, Base64Url.encode(verified.assertionDigest)),
            receivedAt = receivedAt,
            expiresAt = replayExpiresAt
        )

        val identity = if (existing != null) {
            if (existing.state != ExternalIdentityState.ACTIVE ||
                (linkToUserId != null && existing.userId != linkToUserId)
            ) {
                oidcAbort(OidcErrorCode.EXTERNAL_IDENTITY_CONFLICT)
            }
            when (val replay = store.recordExternalIdentityReplay(
                RecordExternalIdentityReplayCommand(receipt, providerLease)
            )) {
                is StoreResult.Success -> Unit
                is StoreResult.Failure -> mapReplayFailure(replay.error.code)
            }
            existing
        } else {
            val now = runtime.clock.now()
            val jitProvisioning = if (linkToUserId == null) {
                newJitProvisioning(verified.claims, now)
            } else {
                requireActiveUser(linkToUserId)
                null
            }
            val userId = linkToUserId ?: requireNotNull(jitProvisioning).user.id
            val created = ExternalIdentity(
                id = ids.newExternalIdentityId(),
                userId = userId,
                provider = providerKey,
                subject = subject,
                email = verified.claims.email,
                createdAt = now,
                updatedAt = now,
                lastAuthenticatedAt = now
            )
            val actor = if (linkToUserId == null) {
                AuditActor(AuditActorType.SYSTEM)
            } else {
                AuditActor(AuditActorType.USER, userId = linkToUserId)
            }
            val audit = AuditEvent(
                id = ids.newAuditEventId(),
                actor = actor,
                organizationId = config.tenantId,
                action = AuditAction.EXTERNAL_IDENTITY_LINKED,
                target = AuditTarget(AuditTargetType.EXTERNAL_IDENTITY, created.id.value),
                outcome = AuditOutcome.SUCCEEDED,
                request = request.auditRequest,
                occurredAt = now
            )
            when (val linked = store.linkExternalIdentity(
                LinkExternalIdentityCommand(
                    identity = created,
                    replayReceipt = receipt,
                    federationProviderLease = providerLease,
                    auditEvent = audit,
                    jitProvisioning = jitProvisioning
                )
            )) {
                is StoreResult.Success -> {
                    if (jitProvisioning != null &&
                        (linked.value.provisionedUser != jitProvisioning.user ||
                            linked.value.provisionedMembership != jitProvisioning.membership)
                    ) {
                        oidcAbort(OidcErrorCode.STORE_UNAVAILABLE)
                    }
                    linked.value.identity
                }
                is StoreResult.Failure -> when (linked.error.code) {
                    IdentityStoreErrorCode.REPLAY_DETECTED -> oidcAbort(OidcErrorCode.ASSERTION_REPLAYED)
                    IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
                    IdentityStoreErrorCode.NOT_FOUND -> oidcAbort(OidcErrorCode.PROVIDER_DISABLED)
                    IdentityStoreErrorCode.ALREADY_EXISTS,
                    IdentityStoreErrorCode.UNIQUE_CONSTRAINT -> oidcAbort(OidcErrorCode.EXTERNAL_IDENTITY_CONFLICT)
                    else -> mapStoreFailure(linked.error.code)
                }
            }
        }
        requireActiveUser(identity.userId)
        return OidcAuthenticationResult(
            userId = identity.userId,
            externalIdentityId = identity.id,
            providerLease = providerLease,
            claims = verified.claims
        )
    }

    private fun newJitProvisioning(claims: OidcVerifiedClaims, now: kotlin.time.Instant): FederationJitProvisioning {
        if (!config.jitProvisioningEnabled) oidcAbort(OidcErrorCode.EXTERNAL_IDENTITY_NOT_LINKED)
        val userId = ids.newUserId()
        val user = User(
            id = userId,
            state = UserState.ACTIVE,
            displayName = claims.displayName?.trim()?.takeIf(String::isNotEmpty) ?: "Federated user",
            primaryEmail = null,
            createdAt = now,
            updatedAt = now,
            activatedAt = now
        )
        val membership = Membership(
            id = ids.newMembershipId(),
            organizationId = config.tenantId,
            userId = userId,
            role = OrganizationRole.VIEWER,
            createdAt = now,
            updatedAt = now
        )
        return FederationJitProvisioning(user, membership)
    }

    private suspend fun requireActiveUser(userId: UserId) {
        val user = when (val found = store.findUser(userId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> mapStoreFailure(found.error.code)
        }
        if (user == null || user.state != UserState.ACTIVE) oidcAbort(OidcErrorCode.EXTERNAL_IDENTITY_CONFLICT)
    }

    private suspend fun loadPendingChallenge(
        id: codes.yousef.aether.auth.ChallengeId,
        providerLease: FederationProviderLease
    ): Challenge {
        val challenge = when (val found = store.findChallenge(id)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> if (found.error.code == IdentityStoreErrorCode.NOT_FOUND) {
                oidcAbort(OidcErrorCode.INVALID_STATE)
            } else {
                mapStoreFailure(found.error.code)
            }
        } ?: oidcAbort(OidcErrorCode.INVALID_STATE)
        if (challenge.purpose != ChallengePurpose.EXTERNAL_IDENTITY_LINK ||
            challenge.organizationId != config.tenantId ||
            challenge.federationProviderLease != providerLease ||
            challenge.state != ChallengeState.PENDING
        ) {
            oidcAbort(OidcErrorCode.INVALID_STATE)
        }
        if (challenge.expiresAt <= runtime.clock.now()) oidcAbort(OidcErrorCode.TRANSACTION_EXPIRED)
        return challenge
    }

    private suspend fun validateChallengeBinding(
        challenge: Challenge,
        request: OidcCallbackRequest,
        providerLease: FederationProviderLease
    ) {
        val stateDigest = runtime.crypto.sha256(request.state.encodeToByteArray())
        val stateMatches = try {
            compareDigest(challenge.challengeDigest, stateDigest)
        } finally {
            stateDigest.fill(0)
        }
        if (!stateMatches) oidcAbort(OidcErrorCode.INVALID_STATE)

        val binding = request.callbackBindingBytes()
        val encodedBinding = lengthPrefixed(providerLease.storageKey.encodeToByteArray(), binding)
        binding.fill(0)
        val bindingDigest = try {
            runtime.crypto.sha256(encodedBinding)
        } finally {
            encodedBinding.fill(0)
        }
        val bindingMatches = try {
            compareDigest(challenge.bindingDigest, bindingDigest)
        } finally {
            bindingDigest.fill(0)
        }
        if (!bindingMatches) oidcAbort(OidcErrorCode.INVALID_STATE)
    }

    private suspend fun exchangeCode(metadata: OidcMetadata, code: String, verifier: String): String {
        val parameters = mutableListOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to config.redirectUri,
            "client_id" to config.clientId,
            "code_verifier" to verifier
        )
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/x-www-form-urlencoded"
        )
        config.clientSecretReference?.let { reference ->
            val secret = try {
                runtime.secrets.resolve(reference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                oidcAbort(OidcErrorCode.TOKEN_EXCHANGE_FAILED)
            }
            secret.useBytes { bytes ->
                if (bytes.size !in 1..4_096) oidcAbort(OidcErrorCode.TOKEN_EXCHANGE_FAILED)
                val credentials = percentEncode(config.clientId).encodeToByteArray() + byteArrayOf(':'.code.toByte()) +
                    percentEncode(bytes).encodeToByteArray()
                try {
                    headers["Authorization"] = "Basic ${standardBase64(credentials)}"
                } finally {
                    credentials.fill(0)
                }
            }
            parameters.removeAll { it.first == "client_id" }
        }
        val body = formEncode(parameters)
        val response = try {
            runtime.http.execute(
                IdentityHttpRequest(
                    IdentityHttpMethod.POST,
                    metadata.tokenEndpoint,
                    headers,
                    body,
                    maximumResponseBytes = config.maximumTokenResponseBytes
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            oidcAbort(OidcErrorCode.TOKEN_EXCHANGE_FAILED)
        } finally {
            body.fill(0)
            headers["Authorization"] = "<cleared>"
        }
        if (response.statusCode !in 200..299) oidcAbort(OidcErrorCode.TOKEN_EXCHANGE_FAILED)
        val responseBytes = response.bodyBytes()
        if (responseBytes.size > config.maximumTokenResponseBytes) {
            responseBytes.fill(0)
            oidcAbort(OidcErrorCode.TOKEN_EXCHANGE_FAILED)
        }
        val document = try {
            BoundedJson.parseObject(responseBytes, config.maximumTokenResponseBytes)
        } catch (_: OidcAbort) {
            oidcAbort(OidcErrorCode.TOKEN_EXCHANGE_FAILED)
        } finally {
            responseBytes.fill(0)
        }
        val tokenType = document.optionalString("token_type", 32)
        if (tokenType != null && !tokenType.equals("Bearer", ignoreCase = true)) {
            oidcAbort(OidcErrorCode.TOKEN_EXCHANGE_FAILED)
        }
        return try {
            document.requiredString("id_token", config.maximumIdTokenBytes)
        } catch (_: OidcAbort) {
            oidcAbort(OidcErrorCode.TOKEN_EXCHANGE_FAILED)
        }
    }

    private suspend fun sha256Digest(value: ByteArray): SecretDigest {
        val digest = try {
            runtime.crypto.sha256(value)
        } finally {
            value.fill(0)
        }
        return try {
            if (digest.size != 32) oidcAbort(OidcErrorCode.STORE_UNAVAILABLE)
            SecretDigest(DigestAlgorithm.SHA256, Base64Url.encode(digest))
        } finally {
            digest.fill(0)
        }
    }

    private suspend fun compareDigest(expected: SecretDigest, actual: ByteArray): Boolean {
        if (expected.algorithm != DigestAlgorithm.SHA256 || expected.keyVersion != null) return false
        val expectedBytes = try {
            Base64Url.decode(expected.encoded, 32)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            expectedBytes.size == 32 && actual.size == 32 && runtime.crypto.constantTimeEquals(expectedBytes, actual)
        } finally {
            expectedBytes.fill(0)
        }
    }

    private fun SecretDigest.decodeSha256Digest(): ByteArray {
        if (algorithm != DigestAlgorithm.SHA256 || keyVersion != null) oidcAbort(OidcErrorCode.INVALID_STATE)
        return try {
            Base64Url.decode(encoded, 32).also { if (it.size != 32) oidcAbort(OidcErrorCode.INVALID_STATE) }
        } catch (_: IllegalArgumentException) {
            oidcAbort(OidcErrorCode.INVALID_STATE)
        }
    }

    private fun parseChallengeId(state: String): codes.yousef.aether.auth.ChallengeId {
        val separator = state.indexOf('.')
        if (separator <= 0 || separator == state.lastIndex || state.indexOf('.', separator + 1) >= 0) {
            oidcAbort(OidcErrorCode.INVALID_STATE)
        }
        if (state.substring(separator + 1).length != 43) oidcAbort(OidcErrorCode.INVALID_STATE)
        try {
            Base64Url.decode(state.substring(separator + 1), 32).also {
                if (it.size != 32) oidcAbort(OidcErrorCode.INVALID_STATE)
                it.fill(0)
            }
            return codes.yousef.aether.auth.ChallengeId.parse(state.substring(0, separator))
        } catch (_: IllegalArgumentException) {
            oidcAbort(OidcErrorCode.INVALID_STATE)
        }
    }

    private fun requireConfiguredEnabled() {
        if (!config.enabled) oidcAbort(OidcErrorCode.PROVIDER_DISABLED)
    }

    private suspend fun acquireProviderLease(
        storageKey: String,
        acquiredAt: kotlin.time.Instant
    ): FederationProviderLease = when (val acquired = store.acquireFederationProviderLease(
        AcquireFederationProviderLeaseCommand(
            organizationId = config.tenantId,
            kind = FederationProviderKind.OIDC,
            providerId = config.providerId,
            storageKey = storageKey,
            acquiredAt = acquiredAt
        )
    )) {
        is StoreResult.Success -> acquired.value
        is StoreResult.Failure -> mapProviderLeaseFailure(acquired.error.code)
    }

    private suspend fun validateRequestLease(lease: FederationProviderLease) {
        if (lease.organizationId != config.tenantId ||
            lease.kind != FederationProviderKind.OIDC ||
            lease.providerId != config.providerId ||
            lease.storageKey != providerStorageKey(config, runtime.crypto)
        ) {
            oidcAbort(OidcErrorCode.INVALID_STATE)
        }
    }

    private suspend fun validateProviderLease(lease: FederationProviderLease) {
        when (val validated = store.validateFederationProviderLease(lease)) {
            is StoreResult.Success -> if (validated.value != lease) oidcAbort(OidcErrorCode.INVALID_STATE)
            is StoreResult.Failure -> mapProviderLeaseFailure(validated.error.code)
        }
    }

    private fun mapProviderLeaseFailure(code: IdentityStoreErrorCode): Nothing = when (code) {
        IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
        IdentityStoreErrorCode.NOT_FOUND,
        IdentityStoreErrorCode.ALREADY_EXISTS,
        IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
        IdentityStoreErrorCode.INVALID_TRANSITION -> oidcAbort(OidcErrorCode.PROVIDER_DISABLED)
        else -> mapStoreFailure(code)
    }

    private fun mapReplayFailure(code: IdentityStoreErrorCode): Nothing = when (code) {
        IdentityStoreErrorCode.REPLAY_DETECTED,
        IdentityStoreErrorCode.ALREADY_EXISTS,
        IdentityStoreErrorCode.UNIQUE_CONSTRAINT -> oidcAbort(OidcErrorCode.ASSERTION_REPLAYED)
        IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
        IdentityStoreErrorCode.NOT_FOUND -> oidcAbort(OidcErrorCode.PROVIDER_DISABLED)
        else -> mapStoreFailure(code)
    }

    private fun mapStoreFailure(code: IdentityStoreErrorCode): Nothing = when (code) {
        IdentityStoreErrorCode.UNAVAILABLE,
        IdentityStoreErrorCode.INTERNAL,
        IdentityStoreErrorCode.VERSION_CONFLICT -> oidcAbort(OidcErrorCode.STORE_UNAVAILABLE)
        IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED -> oidcAbort(OidcErrorCode.PROVIDER_DISABLED)
        IdentityStoreErrorCode.REPLAY_DETECTED -> oidcAbort(OidcErrorCode.ASSERTION_REPLAYED)
        else -> oidcAbort(OidcErrorCode.EXTERNAL_IDENTITY_CONFLICT)
    }
}

private suspend fun <T> runOidc(block: suspend () -> T): OidcResult<T> = try {
    OidcResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: OidcAbort) {
    OidcResult.Failure(OidcError(failure.code))
} catch (_: IllegalArgumentException) {
    OidcResult.Failure(OidcError(OidcErrorCode.INVALID_CALLBACK))
} catch (_: Exception) {
    OidcResult.Failure(OidcError(OidcErrorCode.STORE_UNAVAILABLE))
}
