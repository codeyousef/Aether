package codes.yousef.aether.auth.saml

import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.IdentityFederationProviderManager
import codes.yousef.aether.auth.IdentityOperationResult
import codes.yousef.aether.auth.IdentityStore
import codes.yousef.aether.auth.IdentityStoreError
import codes.yousef.aether.auth.IdentityStoreErrorCode
import codes.yousef.aether.auth.LinkExternalIdentityCommand
import codes.yousef.aether.auth.ExternalIdentityLinkCommit
import codes.yousef.aether.auth.MembershipState
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.P256PublicKey
import codes.yousef.aether.auth.RsaPublicKey
import codes.yousef.aether.auth.StoreResult
import codes.yousef.aether.auth.UserId
import codes.yousef.aether.auth.testkit.DeterministicIdentityRuntime
import codes.yousef.aether.auth.testkit.IdentityFixtures
import codes.yousef.aether.auth.testkit.InMemoryIdentityStore
import codes.yousef.aether.auth.testkit.InMemoryIdentityStoreSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class SamlIdentityProviderTest {
    @Test
    fun signedAssertionAuthenticatesOnceAndReplayIsRejected() = runTest {
        val scenario = scenario()
        val response = scenario.signedResponse()

        assertIs<SamlResult.Success<SamlAuthenticationResult>>(scenario.complete(response))
        val replay = assertIs<SamlResult.Failure>(scenario.complete(response))
        assertEquals(SamlErrorCode.REQUEST_INVALID, replay.error.code)
        assertEquals(1, scenario.store.snapshot().replayReceipts.size)
    }

    @Test
    fun concurrentPostCompletionHasExactlyOneWinner() = runTest {
        val scenario = scenario()
        val response = scenario.signedResponse()

        val outcomes = listOf(
            async { scenario.complete(response) },
            async { scenario.complete(response) }
        ).awaitAll()

        assertEquals(1, outcomes.count { it is SamlResult.Success })
        assertEquals(1, outcomes.count { it is SamlResult.Failure })
        assertEquals(1, scenario.store.snapshot().replayReceipts.size)
    }

    @Test
    fun signatureWrappingWithASecondAssertionIsRejected() = runTest {
        val scenario = scenario()
        val response = scenario.signedResponse(extraAssertion = true)

        val failure = assertIs<SamlResult.Failure>(scenario.complete(response))
        assertEquals(SamlErrorCode.RESPONSE_INVALID, failure.error.code)
        assertTrue(scenario.store.snapshot().replayReceipts.isEmpty())
    }

    @Test
    fun recipientAndTimeWindowsAreExact() = runTest {
        val wrongRecipient = scenario()
        val recipientFailure = assertIs<SamlResult.Failure>(
            wrongRecipient.complete(
                wrongRecipient.signedResponse(recipient = "https://sp.example.test/identity/v1/federation/wrong")
            )
        )
        assertEquals(SamlErrorCode.RESPONSE_INVALID, recipientFailure.error.code)

        val expired = scenario()
        val timeFailure = assertIs<SamlResult.Failure>(
            expired.complete(expired.signedResponse(expiresAt = expired.now - 1.minutes))
        )
        assertEquals(SamlErrorCode.RESPONSE_INVALID, timeFailure.error.code)
    }

    @Test
    fun xxeAndDuplicateIdsAreRejectedBeforeSignatureWork() = runTest {
        val xxe = scenario()
        val valid = xxe.signedResponse()
        val malicious = "<!DOCTYPE samlp:Response [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>$valid"
        val xxeFailure = assertIs<SamlResult.Failure>(xxe.complete(malicious))
        assertEquals(SamlErrorCode.RESPONSE_INVALID, xxeFailure.error.code)

        val duplicate = scenario()
        val duplicateXml = duplicate.signedResponse(extraAssertion = true)
            .replace("ID=\"_assertion-extra\"", "ID=\"_assertion\"")
        val duplicateFailure = assertIs<SamlResult.Failure>(duplicate.complete(duplicateXml))
        assertEquals(SamlErrorCode.RESPONSE_INVALID, duplicateFailure.error.code)
    }

    @Test
    fun sha1AndUnknownTransformsAreRejected() = runTest {
        val sha1 = scenario()
        val sha1Failure = assertIs<SamlResult.Failure>(
            sha1.complete(
                sha1.signedResponse(
                    signatureAlgorithm = "http://www.w3.org/2000/09/xmldsig#rsa-sha1"
                )
            )
        )
        assertEquals(SamlErrorCode.SIGNATURE_INVALID, sha1Failure.error.code)

        val transform = scenario()
        val transformFailure = assertIs<SamlResult.Failure>(
            transform.complete(
                transform.signedResponse(
                    canonicalizationTransform = "http://www.w3.org/TR/1999/REC-xpath-19991116"
                )
            )
        )
        assertEquals(SamlErrorCode.SIGNATURE_INVALID, transformFailure.error.code)
    }

    @Test
    fun metadataKeyRotationAcceptsOnlyTheCurrentOverlappingSnapshot() = runTest {
        val scenario = scenario(metadataKeyId = "key-old")
        val response = scenario.signedResponse(keyId = "key-new")

        val beforeRotation = assertIs<SamlResult.Failure>(scenario.complete(response))
        assertEquals(SamlErrorCode.SIGNATURE_INVALID, beforeRotation.error.code)

        scenario.metadata.current = scenario.metadata("key-new")
        assertIs<SamlResult.Success<SamlAuthenticationResult>>(scenario.complete(response))
    }

    @Test
    fun bothPolicyRequiresAndVerifiesBothSignatures() = runTest {
        val scenario = scenario(signaturePolicy = SamlSignaturePolicy.BOTH)
        val failure = assertIs<SamlResult.Failure>(scenario.complete(scenario.signedResponse()))
        assertEquals(SamlErrorCode.SIGNATURE_INVALID, failure.error.code)
    }

    @Test
    fun ecdsaSha256UsesTheCommonIdentityCryptoBoundary() = runTest {
        val scenario = scenario()
        scenario.metadata.current = scenario.metadata("key-ec", es256 = true)
        val response = scenario.signedResponse(
            keyId = "key-ec",
            signatureAlgorithm = SamlSignatureAlgorithm.ECDSA_SHA256.uri
        )

        assertIs<SamlResult.Success<SamlAuthenticationResult>>(scenario.complete(response))
    }

    @Test
    fun disabledProviderLeaseBlocksCallbackBeforeAssertionWork() = runTest {
        val scenario = scenario()
        val response = scenario.signedResponse()
        scenario.disableProvider()

        val failure = assertIs<SamlResult.Failure>(scenario.complete(response))

        assertEquals(SamlErrorCode.PROVIDER_DISABLED, failure.error.code)
        assertEquals(codes.yousef.aether.auth.ChallengeState.PENDING, scenario.store.snapshot().challenges.single().state)
    }

    @Test
    fun disabledProviderIsRejectedBeforeMetadataResolutionOrChallengeCreation() = runTest {
        val deterministic = DeterministicIdentityRuntime()
        val tenantId = OrganizationId("saml-tenant")
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(organizations = listOf(IdentityFixtures.organization(tenantId)))
        )
        val config = SamlProviderConfig(
            tenantId = tenantId,
            providerId = "workforce",
            spEntityId = SP_ENTITY_ID,
            idpEntityId = IDP_ENTITY_ID,
            assertionConsumerServiceUrl = ACS_URL
        )
        val storageKey = samlProviderStorageKey(config, deterministic.runtime.crypto)
        assertIs<IdentityOperationResult.Success<*>>(
            IdentityFederationProviderManager(store, deterministic.runtime).disableProvider(
                tenantId,
                FederationProviderKind.SAML,
                config.providerId,
                storageKey,
                reasonCode = "saml_test_prestart_disabled"
            )
        )
        val resolver = MutableMetadataResolver(metadata("key-old", deterministic.deterministicClock.now()))
        val provider = SamlIdentityProvider(config, deterministic.runtime, store, resolver)

        val failure = assertIs<SamlResult.Failure>(provider.beginAuthentication())

        assertEquals(SamlErrorCode.PROVIDER_DISABLED, failure.error.code)
        assertEquals(0, resolver.resolutionCount)
        assertEquals(emptyList(), store.snapshot().challenges)
    }

    @Test
    fun disableThenReenableRejectsCallbackFromTheEarlierProviderVersion() = runTest {
        val scenario = scenario()
        val response = scenario.signedResponse()
        scenario.disableProvider()
        scenario.enableProvider()

        val failure = assertIs<SamlResult.Failure>(scenario.complete(response))

        assertEquals(SamlErrorCode.PROVIDER_DISABLED, failure.error.code)
        assertEquals(codes.yousef.aether.auth.ChallengeState.PENDING, scenario.store.snapshot().challenges.single().state)
    }

    @Test
    fun enabledJitAtomicallyCreatesFreshNullEmailViewerAndNeverMergesByEmail() = runTest {
        val scenario = jitScenario()

        val authenticated = assertIs<SamlResult.Success<SamlAuthenticationResult>>(
            scenario.complete(scenario.signedResponse())
        ).value

        assertTrue(authenticated.userId != EXISTING_EMAIL_USER_ID)
        val snapshot = scenario.store.snapshot()
        val user = requireNotNull(snapshot.users.singleOrNull { it.id == authenticated.userId })
        assertEquals(null, user.primaryEmail)
        assertEquals(MembershipState.ACTIVE, snapshot.memberships.single { it.userId == user.id }.state)
        assertEquals(OrganizationRole.VIEWER, snapshot.memberships.single { it.userId == user.id }.role)
        assertEquals(user.id, snapshot.externalIdentities.single().userId)
        assertEquals(EmailAddress("saml-user@example.test"), snapshot.externalIdentities.single().email)
    }

    @Test
    fun jitReplayLinkAndProviderFailuresLeaveNoProvisionedOrphans() = runTest {
        LinkFailureMode.entries.forEach { mode ->
            val (scenario, interceptor) = jitFailureScenario(mode)

            val failure = assertIs<SamlResult.Failure>(
                scenario.complete(scenario.signedResponse()),
                mode.name
            )

            assertEquals(mode.expectedSamlError, failure.error.code, mode.name)
            val provisioning = requireNotNull(interceptor.command?.jitProvisioning)
            val snapshot = scenario.store.snapshot()
            assertTrue(snapshot.users.none { it.id == provisioning.user.id }, mode.name)
            assertTrue(snapshot.memberships.none { it.id == provisioning.membership.id }, mode.name)
            assertTrue(snapshot.externalIdentities.none { it.userId == provisioning.user.id }, mode.name)
        }
    }

    private suspend fun scenario(
        metadataKeyId: String = "key-old",
        signaturePolicy: SamlSignaturePolicy = SamlSignaturePolicy.ASSERTION_OR_RESPONSE
    ): Scenario {
        val deterministic = DeterministicIdentityRuntime()
        val userId = UserId("saml-user")
        val tenantId = OrganizationId("saml-tenant")
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(IdentityFixtures.user(userId)),
                organizations = listOf(IdentityFixtures.organization(tenantId))
            )
        )
        val config = SamlProviderConfig(
            tenantId = tenantId,
            providerId = "workforce",
            spEntityId = SP_ENTITY_ID,
            idpEntityId = IDP_ENTITY_ID,
            assertionConsumerServiceUrl = ACS_URL,
            signaturePolicy = signaturePolicy
        )
        val initialMetadata = metadata(metadataKeyId, deterministic.deterministicClock.now())
        val resolver = MutableMetadataResolver(initialMetadata)
        val provider = SamlIdentityProvider(
            config = config,
            runtime = deterministic.runtime,
            store = store,
            metadataResolver = resolver
        )
        val start = assertIs<SamlResult.Success<SamlAuthenticationStart>>(
            provider.beginAuthentication(SamlAuthenticationRequest(linkToUserId = userId))
        ).value
        return Scenario(deterministic, store, config, resolver, provider, start)
    }

    private suspend fun jitScenario(): Scenario {
        val deterministic = DeterministicIdentityRuntime()
        val existingEmailUser = IdentityFixtures.user(EXISTING_EMAIL_USER_ID).copy(
            primaryEmail = EmailAddress("saml-user@example.test")
        )
        val tenantId = OrganizationId("saml-tenant")
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(existingEmailUser),
                organizations = listOf(IdentityFixtures.organization(tenantId))
            )
        )
        val config = SamlProviderConfig(
            tenantId = tenantId,
            providerId = "workforce",
            spEntityId = SP_ENTITY_ID,
            idpEntityId = IDP_ENTITY_ID,
            assertionConsumerServiceUrl = ACS_URL,
            jitProvisioningEnabled = true
        )
        val resolver = MutableMetadataResolver(metadata("key-old", deterministic.deterministicClock.now()))
        val provider = SamlIdentityProvider(
            config = config,
            runtime = deterministic.runtime,
            store = store,
            metadataResolver = resolver
        )
        val start = assertIs<SamlResult.Success<SamlAuthenticationStart>>(
            provider.beginAuthentication()
        ).value
        return Scenario(deterministic, store, config, resolver, provider, start)
    }

    private suspend fun jitFailureScenario(
        mode: LinkFailureMode
    ): Pair<Scenario, InterceptingLinkStore> {
        val deterministic = DeterministicIdentityRuntime()
        val tenantId = OrganizationId("saml-tenant")
        val existingEmailUser = IdentityFixtures.user(EXISTING_EMAIL_USER_ID).copy(
            primaryEmail = EmailAddress("saml-user@example.test")
        )
        val delegate = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(existingEmailUser),
                organizations = listOf(IdentityFixtures.organization(tenantId))
            )
        )
        val config = SamlProviderConfig(
            tenantId = tenantId,
            providerId = "workforce",
            spEntityId = SP_ENTITY_ID,
            idpEntityId = IDP_ENTITY_ID,
            assertionConsumerServiceUrl = ACS_URL,
            jitProvisioningEnabled = true
        )
        val interceptor = InterceptingLinkStore(delegate, deterministic, config, mode)
        val resolver = MutableMetadataResolver(metadata("key-old", deterministic.deterministicClock.now()))
        val provider = SamlIdentityProvider(config, deterministic.runtime, interceptor, resolver)
        val start = assertIs<SamlResult.Success<SamlAuthenticationStart>>(
            provider.beginAuthentication()
        ).value
        return Scenario(deterministic, delegate, config, resolver, provider, start) to interceptor
    }

    private fun metadata(keyId: String, now: Instant = IdentityFixtures.baseInstant): SamlProviderMetadata =
        SamlProviderMetadata(
            entityId = IDP_ENTITY_ID,
            redirectSsoUrl = SSO_URL,
            verificationKeys = listOf(
                SamlVerificationKey.Rsa(
                    keyId = keyId,
                    publicKey = RsaPublicKey(ByteArray(256) { 1 }),
                    validFrom = now - 1.hours,
                    validUntil = now + 2.hours
                )
            ),
            version = "metadata-$keyId",
            validUntil = now + 2.hours
        )

    private class MutableMetadataResolver(var current: SamlProviderMetadata) : SamlMetadataResolver {
        var resolutionCount: Int = 0
            private set

        override suspend fun resolve(): SamlProviderMetadata {
            resolutionCount += 1
            return current
        }
    }

    private class InterceptingLinkStore(
        private val delegate: InMemoryIdentityStore,
        private val deterministic: DeterministicIdentityRuntime,
        private val config: SamlProviderConfig,
        private val mode: LinkFailureMode
    ) : IdentityStore by delegate {
        var command: LinkExternalIdentityCommand? = null
            private set

        override suspend fun linkExternalIdentity(
            command: LinkExternalIdentityCommand
        ): StoreResult<ExternalIdentityLinkCommit> {
            this.command = command
            return when (mode) {
                LinkFailureMode.REPLAY -> StoreResult.Failure(
                    IdentityStoreError(IdentityStoreErrorCode.REPLAY_DETECTED)
                )
                LinkFailureMode.LINK_CONFLICT -> StoreResult.Failure(
                    IdentityStoreError(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
                )
                LinkFailureMode.PROVIDER_DISABLED -> {
                    val disabled = IdentityFederationProviderManager(delegate, deterministic.runtime).disableProvider(
                        organizationId = config.tenantId,
                        kind = FederationProviderKind.SAML,
                        providerId = config.providerId,
                        storageKey = command.federationProviderLease.storageKey,
                        reasonCode = "saml_test_prelink_disabled"
                    )
                    check(disabled is IdentityOperationResult.Success)
                    delegate.linkExternalIdentity(command)
                }
            }
        }
    }

    private enum class LinkFailureMode(val expectedSamlError: SamlErrorCode) {
        REPLAY(SamlErrorCode.ASSERTION_REPLAYED),
        LINK_CONFLICT(SamlErrorCode.EXTERNAL_IDENTITY_CONFLICT),
        PROVIDER_DISABLED(SamlErrorCode.PROVIDER_DISABLED)
    }

    private class Scenario(
        private val deterministic: DeterministicIdentityRuntime,
        val store: InMemoryIdentityStore,
        private val config: SamlProviderConfig,
        val metadata: MutableMetadataResolver,
        private val provider: SamlIdentityProvider,
        private val start: SamlAuthenticationStart
    ) {
        val now: Instant get() = deterministic.deterministicClock.now()

        suspend fun disableProvider() {
            val storageKey = samlProviderStorageKey(config, deterministic.runtime.crypto)
            assertIs<IdentityOperationResult.Success<*>>(
                IdentityFederationProviderManager(store, deterministic.runtime).disableProvider(
                    organizationId = config.tenantId,
                    kind = FederationProviderKind.SAML,
                    providerId = config.providerId,
                    storageKey = storageKey,
                    reasonCode = "saml_test_disabled"
                )
            )
        }

        suspend fun enableProvider() {
            val storageKey = samlProviderStorageKey(config, deterministic.runtime.crypto)
            assertIs<IdentityOperationResult.Success<*>>(
                IdentityFederationProviderManager(store, deterministic.runtime).enableProvider(
                    organizationId = config.tenantId,
                    kind = FederationProviderKind.SAML,
                    providerId = config.providerId,
                    storageKey = storageKey,
                    reasonCode = "saml_test_enabled"
                )
            )
        }

        fun metadata(keyId: String, es256: Boolean = false): SamlProviderMetadata =
            SamlProviderMetadata(
                entityId = IDP_ENTITY_ID,
                redirectSsoUrl = SSO_URL,
                verificationKeys = listOf(
                    if (es256) {
                        SamlVerificationKey.Es256(
                            keyId,
                            P256PublicKey(ByteArray(65).also { it[0] = 0x04 }),
                            validFrom = now - 1.hours,
                            validUntil = now + 2.hours
                        )
                    } else {
                        SamlVerificationKey.Rsa(
                            keyId,
                            RsaPublicKey(ByteArray(256) { 2 }),
                            validFrom = now - 1.hours,
                            validUntil = now + 2.hours
                        )
                    }
                ),
                version = "metadata-$keyId",
                validUntil = now + 2.hours
            )

        suspend fun signedResponse(
            recipient: String = ACS_URL,
            expiresAt: Instant = now + 5.minutes,
            keyId: String = "key-old",
            extraAssertion: Boolean = false,
            signatureAlgorithm: String = SamlSignatureAlgorithm.RSA_SHA256.uri,
            canonicalizationTransform: String = EXCLUSIVE_C14N
        ): String {
            val placeholderDigest = SamlBase64.encode(ByteArray(32))
            val provisional = responseXml(
                digest = placeholderDigest,
                recipient = recipient,
                expiresAt = expiresAt,
                keyId = keyId,
                extraAssertion = extraAssertion,
                signatureAlgorithm = signatureAlgorithm,
                canonicalizationTransform = canonicalizationTransform
            )
            if (extraAssertion) return provisional
            val document = BoundedSamlXml.parse(
                provisional.encodeToByteArray(),
                SamlXmlLimits(1_048_576, 32, 2_048, 64, 262_144)
            )
            val assertion = document.root.singleDirectElement(SAML_ASSERTION_NAMESPACE, "Assertion")
            val signature = assertion.singleDirectElement(XMLDSIG_NAMESPACE, "Signature")
            val canonical = canonicalizeExclusive(assertion, signature)
            val digest = deterministic.deterministicCrypto.sha256(canonical)
            canonical.fill(0)
            val finalXml = responseXml(
                digest = SamlBase64.encode(digest),
                recipient = recipient,
                expiresAt = expiresAt,
                keyId = keyId,
                extraAssertion = false,
                signatureAlgorithm = signatureAlgorithm,
                canonicalizationTransform = canonicalizationTransform
            )
            digest.fill(0)
            return finalXml
        }

        suspend fun complete(xml: String): SamlResult<SamlAuthenticationResult> {
            val relay = start.state.relayStateBytes()
            val relayState = try {
                Base64Url.encode(relay)
            } finally {
                relay.fill(0)
            }
            return provider.completeAuthentication(
                SamlPostResponseRequest(
                    samlResponse = SamlBase64.encode(xml.encodeToByteArray()),
                    relayState = relayState,
                    state = start.state
                )
            )
        }

        private fun responseXml(
            digest: String,
            recipient: String,
            expiresAt: Instant,
            keyId: String,
            extraAssertion: Boolean,
            signatureAlgorithm: String,
            canonicalizationTransform: String
        ): String {
            val issueInstant = now.toString()
            val notBefore = (now - 1.minutes).toString()
            val signatureValue = SamlBase64.encode(
                ByteArray(
                    if (signatureAlgorithm == SamlSignatureAlgorithm.ECDSA_SHA256.uri) 64 else 256
                ) { 7 }
            )
            val assertion = """
                <saml:Assertion ID="_assertion" Version="2.0" IssueInstant="$issueInstant">
                  <saml:Issuer>$IDP_ENTITY_ID</saml:Issuer>
                  <ds:Signature xmlns:ds="$XMLDSIG_NAMESPACE">
                    <ds:SignedInfo>
                      <ds:CanonicalizationMethod Algorithm="$EXCLUSIVE_C14N"></ds:CanonicalizationMethod>
                      <ds:SignatureMethod Algorithm="$signatureAlgorithm"></ds:SignatureMethod>
                      <ds:Reference URI="#_assertion">
                        <ds:Transforms>
                          <ds:Transform Algorithm="$ENVELOPED_SIGNATURE"></ds:Transform>
                          <ds:Transform Algorithm="$canonicalizationTransform"></ds:Transform>
                        </ds:Transforms>
                        <ds:DigestMethod Algorithm="$SHA256_DIGEST"></ds:DigestMethod>
                        <ds:DigestValue>$digest</ds:DigestValue>
                      </ds:Reference>
                    </ds:SignedInfo>
                    <ds:SignatureValue>$signatureValue</ds:SignatureValue>
                    <ds:KeyInfo><ds:KeyName>$keyId</ds:KeyName></ds:KeyInfo>
                  </ds:Signature>
                  <saml:Subject>
                    <saml:NameID Format="urn:oasis:names:tc:SAML:2.0:nameid-format:persistent">subject-123</saml:NameID>
                    <saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">
                      <saml:SubjectConfirmationData Recipient="$recipient" InResponseTo="${start.state.requestId}" NotOnOrAfter="$expiresAt"></saml:SubjectConfirmationData>
                    </saml:SubjectConfirmation>
                  </saml:Subject>
                  <saml:Conditions NotBefore="$notBefore" NotOnOrAfter="$expiresAt">
                    <saml:AudienceRestriction><saml:Audience>$SP_ENTITY_ID</saml:Audience></saml:AudienceRestriction>
                  </saml:Conditions>
                  <saml:AuthnStatement AuthnInstant="$issueInstant" SessionIndex="session-1" SessionNotOnOrAfter="$expiresAt">
                    <saml:AuthnContext><saml:AuthnContextClassRef>urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport</saml:AuthnContextClassRef></saml:AuthnContext>
                  </saml:AuthnStatement>
                  <saml:AttributeStatement><saml:Attribute Name="email"><saml:AttributeValue>saml-user@example.test</saml:AttributeValue></saml:Attribute></saml:AttributeStatement>
                </saml:Assertion>
            """.trimIndent()
            val secondAssertion = if (extraAssertion) {
                "<saml:Assertion ID=\"_assertion-extra\" Version=\"2.0\" IssueInstant=\"$issueInstant\"><saml:Issuer>$IDP_ENTITY_ID</saml:Issuer></saml:Assertion>"
            } else {
                ""
            }
            return """
                <samlp:Response xmlns:samlp="$SAML_PROTOCOL_NAMESPACE" xmlns:saml="$SAML_ASSERTION_NAMESPACE" ID="_response" Version="2.0" IssueInstant="$issueInstant" Destination="$ACS_URL" InResponseTo="${start.state.requestId}">
                  <saml:Issuer>$IDP_ENTITY_ID</saml:Issuer>
                  <samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"></samlp:StatusCode></samlp:Status>
                  $assertion
                  $secondAssertion
                </samlp:Response>
            """.trimIndent()
        }
    }

    private companion object {
        const val SP_ENTITY_ID = "https://sp.example.test/saml/metadata"
        const val IDP_ENTITY_ID = "https://idp.example.test/entity"
        const val ACS_URL = "https://sp.example.test/identity/v1/federation/saml/workforce"
        const val SSO_URL = "https://idp.example.test/sso"
        val EXISTING_EMAIL_USER_ID = UserId("saml-existing-email-user")
    }
}
