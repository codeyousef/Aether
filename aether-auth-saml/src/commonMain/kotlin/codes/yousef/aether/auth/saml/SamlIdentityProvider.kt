package codes.yousef.aether.auth.saml

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
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.aether.auth.ExternalIdentity
import codes.yousef.aether.auth.ExternalIdentityReplayReceipt
import codes.yousef.aether.auth.ExternalIdentityState
import codes.yousef.aether.auth.FederationJitProvisioning
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.IdentityIdFactory
import codes.yousef.aether.auth.IdentityRuntime
import codes.yousef.aether.auth.IdentityStore
import codes.yousef.aether.auth.IdentityStoreErrorCode
import codes.yousef.aether.auth.LinkExternalIdentityCommand
import codes.yousef.aether.auth.Membership
import codes.yousef.aether.auth.MembershipState
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.RecordExternalIdentityReplayCommand
import codes.yousef.aether.auth.SecretDigest
import codes.yousef.aether.auth.StoreResult
import codes.yousef.aether.auth.User
import codes.yousef.aether.auth.UserId
import codes.yousef.aether.auth.UserState
import kotlin.coroutines.cancellation.CancellationException

/** Tenant-scoped SAML 2.0 SP adapter with an HTTP-Redirect start and HTTP-POST callback. */
class SamlIdentityProvider(
    private val config: SamlProviderConfig,
    private val runtime: IdentityRuntime,
    private val store: IdentityStore,
    private val metadataResolver: SamlMetadataResolver,
    private val redirectSigner: SamlRedirectSigner? = null
) : SamlFederationProvider {
    override val configuredTenantId get() = config.tenantId
    override val configuredProviderId get() = config.providerId
    private val ids = IdentityIdFactory(runtime)
    private val validator = SamlResponseValidator(config, SamlSignatureVerifier(runtime.crypto))

    override suspend fun beginAuthentication(
        request: SamlAuthenticationRequest
    ): SamlResult<SamlAuthenticationStart> = runSaml {
        requireConfiguredEnabled()
        val now = runtime.clock.now()
        val providerKey = samlProviderStorageKey(config, runtime.crypto)
        val providerLease = acquireProviderLease(providerKey, now)
        val metadata = resolveMetadata()
        val challengeId = ids.newChallengeId()
        val requestId = "_${challengeId.value}"
        val relayStateBytes = runtime.secureRandom.nextBytes(32)
        if (relayStateBytes.size != 32) {
            relayStateBytes.fill(0)
            samlAbort(SamlErrorCode.STORE_UNAVAILABLE)
        }
        try {
            val relayState = Base64Url.encode(relayStateBytes)
            val xml = buildAuthnRequest(requestId, metadata.redirectSsoUrl, now)
            val xmlBytes = xml.encodeToByteArray()
            val deflated = try {
                rawDeflateStored(xmlBytes)
            } finally {
                xmlBytes.fill(0)
            }
            val encodedRequest = try {
                SamlBase64.encode(deflated)
            } finally {
                deflated.fill(0)
            }
            var query = "SAMLRequest=${percentEncode(encodedRequest)}&RelayState=${percentEncode(relayState)}"
            val signer = redirectSigner
            if (metadata.wantAuthnRequestsSigned && signer == null) {
                samlAbort(SamlErrorCode.PROVIDER_METADATA_INVALID)
            }
            if (signer != null) {
                val keyId = signer.keyId
                if (keyId.isBlank() || keyId.length > 255 || keyId.any(Char::isWhitespace)) {
                    samlAbort(SamlErrorCode.PROVIDER_METADATA_INVALID)
                }
                query += "&SigAlg=${percentEncode(signer.algorithm.uri)}"
                val signedBytes = query.encodeToByteArray()
                val signature = try {
                    signer.sign(signedBytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    samlAbort(SamlErrorCode.STORE_UNAVAILABLE)
                } finally {
                    signedBytes.fill(0)
                }
                try {
                    val validSize = when (signer.algorithm) {
                        SamlSignatureAlgorithm.RSA_SHA256 -> signature.size in 256..1_024
                        SamlSignatureAlgorithm.ECDSA_SHA256 -> signature.size == 64
                    }
                    if (!validSize) samlAbort(SamlErrorCode.PROVIDER_METADATA_INVALID)
                    query += "&Signature=${percentEncode(SamlBase64.encode(signature))}"
                } finally {
                    signature.fill(0)
                }
            }
            val expiresAt = now + config.requestLifetime
            val requestDigest = sha256Digest(requestId.encodeToByteArray())
            val bindingInput = lengthPrefixed(providerKey.encodeToByteArray(), relayStateBytes)
            val bindingDigest = try {
                sha256Digest(bindingInput)
            } finally {
                bindingInput.fill(0)
            }
            val challenge = Challenge(
                id = challengeId,
                purpose = ChallengePurpose.EXTERNAL_IDENTITY_LINK,
                challengeDigest = requestDigest,
                bindingDigest = bindingDigest,
                userId = request.linkToUserId,
                organizationId = config.tenantId,
                federationProviderLease = providerLease,
                createdAt = now,
                expiresAt = expiresAt
            )
            when (val created = store.createChallenge(CreateChallengeCommand(challenge))) {
                is StoreResult.Success -> Unit
                is StoreResult.Failure -> mapStoreFailure(created.error.code)
            }
            val state = SamlAuthenticationState(
                challengeId,
                requestId,
                relayStateBytes,
                request.linkToUserId,
                providerLease,
                expiresAt
            )
            SamlAuthenticationStart(
                redirectUrl = appendQuery(metadata.redirectSsoUrl, query),
                state = state,
                expiresAt = expiresAt
            )
        } finally {
            relayStateBytes.fill(0)
        }
    }

    override suspend fun completeAuthentication(
        request: SamlPostResponseRequest
    ): SamlResult<SamlAuthenticationResult> = runSaml {
        requireConfiguredEnabled()
        val providerLease = requireCurrentProviderLease(request.state.providerLease)
        val now = runtime.clock.now()
        if (now >= request.state.expiresAt) samlAbort(SamlErrorCode.REQUEST_EXPIRED)
        validateRelayState(request)
        val challenge = loadChallenge(request.state.challengeId, now, providerLease)
        validateChallengeBinding(challenge, request)
        val metadata = resolveMetadata()
        if (request.samlResponse.length > config.maximumEncodedResponseBytes * 2) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        val xmlBytes = try {
            SamlBase64.decode(request.samlResponse, config.maximumXmlBytes)
        } catch (_: IllegalArgumentException) {
            samlAbort(SamlErrorCode.RESPONSE_INVALID)
        }
        val document = try {
            BoundedSamlXml.parse(
                xmlBytes,
                SamlXmlLimits(
                    maximumBytes = config.maximumXmlBytes,
                    maximumDepth = config.maximumXmlDepth,
                    maximumElements = config.maximumElements,
                    maximumAttributesPerElement = config.maximumAttributesPerElement,
                    maximumTextCharacters = config.maximumTextCharacters
                )
            )
        } finally {
            xmlBytes.fill(0)
        }
        val validated = validator.validate(document, request.state.requestId, metadata, now)
        when (val consumed = store.consumeChallenge(
            ConsumeChallengeCommand(
                challengeId = challenge.id,
                expectedVersion = challenge.version,
                terminalState = ChallengeState.CONSUMED,
                consumedAt = now,
                federationProviderLease = providerLease
            )
        )) {
            is StoreResult.Success -> Unit
            is StoreResult.Failure -> when (consumed.error.code) {
                IdentityStoreErrorCode.CHALLENGE_EXPIRED -> samlAbort(SamlErrorCode.REQUEST_EXPIRED)
                IdentityStoreErrorCode.CHALLENGE_NOT_PENDING,
                IdentityStoreErrorCode.VERSION_CONFLICT,
                IdentityStoreErrorCode.INVALID_TRANSITION -> samlAbort(SamlErrorCode.REQUEST_INVALID)
                else -> mapStoreFailure(consumed.error.code)
            }
        }
        resolveIdentity(validated, request, challenge.userId, providerLease)
    }

    private suspend fun resolveIdentity(
        validated: ValidatedSamlResponse,
        request: SamlPostResponseRequest,
        linkToUserId: UserId?,
        providerLease: FederationProviderLease
    ): SamlAuthenticationResult {
        val providerKey = providerLease.storageKey
        val assertionBytes = canonicalizeExclusive(validated.assertion)
        val assertionDigest = try {
            runtime.crypto.sha256(assertionBytes)
        } finally {
            assertionBytes.fill(0)
        }
        if (assertionDigest.size != 32) {
            assertionDigest.fill(0)
            samlAbort(SamlErrorCode.STORE_UNAVAILABLE)
        }
        val now = runtime.clock.now()
        val receipt = try {
            ExternalIdentityReplayReceipt(
                id = ids.newExternalReplayReceiptId(),
                provider = providerKey,
                assertionDigest = SecretDigest(DigestAlgorithm.SHA256, Base64Url.encode(assertionDigest)),
                receivedAt = now,
                expiresAt = maxOf(validated.claims.expiresAt + config.clockSkew, now + config.replayReceiptLifetime)
            )
        } finally {
            assertionDigest.fill(0)
        }
        val subject = validated.claims.subject
        val existing = when (val found = store.findExternalIdentity(providerKey, subject)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> mapStoreFailure(found.error.code)
        }
        requireCurrentProviderLease(providerLease)
        val identity = if (existing != null) {
            if (existing.state != ExternalIdentityState.ACTIVE ||
                (linkToUserId != null && existing.userId != linkToUserId)
            ) {
                samlAbort(SamlErrorCode.EXTERNAL_IDENTITY_CONFLICT)
            }
            when (val replay = store.recordExternalIdentityReplay(
                RecordExternalIdentityReplayCommand(receipt, providerLease)
            )) {
                is StoreResult.Success -> Unit
                is StoreResult.Failure -> mapReplayFailure(replay.error.code)
            }
            existing
        } else {
            val jitProvisioning = if (linkToUserId == null) {
                createJitProvisioning(validated.claims, now)
            } else {
                null
            }
            val userId = linkToUserId ?: requireNotNull(jitProvisioning).user.id
            if (jitProvisioning == null) requireActiveUser(userId)
            val created = ExternalIdentity(
                id = ids.newExternalIdentityId(),
                userId = userId,
                provider = providerKey,
                subject = subject,
                email = findEmailAttribute(validated.claims),
                createdAt = now,
                updatedAt = now,
                lastAuthenticatedAt = now
            )
            val audit = AuditEvent(
                id = ids.newAuditEventId(),
                actor = if (linkToUserId == null) {
                    AuditActor(AuditActorType.SYSTEM)
                } else {
                    AuditActor(AuditActorType.USER, userId = userId)
                },
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
                is StoreResult.Success -> linked.value.identity
                is StoreResult.Failure -> when (linked.error.code) {
                    IdentityStoreErrorCode.REPLAY_DETECTED -> samlAbort(SamlErrorCode.ASSERTION_REPLAYED)
                    IdentityStoreErrorCode.ALREADY_EXISTS,
                    IdentityStoreErrorCode.UNIQUE_CONSTRAINT -> samlAbort(SamlErrorCode.EXTERNAL_IDENTITY_CONFLICT)
                    else -> mapStoreFailure(linked.error.code)
                }
            }
        }
        requireActiveUser(identity.userId)
        return SamlAuthenticationResult(
            userId = identity.userId,
            externalIdentityId = identity.id,
            providerLease = providerLease,
            claims = validated.claims
        )
    }

    private fun createJitProvisioning(
        claims: SamlVerifiedClaims,
        occurredAt: kotlin.time.Instant
    ): FederationJitProvisioning {
        if (!config.jitProvisioningEnabled) samlAbort(SamlErrorCode.EXTERNAL_IDENTITY_NOT_LINKED)
        val userId = ids.newUserId()
        val user = User(
            id = userId,
            state = UserState.ACTIVE,
            displayName = findDisplayNameAttribute(claims) ?: "Federated user",
            primaryEmail = null,
            createdAt = occurredAt,
            updatedAt = occurredAt,
            activatedAt = occurredAt
        )
        val membership = Membership(
            id = ids.newMembershipId(),
            organizationId = config.tenantId,
            userId = userId,
            role = OrganizationRole.VIEWER,
            state = MembershipState.ACTIVE,
            createdAt = occurredAt,
            updatedAt = occurredAt
        )
        return FederationJitProvisioning(user, membership)
    }

    private suspend fun requireActiveUser(userId: UserId) {
        val user = when (val found = store.findUser(userId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> mapStoreFailure(found.error.code)
        }
        if (user == null || user.state != UserState.ACTIVE) samlAbort(SamlErrorCode.EXTERNAL_IDENTITY_CONFLICT)
    }

    private fun findEmailAttribute(claims: SamlVerifiedClaims): EmailAddress? {
        val candidates = listOf(
            "email",
            "mail",
            "urn:oid:0.9.2342.19200300.100.1.3",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress"
        )
        val value = candidates.firstNotNullOfOrNull { claims.attributes[it]?.singleOrNull() } ?: return null
        return try {
            EmailAddress(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun findDisplayNameAttribute(claims: SamlVerifiedClaims): String? {
        val candidates = listOf(
            "displayName",
            "name",
            "urn:oid:2.16.840.1.113730.3.1.241",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name"
        )
        return candidates.firstNotNullOfOrNull { claims.attributes[it]?.singleOrNull() }
            ?.takeIf { it.isNotBlank() && it.length <= 200 }
    }

    private suspend fun validateRelayState(request: SamlPostResponseRequest) {
        val actual = try {
            Base64Url.decode(request.relayState, maximumBytes = 64)
        } catch (_: IllegalArgumentException) {
            samlAbort(SamlErrorCode.REQUEST_INVALID)
        }
        val expected = request.state.relayStateBytes()
        val matches = try {
            actual.size == 32 && expected.size == 32 && runtime.crypto.constantTimeEquals(expected, actual)
        } finally {
            actual.fill(0)
            expected.fill(0)
        }
        if (!matches) samlAbort(SamlErrorCode.REQUEST_INVALID)
    }

    private suspend fun loadChallenge(
        id: codes.yousef.aether.auth.ChallengeId,
        now: kotlin.time.Instant,
        expectedProviderLease: FederationProviderLease
    ): Challenge {
        val challenge = when (val found = store.findChallenge(id)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> mapStoreFailure(found.error.code)
        } ?: samlAbort(SamlErrorCode.REQUEST_INVALID)
        if (challenge.purpose != ChallengePurpose.EXTERNAL_IDENTITY_LINK ||
            challenge.organizationId != config.tenantId || challenge.state != ChallengeState.PENDING ||
            challenge.federationProviderLease != expectedProviderLease
        ) {
            samlAbort(SamlErrorCode.REQUEST_INVALID)
        }
        if (challenge.expiresAt <= now) samlAbort(SamlErrorCode.REQUEST_EXPIRED)
        return challenge
    }

    private suspend fun validateChallengeBinding(challenge: Challenge, request: SamlPostResponseRequest) {
        if (request.state.requestId != "_${challenge.id.value}" || request.state.linkToUserId != challenge.userId) {
            samlAbort(SamlErrorCode.REQUEST_INVALID)
        }
        val requestDigest = runtime.crypto.sha256(request.state.requestId.encodeToByteArray())
        val requestMatches = compareDigest(challenge.challengeDigest, requestDigest)
        requestDigest.fill(0)
        if (!requestMatches) samlAbort(SamlErrorCode.REQUEST_INVALID)

        val providerKey = request.state.providerLease.storageKey
        val relay = request.state.relayStateBytes()
        val bindingInput = lengthPrefixed(providerKey.encodeToByteArray(), relay)
        relay.fill(0)
        val bindingDigest = try {
            runtime.crypto.sha256(bindingInput)
        } finally {
            bindingInput.fill(0)
        }
        val bindingMatches = compareDigest(challenge.bindingDigest, bindingDigest)
        bindingDigest.fill(0)
        if (!bindingMatches) samlAbort(SamlErrorCode.REQUEST_INVALID)
    }

    private suspend fun sha256Digest(value: ByteArray): SecretDigest {
        val digest = try {
            runtime.crypto.sha256(value)
        } finally {
            value.fill(0)
        }
        return try {
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

    private suspend fun resolveMetadata(): SamlProviderMetadata {
        val metadata = try {
            metadataResolver.resolve()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            samlAbort(SamlErrorCode.PROVIDER_METADATA_INVALID)
        }
        val now = runtime.clock.now()
        if (metadata.entityId != config.idpEntityId || metadata.validUntil <= now ||
            metadata.verificationKeys.none { key ->
                (key.validFrom == null || now >= key.validFrom!!) && (key.validUntil == null || now < key.validUntil!!)
            }
        ) {
            samlAbort(SamlErrorCode.PROVIDER_METADATA_INVALID)
        }
        return metadata
    }

    private fun buildAuthnRequest(requestId: String, destination: String, issuedAt: kotlin.time.Instant): String =
        buildString(1_024) {
            append("<samlp:AuthnRequest xmlns:samlp=\"").append(SAML_PROTOCOL_NAMESPACE)
                .append("\" xmlns:saml=\"").append(SAML_ASSERTION_NAMESPACE)
                .append("\" ID=\"").append(escapeXmlAttribute(requestId))
                .append("\" Version=\"2.0\" IssueInstant=\"").append(issuedAt)
                .append("\" Destination=\"").append(escapeXmlAttribute(destination))
                .append("\" AssertionConsumerServiceURL=\"")
                .append(escapeXmlAttribute(config.assertionConsumerServiceUrl))
                .append("\" ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\">")
            append("<saml:Issuer>").append(escapeXmlText(config.spEntityId)).append("</saml:Issuer>")
            append("<samlp:NameIDPolicy AllowCreate=\"true\"/>")
            append("</samlp:AuthnRequest>")
        }

    private fun requireConfiguredEnabled() {
        if (!config.enabled) samlAbort(SamlErrorCode.PROVIDER_DISABLED)
    }

    private suspend fun acquireProviderLease(
        storageKey: String,
        acquiredAt: kotlin.time.Instant
    ): FederationProviderLease {
        val command = AcquireFederationProviderLeaseCommand(
            organizationId = config.tenantId,
            kind = FederationProviderKind.SAML,
            providerId = config.providerId,
            storageKey = storageKey,
            acquiredAt = acquiredAt
        )
        return when (val acquired = store.acquireFederationProviderLease(command)) {
            is StoreResult.Success -> acquired.value.takeIf {
                it.organizationId == command.organizationId &&
                    it.kind == command.kind &&
                    it.providerId == command.providerId &&
                    it.storageKey == command.storageKey
            } ?: samlAbort(SamlErrorCode.PROVIDER_DISABLED)
            is StoreResult.Failure -> mapProviderControlFailure(acquired.error.code)
        }
    }

    private suspend fun requireCurrentProviderLease(
        lease: FederationProviderLease
    ): FederationProviderLease {
        val expectedStorageKey = samlProviderStorageKey(config, runtime.crypto)
        if (lease.organizationId != config.tenantId ||
            lease.kind != FederationProviderKind.SAML ||
            lease.providerId != config.providerId ||
            lease.storageKey != expectedStorageKey
        ) {
            samlAbort(SamlErrorCode.PROVIDER_DISABLED)
        }
        return when (val validated = store.validateFederationProviderLease(lease)) {
            is StoreResult.Success -> validated.value.takeIf { it == lease }
                ?: samlAbort(SamlErrorCode.PROVIDER_DISABLED)
            is StoreResult.Failure -> mapProviderControlFailure(validated.error.code)
        }
    }

    private fun mapProviderControlFailure(code: IdentityStoreErrorCode): Nothing = when (code) {
        IdentityStoreErrorCode.UNAVAILABLE,
        IdentityStoreErrorCode.INTERNAL,
        IdentityStoreErrorCode.VERSION_CONFLICT -> samlAbort(SamlErrorCode.STORE_UNAVAILABLE)
        else -> samlAbort(SamlErrorCode.PROVIDER_DISABLED)
    }

    private fun mapReplayFailure(code: IdentityStoreErrorCode): Nothing = when (code) {
        IdentityStoreErrorCode.REPLAY_DETECTED,
        IdentityStoreErrorCode.ALREADY_EXISTS,
        IdentityStoreErrorCode.UNIQUE_CONSTRAINT -> samlAbort(SamlErrorCode.ASSERTION_REPLAYED)
        else -> mapStoreFailure(code)
    }

    private fun mapStoreFailure(code: IdentityStoreErrorCode): Nothing = when (code) {
        IdentityStoreErrorCode.UNAVAILABLE,
        IdentityStoreErrorCode.INTERNAL,
        IdentityStoreErrorCode.VERSION_CONFLICT -> samlAbort(SamlErrorCode.STORE_UNAVAILABLE)
        IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED -> samlAbort(SamlErrorCode.PROVIDER_DISABLED)
        IdentityStoreErrorCode.REPLAY_DETECTED -> samlAbort(SamlErrorCode.ASSERTION_REPLAYED)
        else -> samlAbort(SamlErrorCode.EXTERNAL_IDENTITY_CONFLICT)
    }
}

private suspend fun <T> runSaml(block: suspend () -> T): SamlResult<T> = try {
    SamlResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (abort: SamlAbort) {
    SamlResult.Failure(SamlError(abort.code))
} catch (_: IllegalArgumentException) {
    SamlResult.Failure(SamlError(SamlErrorCode.RESPONSE_INVALID))
} catch (_: Throwable) {
    SamlResult.Failure(SamlError(SamlErrorCode.STORE_UNAVAILABLE))
}
