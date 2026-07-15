package codes.yousef.aether.auth.oidc

import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.ExternalSubject
import codes.yousef.aether.auth.ExternalIdentity
import codes.yousef.aether.auth.ExternalIdentityLinkCommit
import codes.yousef.aether.auth.ExternalIdentityReplayReceipt
import codes.yousef.aether.auth.ExternalReplayReceiptId
import codes.yousef.aether.auth.FederationJitProvisioning
import codes.yousef.aether.auth.IdentityFederationProviderManager
import codes.yousef.aether.auth.IdentityHttpMethod
import codes.yousef.aether.auth.IdentityHttpResponse
import codes.yousef.aether.auth.IdentityStore
import codes.yousef.aether.auth.IdentityStoreError
import codes.yousef.aether.auth.IdentityStoreErrorCode
import codes.yousef.aether.auth.LinkExternalIdentityCommand
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.RecordExternalIdentityReplayCommand
import codes.yousef.aether.auth.SecretDigest
import codes.yousef.aether.auth.SessionAuthenticationMethod
import codes.yousef.aether.auth.DigestAlgorithm
import codes.yousef.aether.auth.StoreResult
import codes.yousef.aether.auth.UserId
import codes.yousef.aether.auth.testkit.DeterministicIdentityCrypto
import codes.yousef.aether.auth.testkit.DeterministicIdentityRuntime
import codes.yousef.aether.auth.testkit.IdentityFixtures
import codes.yousef.aether.auth.testkit.InMemoryIdentityStore
import codes.yousef.aether.auth.testkit.InMemoryIdentityStoreSeed
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OidcIdentityProviderTest {
    @Test
    fun authorizationCodePkceRefreshesJwksAndAuthenticatesLinkedSubject() = runTest {
        val fixture = fixture(linked = true)
        val start = fixture.begin()
        val state = queryParameter(start.authorizationUrl, "state")
        val nonce = queryParameter(start.authorizationUrl, "nonce")
        assertContains(start.authorizationUrl, "code_challenge_method=S256")
        assertContains(start.authorizationUrl, "response_type=code")

        fixture.http.enqueue(jsonResponse(tokenResponse(idToken(nonce = nonce, keyId = "rotated-key"))))
        fixture.http.enqueue(jsonResponse(jwks("old-key")))
        fixture.http.enqueue(jsonResponse(jwks("rotated-key")))

        val result = fixture.provider.completeAuthorization(
            callback(start, state, fixture.binding)
        )
        val authenticated = assertIs<OidcResult.Success<OidcAuthenticationResult>>(result).value
        assertEquals(USER_ID, authenticated.userId)
        assertEquals(SessionAuthenticationMethod.OIDC, authenticated.authenticationMethod)
        assertTrue(authenticated.passkeyStepUpRequiredForSensitiveActions)

        val requests = fixture.http.recordedRequests()
        assertEquals(4, requests.size)
        assertEquals(IdentityHttpMethod.POST, requests[1].method)
        assertEquals(65_536, requests[0].maximumResponseBytes)
        assertEquals(65_536, requests[1].maximumResponseBytes)
        assertEquals(262_144, requests[2].maximumResponseBytes)
        assertEquals(262_144, requests[3].maximumResponseBytes)
        val tokenBody = requests[1].bodyBytes().decodeToString()
        assertContains(tokenBody, "grant_type=authorization_code")
        assertContains(tokenBody, "code_verifier=")
        assertEquals(2, requests.count { it.url == JWKS_URI })

        val snapshot = fixture.store.snapshot()
        assertEquals(1, snapshot.replayReceipts.size)
        val consumedChallenge = snapshot.challenges.single()
        assertEquals(codes.yousef.aether.auth.ChallengeState.CONSUMED, consumedChallenge.state)
        assertEquals(start.providerLease, consumedChallenge.federationProviderLease)
    }

    @Test
    fun discoveryAndJwksCachesAreReusedWithinTheirConfiguredLifetime() = runTest {
        val fixture = fixture(linked = true)
        val first = fixture.begin()
        fixture.http.enqueue(
            jsonResponse(tokenResponse(idToken(queryParameter(first.authorizationUrl, "nonce"))))
        )
        fixture.http.enqueue(jsonResponse(jwks("active-key")))
        assertIs<OidcResult.Success<OidcAuthenticationResult>>(
            fixture.provider.completeAuthorization(
                callback(first, queryParameter(first.authorizationUrl, "state"), fixture.binding)
            )
        )

        val second = fixture.begin()
        fixture.http.enqueue(
            jsonResponse(tokenResponse(idToken(queryParameter(second.authorizationUrl, "nonce"))))
        )
        assertIs<OidcResult.Success<OidcAuthenticationResult>>(
            fixture.provider.completeAuthorization(
                callback(second, queryParameter(second.authorizationUrl, "state"), fixture.binding)
            )
        )

        val requests = fixture.http.recordedRequests()
        assertEquals(1, requests.count { it.url.endsWith("/.well-known/openid-configuration") })
        assertEquals(1, requests.count { it.url == JWKS_URI })
        assertEquals(2, fixture.store.snapshot().replayReceipts.size)
    }

    @Test
    fun validatesIssuerAudienceAuthorizedPartyNonceAndTimeExactly() = runTest {
        val cases = listOf<(String, Long) -> String>(
            { nonce, now -> claimsJson(nonce, now, issuer = "https://wrong.example.test") },
            { nonce, now -> claimsJson(nonce, now, audienceJson = "\"different-client\"") },
            { nonce, now -> claimsJson(nonce, now, audienceJson = "[\"$CLIENT_ID\",\"other\"]") },
            { _, now -> claimsJson("wrong-nonce", now) },
            { nonce, now -> claimsJson(nonce, now, expiresAt = now - 120) },
            { nonce, now -> claimsJson(nonce, now, issuedAt = now + 120) }
        )

        for (claims in cases) {
            val fixture = fixture(linked = true)
            val start = fixture.begin()
            val state = queryParameter(start.authorizationUrl, "state")
            val nonce = queryParameter(start.authorizationUrl, "nonce")
            val now = fixture.runtime.deterministicClock.now().epochSeconds
            fixture.http.enqueue(jsonResponse(tokenResponse(jwt(claims(nonce, now), "active-key", "ES256"))))
            fixture.http.enqueue(jsonResponse(jwks("active-key")))

            val failure = assertIs<OidcResult.Failure>(
                fixture.provider.completeAuthorization(callback(start, state, fixture.binding))
            )
            assertEquals(OidcErrorCode.ID_TOKEN_INVALID, failure.error.code)
            assertEquals(0, fixture.store.snapshot().replayReceipts.size)
        }
    }

    @Test
    fun requiresExactDiscoveryIssuerAndPkceS256() = runTest {
        val runtime = DeterministicIdentityRuntime()
        val providerConfig = config()
        val store = emptyProviderStore(providerConfig)
        val provider = OidcIdentityProvider(providerConfig, runtime.runtime, store)
        runtime.deterministicHttp.enqueue(
            jsonResponse(discovery(issuer = "https://different.example.test"))
        )
        val wrongIssuer = assertIs<OidcResult.Failure>(provider.beginAuthorization(request(BINDING)))
        assertEquals(OidcErrorCode.PROVIDER_METADATA_INVALID, wrongIssuer.error.code)

        val secondRuntime = DeterministicIdentityRuntime()
        val secondConfig = config()
        val secondProvider = OidcIdentityProvider(
            secondConfig,
            secondRuntime.runtime,
            emptyProviderStore(secondConfig)
        )
        secondRuntime.deterministicHttp.enqueue(
            jsonResponse(discovery(pkceMethods = "[\"plain\"]"))
        )
        val noS256 = assertIs<OidcResult.Failure>(secondProvider.beginAuthorization(request(BINDING)))
        assertEquals(OidcErrorCode.PROVIDER_METADATA_INVALID, noS256.error.code)

        val redirectedRuntime = DeterministicIdentityRuntime()
        val redirectedProvider = OidcIdentityProvider(
            providerConfig,
            redirectedRuntime.runtime,
            emptyProviderStore(providerConfig)
        )
        redirectedRuntime.deterministicHttp.enqueue(
            jsonResponse(discovery(tokenEndpoint = "https://credential-sink.example.test/token"))
        )
        val redirectedTokenEndpoint = assertIs<OidcResult.Failure>(
            redirectedProvider.beginAuthorization(request(BINDING))
        )
        assertEquals(OidcErrorCode.PROVIDER_METADATA_INVALID, redirectedTokenEndpoint.error.code)
        assertEquals(1, redirectedRuntime.deterministicHttp.recordedRequests().size)
    }

    @Test
    fun jitIsOffByDefaultAndEmailNeverLinksAnExistingUser() = runTest {
        val fixture = fixture(linked = false)
        val start = fixture.begin()
        val state = queryParameter(start.authorizationUrl, "state")
        val nonce = queryParameter(start.authorizationUrl, "nonce")
        fixture.http.enqueue(jsonResponse(tokenResponse(idToken(nonce))))
        fixture.http.enqueue(jsonResponse(jwks("active-key")))

        val failure = assertIs<OidcResult.Failure>(
            fixture.provider.completeAuthorization(callback(start, state, fixture.binding))
        )
        assertEquals(OidcErrorCode.EXTERNAL_IDENTITY_NOT_LINKED, failure.error.code)
        assertTrue(fixture.store.snapshot().externalIdentities.isEmpty())
    }

    @Test
    fun enabledJitAtomicallyCreatesANullEmailViewerAndNeverMergesByEmail() = runTest {
        val fixture = fixture(linked = false, jitProvisioningEnabled = true, existingEmailUser = true)
        val authenticated = assertIs<OidcResult.Success<OidcAuthenticationResult>>(
            fixture.complete()
        ).value

        assertTrue(authenticated.userId != USER_ID)
        val snapshot = fixture.store.snapshot()
        assertEquals(2, snapshot.users.size)
        assertEquals("user-1@example.test", snapshot.users.single { it.id == USER_ID }.primaryEmail?.value)
        val provisioned = snapshot.users.single { it.id == authenticated.userId }
        assertEquals(null, provisioned.primaryEmail)
        assertEquals("OIDC User", provisioned.displayName)
        val membership = snapshot.memberships.single { it.userId == authenticated.userId }
        assertEquals(fixture.config.tenantId, membership.organizationId)
        assertEquals(OrganizationRole.VIEWER, membership.role)
        assertEquals(authenticated.userId, snapshot.externalIdentities.single().userId)
    }

    @Test
    fun jitLinkFailureLeavesNoOrphanUserOrMembership() = runTest {
        val runtime = DeterministicIdentityRuntime()
        val config = config(jitProvisioningEnabled = true)
        val delegate = emptyProviderStore(config)
        val store = RejectingJitLinkStore(delegate)
        val provider = OidcIdentityProvider(config, runtime.runtime, store)
        runtime.deterministicHttp.enqueue(jsonResponse(discovery()))
        val fixture = Fixture(runtime, delegate, provider, config, BINDING.copyOf(), linkOnBegin = false)

        val failure = assertIs<OidcResult.Failure>(fixture.complete())
        assertEquals(OidcErrorCode.EXTERNAL_IDENTITY_CONFLICT, failure.error.code)
        assertTrue(store.capturedProvisioning != null)
        val snapshot = delegate.snapshot()
        assertTrue(snapshot.users.isEmpty())
        assertTrue(snapshot.memberships.isEmpty())
        assertTrue(snapshot.externalIdentities.isEmpty())
        assertTrue(snapshot.replayReceipts.isEmpty())
    }

    @Test
    fun explicitLinkUsesTenantProviderIssuerAndSubjectKey() = runTest {
        val fixture = fixture(linked = false, linkOnBegin = true)
        val start = fixture.begin()
        val state = queryParameter(start.authorizationUrl, "state")
        val nonce = queryParameter(start.authorizationUrl, "nonce")
        fixture.http.enqueue(jsonResponse(tokenResponse(idToken(nonce))))
        fixture.http.enqueue(jsonResponse(jwks("active-key")))

        val success = assertIs<OidcResult.Success<OidcAuthenticationResult>>(
            fixture.provider.completeAuthorization(callback(start, state, fixture.binding))
        ).value
        assertEquals(USER_ID, success.userId)
        assertEquals(fixture.providerKey(), success.providerLease.storageKey)
        val stored = fixture.store.snapshot().externalIdentities.single()
        assertEquals(fixture.providerKey(), stored.provider)
        assertEquals(SUBJECT, stored.subject)
    }

    @Test
    fun supportsRs256JwkAndRejectsInvalidSignatures() = runTest {
        val rsaFixture = fixture(linked = true)
        val rsaStart = rsaFixture.begin()
        val rsaState = queryParameter(rsaStart.authorizationUrl, "state")
        val rsaNonce = queryParameter(rsaStart.authorizationUrl, "nonce")
        rsaFixture.http.enqueue(jsonResponse(tokenResponse(idToken(rsaNonce, "rsa-key", "RS256"))))
        rsaFixture.http.enqueue(jsonResponse(rsaJwks("rsa-key")))
        assertIs<OidcResult.Success<OidcAuthenticationResult>>(
            rsaFixture.provider.completeAuthorization(callback(rsaStart, rsaState, rsaFixture.binding))
        )

        val runtime = DeterministicIdentityRuntime(
            deterministicCrypto = DeterministicIdentityCrypto(verifyEs256Result = false)
        )
        val invalidFixture = fixture(linked = true, runtime = runtime)
        val invalidStart = invalidFixture.begin()
        val invalidState = queryParameter(invalidStart.authorizationUrl, "state")
        val invalidNonce = queryParameter(invalidStart.authorizationUrl, "nonce")
        invalidFixture.http.enqueue(jsonResponse(tokenResponse(idToken(invalidNonce))))
        invalidFixture.http.enqueue(jsonResponse(jwks("active-key")))
        invalidFixture.http.enqueue(jsonResponse(jwks("active-key")))
        val failure = assertIs<OidcResult.Failure>(
            invalidFixture.provider.completeAuthorization(
                callback(invalidStart, invalidState, invalidFixture.binding)
            )
        )
        assertEquals(OidcErrorCode.SIGNATURE_INVALID, failure.error.code)
    }

    @Test
    fun rejectsDuplicateSecurityClaimsBeforeVerification() = runTest {
        val fixture = fixture(linked = true)
        val start = fixture.begin()
        val state = queryParameter(start.authorizationUrl, "state")
        val nonce = queryParameter(start.authorizationUrl, "nonce")
        val now = fixture.runtime.deterministicClock.now().epochSeconds
        val duplicateIssuerClaims = claimsJson(nonce, now).dropLast(1) + ",\"iss\":\"$ISSUER\"}"
        fixture.http.enqueue(jsonResponse(tokenResponse(jwt(duplicateIssuerClaims, "active-key", "ES256"))))
        fixture.http.enqueue(jsonResponse(jwks("active-key")))

        val failure = assertIs<OidcResult.Failure>(
            fixture.provider.completeAuthorization(callback(start, state, fixture.binding))
        )
        assertEquals(OidcErrorCode.ID_TOKEN_INVALID, failure.error.code)
    }

    @Test
    fun storeReplayReceiptRejectsAPreviouslyAcceptedAssertionDigest() = runTest {
        val fixture = fixture(linked = true)
        val start = fixture.begin()
        val state = queryParameter(start.authorizationUrl, "state")
        val nonce = queryParameter(start.authorizationUrl, "nonce")
        val token = idToken(nonce)
        val digest = fixture.runtime.runtime.crypto.sha256(token.encodeToByteArray())
        assertIs<StoreResult.Success<ExternalIdentityReplayReceipt>>(fixture.store.recordExternalIdentityReplay(
            RecordExternalIdentityReplayCommand(
                ExternalIdentityReplayReceipt(
                    id = ExternalReplayReceiptId("existing-replay-receipt"),
                    provider = fixture.providerKey(),
                    assertionDigest = SecretDigest(DigestAlgorithm.SHA256, Base64Url.encode(digest)),
                    receivedAt = fixture.runtime.deterministicClock.now(),
                    expiresAt = fixture.runtime.deterministicClock.now() + 10.minutes
                ),
                start.providerLease
            )
        ))
        digest.fill(0)
        fixture.http.enqueue(jsonResponse(tokenResponse(token)))
        fixture.http.enqueue(jsonResponse(jwks("active-key")))

        val failure = assertIs<OidcResult.Failure>(
            fixture.provider.completeAuthorization(callback(start, state, fixture.binding))
        )
        assertEquals(OidcErrorCode.ASSERTION_REPLAYED, failure.error.code)
    }

    @Test
    fun callbackBindingAndProviderLeaseInvalidationAreEnforcedBeforeExchange() = runTest {
        val fixture = fixture(linked = true)
        val start = fixture.begin()
        val state = queryParameter(start.authorizationUrl, "state")
        val wrongBinding = ByteArray(32) { 99 }
        val bindingFailure = assertIs<OidcResult.Failure>(
            fixture.provider.completeAuthorization(callback(start, state, wrongBinding))
        )
        assertEquals(OidcErrorCode.INVALID_STATE, bindingFailure.error.code)
        assertEquals(1, fixture.http.recordedRequests().size)

        val runtime = DeterministicIdentityRuntime()
        val config = config()
        val store = emptyProviderStore(config)
        val provider = OidcIdentityProvider(
            config = config,
            runtime = runtime.runtime,
            store = store
        )
        runtime.deterministicHttp.enqueue(jsonResponse(discovery()))
        val enabledStart = assertIs<OidcResult.Success<OidcAuthorizationStart>>(
            provider.beginAuthorization(OidcAuthorizationRequest(BINDING))
        ).value
        val manager = IdentityFederationProviderManager(store, runtime.runtime)
        assertIs<codes.yousef.aether.auth.IdentityOperationResult.Success<*>>(
            manager.disableProvider(
                organizationId = enabledStart.providerLease.organizationId,
                kind = enabledStart.providerLease.kind,
                providerId = enabledStart.providerLease.providerId,
                storageKey = enabledStart.providerLease.storageKey
            )
        )
        val disabled = assertIs<OidcResult.Failure>(
            provider.completeAuthorization(
                callback(
                    enabledStart,
                    queryParameter(enabledStart.authorizationUrl, "state"),
                    BINDING
                )
            )
        )
        assertEquals(OidcErrorCode.PROVIDER_DISABLED, disabled.error.code)
        assertEquals(1, runtime.deterministicHttp.recordedRequests().size)

        assertIs<codes.yousef.aether.auth.IdentityOperationResult.Success<*>>(
            manager.enableProvider(
                organizationId = enabledStart.providerLease.organizationId,
                kind = enabledStart.providerLease.kind,
                providerId = enabledStart.providerLease.providerId,
                storageKey = enabledStart.providerLease.storageKey
            )
        )
        val staleAfterReenable = assertIs<OidcResult.Failure>(
            provider.completeAuthorization(
                callback(
                    enabledStart,
                    queryParameter(enabledStart.authorizationUrl, "state"),
                    BINDING
                )
            )
        )
        assertEquals(OidcErrorCode.PROVIDER_DISABLED, staleAfterReenable.error.code)
        assertEquals(1, runtime.deterministicHttp.recordedRequests().size)
        assertEquals(
            codes.yousef.aether.auth.ChallengeState.PENDING,
            store.snapshot().challenges.single().state
        )
    }

    private suspend fun fixture(
        linked: Boolean,
        linkOnBegin: Boolean = false,
        runtime: DeterministicIdentityRuntime = DeterministicIdentityRuntime(),
        jitProvisioningEnabled: Boolean = false,
        existingEmailUser: Boolean = false
    ): Fixture {
        val config = config(jitProvisioningEnabled)
        val providerKey = providerStorageKey(config, runtime.runtime.crypto)
        val user = IdentityFixtures.user(USER_ID).let { fixtureUser ->
            if (existingEmailUser) {
                fixtureUser.copy(primaryEmail = codes.yousef.aether.auth.EmailAddress("user-1@example.test"))
            } else {
                fixtureUser
            }
        }
        val externalIdentity = IdentityFixtures.externalIdentity(
            userId = USER_ID,
            provider = providerKey,
            subject = SUBJECT
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                organizations = listOf(providerOrganization(config)),
                externalIdentities = if (linked) listOf(externalIdentity) else emptyList()
            )
        )
        val provider = OidcIdentityProvider(config, runtime.runtime, store)
        runtime.deterministicHttp.enqueue(jsonResponse(discovery()))
        return Fixture(runtime, store, provider, config, BINDING.copyOf(), linkOnBegin)
    }

    private inner class Fixture(
        val runtime: DeterministicIdentityRuntime,
        val store: InMemoryIdentityStore,
        val provider: OidcIdentityProvider,
        val config: OidcProviderConfig,
        val binding: ByteArray,
        val linkOnBegin: Boolean
    ) {
        val http get() = runtime.deterministicHttp
        suspend fun providerKey(): String = store.snapshot().federationProviderControls.single().storageKey

        suspend fun begin(): OidcAuthorizationStart = assertIs<OidcResult.Success<OidcAuthorizationStart>>(
            provider.beginAuthorization(
                OidcAuthorizationRequest(binding, linkToUserId = if (linkOnBegin) USER_ID else null)
            )
        ).value

        suspend fun complete(): OidcResult<OidcAuthenticationResult> {
            val start = begin()
            val state = queryParameter(start.authorizationUrl, "state")
            val nonce = queryParameter(start.authorizationUrl, "nonce")
            runtime.deterministicHttp.enqueue(jsonResponse(tokenResponse(idToken(nonce))))
            runtime.deterministicHttp.enqueue(jsonResponse(jwks("active-key")))
            return provider.completeAuthorization(callback(start, state, binding))
        }
    }

    private fun config(jitProvisioningEnabled: Boolean = false) = OidcProviderConfig(
        tenantId = OrganizationId("tenant-1"),
        providerId = "workforce",
        issuer = ISSUER,
        clientId = CLIENT_ID,
        redirectUri = "https://app.example.test/identity/v1/federation/tenant-1/workforce/callback",
        jitProvisioningEnabled = jitProvisioningEnabled
    )

    private fun providerOrganization(config: OidcProviderConfig) = IdentityFixtures.organization(
        id = config.tenantId,
        slug = "tenant-one"
    )

    private fun emptyProviderStore(config: OidcProviderConfig) = InMemoryIdentityStore(
        InMemoryIdentityStoreSeed(organizations = listOf(providerOrganization(config)))
    )

    private class RejectingJitLinkStore(
        private val delegate: IdentityStore,
    ) : IdentityStore by delegate {
        var capturedProvisioning: FederationJitProvisioning? = null

        override suspend fun linkExternalIdentity(
            command: LinkExternalIdentityCommand
        ): StoreResult<ExternalIdentityLinkCommit> {
            capturedProvisioning = command.jitProvisioning
            return StoreResult.Failure(
                IdentityStoreError(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            )
        }
    }

    private fun request(binding: ByteArray) = OidcAuthorizationRequest(binding)

    private fun callback(start: OidcAuthorizationStart, state: String, binding: ByteArray) =
        OidcCallbackRequest(
            state = state,
            authorizationCode = "authorization-code",
            callbackBinding = binding,
            callbackSecret = start.callbackSecret,
            providerLease = start.providerLease
        )

    private fun discovery(
        issuer: String = ISSUER,
        pkceMethods: String = "[\"S256\"]",
        tokenEndpoint: String = TOKEN_ENDPOINT
    ): String = """{
        "issuer":"$issuer",
        "authorization_endpoint":"$AUTHORIZATION_ENDPOINT",
        "token_endpoint":"$tokenEndpoint",
        "jwks_uri":"$JWKS_URI",
        "response_types_supported":["code"],
        "subject_types_supported":["public"],
        "code_challenge_methods_supported":$pkceMethods,
        "id_token_signing_alg_values_supported":["ES256","RS256"],
        "token_endpoint_auth_methods_supported":["none"]
    }""".trimIndent()

    private fun jwks(keyId: String): String {
        val x = Base64Url.encode(ByteArray(32) { 1 })
        val y = Base64Url.encode(ByteArray(32) { 2 })
        return """{"keys":[{"kty":"EC","kid":"$keyId","use":"sig","key_ops":["verify"],"alg":"ES256","crv":"P-256","x":"$x","y":"$y"}]}"""
    }

    private fun rsaJwks(keyId: String): String {
        val modulus = ByteArray(256) { index -> if (index == 0) 0x80.toByte() else (index + 1).toByte() }
        val exponent = byteArrayOf(0x01, 0x00, 0x01)
        return """{"keys":[{"kty":"RSA","kid":"$keyId","use":"sig","alg":"RS256","n":"${Base64Url.encode(modulus)}","e":"${Base64Url.encode(exponent)}"}]}"""
    }

    private fun idToken(nonce: String, keyId: String = "active-key", algorithm: String = "ES256"): String {
        val now = IdentityFixtures.baseInstant.epochSeconds
        return jwt(claimsJson(nonce, now), keyId, algorithm)
    }

    private fun claimsJson(
        nonce: String,
        now: Long,
        issuer: String = ISSUER,
        audienceJson: String = "\"$CLIENT_ID\"",
        authorizedParty: String? = null,
        issuedAt: Long = now,
        expiresAt: Long = now + 600
    ): String = buildString {
        append("{\"iss\":\"").append(issuer).append("\",")
        append("\"sub\":\"").append(SUBJECT.value).append("\",")
        append("\"aud\":").append(audienceJson).append(',')
        authorizedParty?.let { append("\"azp\":\"").append(it).append("\",") }
        append("\"exp\":").append(expiresAt).append(',')
        append("\"iat\":").append(issuedAt).append(',')
        append("\"nonce\":\"").append(nonce).append("\",")
        append("\"email\":\"user-1@example.test\",\"name\":\"OIDC User\"}")
    }

    private fun jwt(claimsJson: String, keyId: String, algorithm: String): String {
        val header = """{"alg":"$algorithm","kid":"$keyId","typ":"JWT"}"""
        val signatureSize = if (algorithm == "RS256") 256 else 64
        return Base64Url.encode(header.encodeToByteArray()) + "." +
            Base64Url.encode(claimsJson.encodeToByteArray()) + "." +
            Base64Url.encode(ByteArray(signatureSize) { 7 })
    }

    private fun tokenResponse(idToken: String): String =
        """{"token_type":"Bearer","id_token":"$idToken","access_token":"not-consumed"}"""

    private fun jsonResponse(body: String): IdentityHttpResponse = IdentityHttpResponse(
        statusCode = 200,
        headers = mapOf("Content-Type" to "application/json"),
        body = body.encodeToByteArray()
    )

    private fun queryParameter(url: String, name: String): String = url.substringAfter('?')
        .split('&')
        .first { it.substringBefore('=') == name }
        .substringAfter('=')

    private companion object {
        const val ISSUER = "https://issuer.example.test"
        const val CLIENT_ID = "aether-client"
        const val AUTHORIZATION_ENDPOINT = "https://issuer.example.test/authorize"
        const val TOKEN_ENDPOINT = "https://issuer.example.test/token"
        const val JWKS_URI = "https://issuer.example.test/jwks"
        val USER_ID = UserId("user-1")
        val SUBJECT = ExternalSubject("provider-subject-1")
        val BINDING = ByteArray(32) { (it + 1).toByte() }
    }
}
