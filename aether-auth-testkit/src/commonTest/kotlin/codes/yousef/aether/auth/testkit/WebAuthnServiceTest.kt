package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import codes.yousef.aether.auth.webauthn.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WebAuthnServiceTest {
    @Test
    fun `registration and username-free authentication issue a digest-only session`() = runTest {
        val fixture = Fixture()
        val registration = fixture.register()
        assertEquals("Primary passkey", registration.credential.name)
        assertTrue(registration.credential.discoverable)

        val started = fixture.service.startAuthentication(fixture.binding).expectOperationSuccess()
        assertTrue(started.publicKey.allowCredentials.isEmpty())
        val completed = fixture.service.finishAuthentication(
            ceremonyId = started.ceremonyId,
            browserCredential = fixture.assertion(started, registration.credential.webAuthnId, 1),
            binding = fixture.binding
        ).expectOperationSuccess()

        val cookie = completed.issuedSession.cookieValue()
        assertEquals(1, cookie.count { it == '.' })
        assertTrue(cookie.startsWith("${completed.issuedSession.session.id}."))
        assertNotEquals(cookie.substringAfter('.'), completed.issuedSession.session.tokenDigest.encoded)
        assertEquals(DigestAlgorithm.HMAC_SHA256, completed.issuedSession.session.tokenDigest.algorithm)
        assertEquals(fixture.config.keys.sessionPepper.version, completed.issuedSession.session.tokenDigest.keyVersion)
        assertEquals(AuthenticationAssurance.PASSKEY, completed.issuedSession.session.assurance)
        assertEquals(SessionState.ACTIVE, fixture.store.findSession(completed.issuedSession.session.id).expectSuccess()?.state)
        assertEquals(CredentialState.ACTIVE, completed.credential.state)
        assertEquals(1, completed.credential.signCount)
    }

    @Test
    fun `every malformed finish consumes its ceremony`() = runTest {
        val fixture = Fixture()
        val started = fixture.service.startRegistration(
            fixture.user.id,
            fixture.binding,
            IdentityRegistrationSource.EXISTING_ACCOUNT
        ).expectOperationSuccess()
        val valid = fixture.registrationCredential(started)
        val malformed = valid.copy(
            response = valid.response.copy(
                clientDataJSON = clientData(
                    type = "webauthn.create",
                    challenge = started.publicKey.challenge,
                    origin = "https://attacker.example"
                )
            )
        )

        val result = fixture.service.finishRegistration(
            started.ceremonyId,
            "Primary passkey",
            malformed,
            fixture.binding,
            IdentityRegistrationSource.EXISTING_ACCOUNT
        )

        assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, (result as IdentityOperationResult.Failure).code)
        val snapshot = fixture.store.snapshot()
        assertEquals(ChallengeState.FAILED, snapshot.challenges.single().state)
        assertEquals(1, snapshot.challenges.single().attemptCount)
        val rejection = snapshot.auditEvents.single()
        assertEquals(AuditAction.WEBAUTHN_CEREMONY_REJECTED, rejection.action)
        assertEquals(AuditTargetType.CHALLENGE, rejection.target?.type)
        assertEquals(started.ceremonyId.value, rejection.target?.id)
        assertEquals(AuditOutcome.DENIED, rejection.outcome)
        assertEquals(IdentityErrorCode.INVALID_CREDENTIALS.wireName, rejection.reasonCode)
        assertEquals(
            IdentityErrorCode.CHALLENGE_INVALID,
            (fixture.service.finishRegistration(
                started.ceremonyId,
                "Primary passkey",
                valid,
                fixture.binding,
                IdentityRegistrationSource.EXISTING_ACCOUNT
            ) as IdentityOperationResult.Failure).code
        )
        assertEquals(1, fixture.store.snapshot().auditEvents.size)
    }

    @Test
    fun `duplicate credential rejection atomically fails ceremony and exposes no uniqueness oracle`() = runTest {
        val fixture = Fixture()
        val registered = fixture.register()
        val started = fixture.service.startRegistration(
            fixture.user.id,
            fixture.binding,
            IdentityRegistrationSource.EXISTING_ACCOUNT
        ).expectOperationSuccess()

        val duplicate = fixture.service.finishRegistration(
            ceremonyId = started.ceremonyId,
            credentialName = "Duplicate passkey",
            browserCredential = fixture.registrationCredential(started),
            binding = fixture.binding,
            registrationSource = IdentityRegistrationSource.EXISTING_ACCOUNT
        )

        assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, (duplicate as IdentityOperationResult.Failure).code)
        val snapshot = fixture.store.snapshot()
        assertEquals(listOf(registered.credential), snapshot.credentials)
        val challenge = snapshot.challenges.single { it.id == started.ceremonyId }
        assertEquals(ChallengeState.FAILED, challenge.state)
        assertEquals(1, challenge.attemptCount)
        val rejection = snapshot.auditEvents.single {
            it.action == AuditAction.WEBAUTHN_CEREMONY_REJECTED && it.target?.id == started.ceremonyId.value
        }
        assertEquals(WEBAUTHN_STORE_REJECTION_REASON_CODE, rejection.reasonCode)
        assertEquals(AuditOutcome.DENIED, rejection.outcome)
    }

    @Test
    fun `expired registration and authentication finishes atomically expire and audit ceremonies`() = runTest {
        val registrationFixture = Fixture()
        val registration = registrationFixture.service
            .startRegistration(
                registrationFixture.user.id,
                registrationFixture.binding,
                IdentityRegistrationSource.EXISTING_ACCOUNT
            )
            .expectOperationSuccess()
        registrationFixture.deterministic.deterministicClock.advanceMilliseconds(
            registrationFixture.config.lifetimes.challenge.seconds * 1_000
        )
        val request = AuditRequestMetadata(
            requestId = "expired-registration",
            method = "POST",
            path = "/identity/v1/passkeys/register/finish"
        )

        val expiredRegistration = registrationFixture.service.finishRegistration(
            ceremonyId = registration.ceremonyId,
            credentialName = "Primary passkey",
            browserCredential = registrationFixture.registrationCredential(registration),
            binding = registrationFixture.binding,
            registrationSource = IdentityRegistrationSource.EXISTING_ACCOUNT,
            request = request
        )

        assertEquals(
            IdentityErrorCode.CHALLENGE_INVALID,
            (expiredRegistration as IdentityOperationResult.Failure).code
        )
        val registrationSnapshot = registrationFixture.store.snapshot()
        assertEquals(ChallengeState.EXPIRED, registrationSnapshot.challenges.single().state)
        assertEquals(0, registrationSnapshot.challenges.single().attemptCount)
        val registrationAudit = registrationSnapshot.auditEvents.single()
        assertEquals(AuditAction.WEBAUTHN_CEREMONY_REJECTED, registrationAudit.action)
        assertEquals("webauthn_challenge_expired", registrationAudit.reasonCode)
        assertEquals(request, registrationAudit.request)

        val authenticationFixture = Fixture()
        authenticationFixture.register()
        val authentication = authenticationFixture.service
            .startAuthentication(authenticationFixture.binding)
            .expectOperationSuccess()
        authenticationFixture.deterministic.deterministicClock.advanceMilliseconds(
            authenticationFixture.config.lifetimes.challenge.seconds * 1_000
        )

        val expiredAuthentication = authenticationFixture.service.finishAuthentication(
            ceremonyId = authentication.ceremonyId,
            browserCredential = authenticationFixture.assertion(
                authentication,
                authenticationFixture.store.snapshot().credentials.single().webAuthnId,
                signCount = 1
            ),
            binding = authenticationFixture.binding
        )

        assertEquals(
            IdentityErrorCode.CHALLENGE_INVALID,
            (expiredAuthentication as IdentityOperationResult.Failure).code
        )
        val authenticationSnapshot = authenticationFixture.store.snapshot()
        assertEquals(
            ChallengeState.EXPIRED,
            authenticationSnapshot.challenges.single { it.id == authentication.ceremonyId }.state
        )
        assertEquals(
            1,
            authenticationSnapshot.auditEvents.count {
                it.action == AuditAction.WEBAUTHN_CEREMONY_REJECTED &&
                    it.target?.id == authentication.ceremonyId.value &&
                    it.reasonCode == "webauthn_challenge_expired"
            }
        )
    }

    @Test
    fun `registration completion is bound to the authenticated user`() = runTest {
        val fixture = Fixture()
        val started = fixture.service.startRegistration(
            fixture.user.id,
            fixture.binding,
            IdentityRegistrationSource.EXISTING_ACCOUNT
        ).expectOperationSuccess()

        val result = fixture.service.finishRegistration(
            ceremonyId = started.ceremonyId,
            credentialName = "Primary passkey",
            browserCredential = fixture.registrationCredential(started),
            binding = fixture.binding,
            registrationSource = IdentityRegistrationSource.EXISTING_ACCOUNT,
            expectedUserId = UserId("different-user")
        )

        assertEquals(IdentityErrorCode.CHALLENGE_INVALID, (result as IdentityOperationResult.Failure).code)
        assertEquals(ChallengeState.FAILED, fixture.store.findChallenge(started.ceremonyId).expectSuccess()?.state)
        assertTrue(fixture.store.snapshot().credentials.isEmpty())
    }

    @Test
    fun `non-increasing nonzero counter consumes challenge and quarantines credential`() = runTest {
        val fixture = Fixture()
        val registered = fixture.register().credential
        val firstStart = fixture.service.startAuthentication(fixture.binding).expectOperationSuccess()
        fixture.service.finishAuthentication(
            firstStart.ceremonyId,
            fixture.assertion(firstStart, registered.webAuthnId, 7),
            fixture.binding
        ).expectOperationSuccess()

        val replayStart = fixture.service.startAuthentication(fixture.binding).expectOperationSuccess()
        val replay = fixture.service.finishAuthentication(
            replayStart.ceremonyId,
            fixture.assertion(replayStart, registered.webAuthnId, 7),
            fixture.binding
        )

        assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, (replay as IdentityOperationResult.Failure).code)
        val snapshot = fixture.store.snapshot()
        val quarantined = snapshot.credentials.single()
        assertEquals(CredentialState.SUSPECTED_CLONE, quarantined.state)
        assertEquals("signature_counter_anomaly", quarantined.revocationReasonCode)
        assertEquals(
            ChallengeState.CONSUMED,
            snapshot.challenges.single { it.id == replayStart.ceremonyId }.state
        )
        val quarantineAudit = snapshot.auditEvents.single { it.action == AuditAction.CREDENTIAL_QUARANTINED }
        assertEquals(AuditOutcome.DENIED, quarantineAudit.outcome)
        assertEquals(1, snapshot.sessions.size, "counter replay must not issue another session")
    }

    @Test
    fun `backup eligibility cannot change while backup state is tracked`() = runTest {
        val fixture = Fixture()
        val registered = fixture.register(backupEligible = true, backedUp = false).credential
        val started = fixture.service.startAuthentication(fixture.binding).expectOperationSuccess()

        val completed = fixture.service.finishAuthentication(
            started.ceremonyId,
            fixture.assertion(started, registered.webAuthnId, 0, backupEligible = true, backedUp = true),
            fixture.binding
        ).expectOperationSuccess()

        assertTrue(completed.credential.backupEligible)
        assertTrue(completed.credential.backedUp)

        val invalidStart = fixture.service.startAuthentication(fixture.binding).expectOperationSuccess()
        val invalid = fixture.service.finishAuthentication(
            invalidStart.ceremonyId,
            fixture.assertion(invalidStart, registered.webAuthnId, 0, backupEligible = false, backedUp = false),
            fixture.binding
        )
        assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, (invalid as IdentityOperationResult.Failure).code)
        assertEquals(ChallengeState.FAILED, fixture.store.findChallenge(invalidStart.ceremonyId).expectSuccess()?.state)
    }

    @Test
    fun `authentication rejects wrong type challenge RP hash and missing user-presence flags`() = runTest {
        suspend fun reject(
            fixture: Fixture = Fixture(),
            credential: suspend Fixture.(WebAuthnAuthenticationStartResponse, Credential) ->
                AuthenticationPublicKeyCredentialDto
        ) {
            val registered = fixture.register().credential
            val started = fixture.service.startAuthentication(fixture.binding).expectOperationSuccess()
            val result = fixture.service.finishAuthentication(
                ceremonyId = started.ceremonyId,
                browserCredential = fixture.credential(started, registered),
                binding = fixture.binding
            )

            assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, (result as IdentityOperationResult.Failure).code)
            assertEquals(
                ChallengeState.FAILED,
                fixture.store.findChallenge(started.ceremonyId).expectSuccess()?.state
            )
            assertTrue(fixture.store.snapshot().sessions.isEmpty())
        }

        reject { started, registered ->
            assertion(started, registered.webAuthnId, 1).let { assertion ->
                assertion.copy(
                    response = assertion.response.copy(
                        clientDataJSON = clientData(
                            type = "webauthn.create",
                            challenge = started.publicKey.challenge,
                            origin = config.publicBaseUrl
                        )
                    )
                )
            }
        }
        reject { started, registered ->
            assertion(started, registered.webAuthnId, 1).let { assertion ->
                assertion.copy(
                    response = assertion.response.copy(
                        clientDataJSON = clientData(
                            type = "webauthn.get",
                            challenge = Base64Url.encode(ByteArray(32) { 0x7f }),
                            origin = config.publicBaseUrl
                        )
                    )
                )
            }
        }
        reject { started, registered ->
            assertion(started, registered.webAuthnId, 1, rpId = "attacker.example")
        }
        reject { started, registered ->
            assertion(started, registered.webAuthnId, 1, flagsOverride = 0x04)
        }
        reject { started, registered ->
            assertion(started, registered.webAuthnId, 1, flagsOverride = 0x01)
        }
    }

    @Test
    fun `authentication rejects invalid ES256 signatures`() = runTest {
        val fixture = Fixture(
            deterministicCrypto = DeterministicIdentityCrypto(verifyEs256Result = false)
        )
        val registered = fixture.register().credential
        val started = fixture.service.startAuthentication(fixture.binding).expectOperationSuccess()

        val result = fixture.service.finishAuthentication(
            ceremonyId = started.ceremonyId,
            browserCredential = fixture.assertion(started, registered.webAuthnId, 1),
            binding = fixture.binding
        )

        assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, (result as IdentityOperationResult.Failure).code)
        assertEquals(ChallengeState.FAILED, fixture.store.findChallenge(started.ceremonyId).expectSuccess()?.state)
        assertTrue(fixture.store.snapshot().sessions.isEmpty())
    }

    @Test
    fun `step-up rejects a credential belonging to another user`() = runTest {
        val primary = IdentityFixtures.user(IdentityFixtures.userId("webauthn-primary"))
        val other = IdentityFixtures.user(IdentityFixtures.userId("webauthn-other"))
        val otherCredential = IdentityFixtures.credential(
            id = IdentityFixtures.credentialId("webauthn-other"),
            userId = other.id
        )
        val fixture = Fixture(
            user = primary,
            additionalUsers = listOf(other),
            initialCredentials = listOf(otherCredential)
        )
        fixture.register()
        val started = fixture.service.startStepUp(primary.id, fixture.binding).expectOperationSuccess()

        val result = fixture.service.finishAuthentication(
            ceremonyId = started.ceremonyId,
            browserCredential = fixture.assertion(
                started,
                otherCredential.webAuthnId,
                signCount = 1,
                userHandleId = other.id
            ),
            binding = fixture.binding,
            rotatedFrom = IdentityFixtures.session(
                id = IdentityFixtures.sessionId("primary-step-up"),
                userId = primary.id
            )
        )

        assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, (result as IdentityOperationResult.Failure).code)
        assertEquals(ChallengeState.FAILED, fixture.store.findChallenge(started.ceremonyId).expectSuccess()?.state)
        assertTrue(fixture.store.snapshot().sessions.isEmpty())
    }

    @Test
    fun `authentication rejects a foreign predecessor session instead of rotating it`() = runTest {
        val primary = IdentityFixtures.user(IdentityFixtures.userId("session-primary"))
        val other = IdentityFixtures.user(IdentityFixtures.userId("session-other"))
        val foreignSession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("foreign-predecessor"),
            userId = other.id
        )
        val fixture = Fixture(
            user = primary,
            additionalUsers = listOf(other),
            initialSessions = listOf(foreignSession)
        )
        val registered = fixture.register().credential
        val started = fixture.service.startAuthentication(fixture.binding).expectOperationSuccess()

        val result = fixture.service.finishAuthentication(
            ceremonyId = started.ceremonyId,
            browserCredential = fixture.assertion(started, registered.webAuthnId, 1),
            binding = fixture.binding,
            rotatedFrom = foreignSession
        )

        assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, (result as IdentityOperationResult.Failure).code)
        val snapshot = fixture.store.snapshot()
        assertEquals(ChallengeState.FAILED, snapshot.challenges.single { it.id == started.ceremonyId }.state)
        assertEquals(foreignSession, snapshot.sessions.single())
    }

    @Test
    fun `recovery enrollment atomically installs passkey rotates codes and revokes every session`() = runTest {
        val fixture = Fixture()
        val recovery = IdentityRecoveryService(fixture.store, fixture.deterministic.runtime, fixture.config)
        val initialCodes = recovery.replaceCodes(fixture.user.id, expectedGeneration = null)
            .expectOperationSuccess()
        val recovered = recovery.recover(initialCodes.codes.first().reveal()).expectOperationSuccess()
        val started = fixture.service.startRegistration(
            fixture.user.id,
            fixture.binding,
            IdentityRegistrationSource.RECOVERY
        ).expectOperationSuccess()

        val completed = fixture.service.finishRegistration(
            ceremonyId = started.ceremonyId,
            credentialName = "Recovered passkey",
            browserCredential = fixture.registrationCredential(started),
            binding = fixture.binding,
            registrationSource = IdentityRegistrationSource.RECOVERY,
            recoverySession = recovered.issuedSession.session
        ).expectOperationSuccess()

        assertTrue(completed.clearRecoverySessionCookie)
        val replacement = requireNotNull(completed.replacementRecoveryCodes)
        assertEquals(1L, replacement.generation)
        assertEquals(10, replacement.codes.size)
        val snapshot = fixture.store.snapshot()
        assertEquals(1L, snapshot.users.single().sessionEpoch)
        assertEquals(SessionState.REVOKED, snapshot.sessions.single().state)
        assertEquals(10, snapshot.recoveryCodes.count { it.state == RecoveryCodeState.ACTIVE && it.generation == 1L })
        assertEquals(0, snapshot.recoveryCodes.count { it.state == RecoveryCodeState.ACTIVE && it.generation == 0L })
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.RECOVERY_ENROLLMENT_COMPLETED })

        recovery.recover(replacement.codes.first().reveal()).expectOperationSuccess()
    }

    @Test
    fun `invitation enrollment session completes through passkey registration and emits recovery codes`() = runTest {
        val invitationSession = IdentityFixtures.session(
            id = SessionId("invitation-enrollment-session"),
            assurance = AuthenticationAssurance.RECOVERY,
            authenticationMethod = SessionAuthenticationMethod.INVITATION
        )
        val fixture = Fixture(initialSessions = listOf(invitationSession))
        val started = fixture.service.startRegistration(
            fixture.user.id,
            fixture.binding,
            IdentityRegistrationSource.ADMIN_INVITATION
        ).expectOperationSuccess()

        val completed = fixture.service.finishRegistration(
            ceremonyId = started.ceremonyId,
            credentialName = "Invitation passkey",
            browserCredential = fixture.registrationCredential(started),
            binding = fixture.binding,
            registrationSource = IdentityRegistrationSource.ADMIN_INVITATION,
            recoverySession = invitationSession
        ).expectOperationSuccess()

        assertTrue(completed.clearRecoverySessionCookie)
        assertEquals(10, requireNotNull(completed.replacementRecoveryCodes).codes.size)
        val snapshot = fixture.store.snapshot()
        assertEquals(1L, snapshot.users.single().sessionEpoch)
        assertEquals(SessionState.REVOKED, snapshot.sessions.single().state)
        assertEquals(10, snapshot.recoveryCodes.count { it.state == RecoveryCodeState.ACTIVE })
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.RECOVERY_ENROLLMENT_COMPLETED })
    }

    @Test
    fun `registration service enforces policy and binds trusted provenance into its challenge`() = runTest {
        val fixture = Fixture()
        val rejected = fixture.service.startRegistration(
            fixture.user.id,
            fixture.binding,
            IdentityRegistrationSource.PUBLIC
        )
        assertEquals(
            IdentityErrorCode.REGISTRATION_NOT_ALLOWED,
            (rejected as IdentityOperationResult.Failure).code
        )
        assertTrue(fixture.store.snapshot().challenges.isEmpty())

        val started = fixture.service.startRegistration(
            fixture.user.id,
            fixture.binding,
            IdentityRegistrationSource.ADMIN_INVITATION
        ).expectOperationSuccess()
        val switchedSource = fixture.service.finishRegistration(
            ceremonyId = started.ceremonyId,
            credentialName = "Source switch",
            browserCredential = fixture.registrationCredential(started),
            binding = fixture.binding,
            registrationSource = IdentityRegistrationSource.EXISTING_ACCOUNT
        )
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            (switchedSource as IdentityOperationResult.Failure).code
        )
        assertEquals(ChallengeState.FAILED, fixture.store.snapshot().challenges.single().state)

        val disabled = Fixture(registrationPolicy = RegistrationPolicy.DISABLED)
        assertEquals(
            IdentityErrorCode.REGISTRATION_NOT_ALLOWED,
            (disabled.service.startRegistration(
                disabled.user.id,
                disabled.binding,
                IdentityRegistrationSource.ADMIN_INVITATION
            ) as IdentityOperationResult.Failure).code
        )
    }

    private class Fixture(
        val user: User = IdentityFixtures.user(),
        initialSessions: List<IdentitySession> = emptyList(),
        registrationPolicy: RegistrationPolicy = RegistrationPolicy.INVITATION_ONLY,
        additionalUsers: List<User> = emptyList(),
        initialCredentials: List<Credential> = emptyList(),
        deterministicCrypto: DeterministicIdentityCrypto = DeterministicIdentityCrypto()
    ) {
        val config = identityConfig(registrationPolicy)
        val deterministic = DeterministicIdentityRuntime(
            deterministicCrypto = deterministicCrypto,
            deterministicSecrets = DeterministicIdentitySecretResolver(
                mapOf(
                    config.keys.sessionPepper to ByteArray(32) { 0x31 },
                    config.keys.recoveryPepper to ByteArray(32) { 0x42 }
                )
            )
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user) + additionalUsers,
                credentials = initialCredentials,
                sessions = initialSessions
            )
        )
        val service = WebAuthnService(store, deterministic.runtime, config)
        val binding: WebAuthnCeremonyBinding = issueWebAuthnCeremonyBinding(deterministic.runtime).binding

        suspend fun register(
            backupEligible: Boolean = false,
            backedUp: Boolean = false
        ): CompletedWebAuthnRegistration {
            val started = service.startRegistration(
                user.id,
                binding,
                IdentityRegistrationSource.EXISTING_ACCOUNT
            ).expectOperationSuccess()
            return service.finishRegistration(
                started.ceremonyId,
                "Primary passkey",
                registrationCredential(started, backupEligible, backedUp),
                binding,
                IdentityRegistrationSource.EXISTING_ACCOUNT
            ).expectOperationSuccess()
        }

        suspend fun registrationCredential(
            started: WebAuthnRegistrationStartResponse,
            backupEligible: Boolean = false,
            backedUp: Boolean = false
        ): RegistrationPublicKeyCredentialDto {
            val rawId = Base64Url.encode(byteArrayOf(9, 8, 7, 6))
            val rpHash = deterministic.runtime.crypto.sha256(config.relyingParty.id.encodeToByteArray())
            val flags = 0x45 or (if (backupEligible) 0x08 else 0) or (if (backedUp) 0x10 else 0)
            val cose = coseKey()
            val authData = rpHash + byteArrayOf(flags.toByte(), 0, 0, 0, 0) +
                ByteArray(16) + byteArrayOf(0, 4) + byteArrayOf(9, 8, 7, 6) + cose
            val attestation = cborMap(
                cborText("fmt") to cborText("none"),
                cborText("authData") to cborBytes(authData),
                cborText("attStmt") to cborMap()
            )
            return RegistrationPublicKeyCredentialDto(
                id = rawId,
                rawId = rawId,
                type = "public-key",
                response = AuthenticatorAttestationResponseDto(
                    clientDataJSON = clientData(
                        "webauthn.create",
                        started.publicKey.challenge,
                        config.publicBaseUrl
                    ),
                    attestationObject = Base64Url.encode(attestation),
                    transports = listOf("internal", "hybrid")
                ),
                clientExtensionResults = emptyMap()
            )
        }

        suspend fun assertion(
            started: WebAuthnAuthenticationStartResponse,
            id: WebAuthnCredentialId,
            signCount: Long,
            backupEligible: Boolean = false,
            backedUp: Boolean = false,
            rpId: String = config.relyingParty.id,
            flagsOverride: Int? = null,
            userHandleId: UserId = user.id
        ): AuthenticationPublicKeyCredentialDto {
            val rpHash = deterministic.runtime.crypto.sha256(rpId.encodeToByteArray())
            val flags = flagsOverride
                ?: (0x05 or (if (backupEligible) 0x08 else 0) or (if (backedUp) 0x10 else 0))
            val authData = rpHash + byteArrayOf(
                flags.toByte(),
                (signCount ushr 24).toByte(),
                (signCount ushr 16).toByte(),
                (signCount ushr 8).toByte(),
                signCount.toByte()
            )
            return AuthenticationPublicKeyCredentialDto(
                id = id.encoded,
                rawId = id.encoded,
                type = "public-key",
                response = AuthenticatorAssertionResponseDto(
                    clientDataJSON = clientData(
                        "webauthn.get",
                        started.publicKey.challenge,
                        config.publicBaseUrl
                    ),
                    authenticatorData = Base64Url.encode(authData),
                    signature = Base64Url.encode(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02)),
                    userHandle = Base64Url.encode(userHandleId.value.encodeToByteArray())
                ),
                clientExtensionResults = emptyMap()
            )
        }
    }
}

private fun identityConfig(
    registrationPolicy: RegistrationPolicy = RegistrationPolicy.INVITATION_ONLY
): IdentityConfig {
    fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
    return IdentityConfig(
        environment = IdentityEnvironment.TEST,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig(
            id = "localhost",
            name = "Aether Test",
            allowedOrigins = setOf("http://localhost:8080")
        ),
        keys = IdentityKeyConfig(
            sessionPepper = secret("session"),
            recoveryPepper = secret("recovery"),
            deviceTokenPepper = secret("device"),
            serviceCredentialPepper = secret("service"),
            auditPseudonymizationKey = secret("audit"),
            encryptionKey = secret("encryption"),
            signingKey = secret("signing")
        ),
        registrationPolicy = registrationPolicy
    )
}

private fun clientData(type: String, challenge: String, origin: String): String = Base64Url.encode(
    """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}"""
        .encodeToByteArray()
)

private fun coseKey(): ByteArray = cborMap(
    cborInteger(1) to cborInteger(2),
    cborInteger(3) to cborInteger(-7),
    cborInteger(-1) to cborInteger(1),
    cborInteger(-2) to cborBytes(ByteArray(32) { (it + 1).toByte() }),
    cborInteger(-3) to cborBytes(ByteArray(32) { (it + 33).toByte() })
)

private fun cborInteger(value: Long): ByteArray = when {
    value in 0..23 -> byteArrayOf(value.toByte())
    value < 0 && -1 - value <= 23 -> byteArrayOf((0x20 + (-1 - value)).toByte())
    else -> error("test CBOR integer is out of range")
}

private fun cborBytes(value: ByteArray): ByteArray = cborLength(2, value.size) + value
private fun cborText(value: String): ByteArray = value.encodeToByteArray().let { cborLength(3, it.size) + it }
private fun cborMap(vararg entries: Pair<ByteArray, ByteArray>): ByteArray =
    cborLength(5, entries.size) + entries.flatMap { (key, value) -> (key + value).asIterable() }.toByteArray()

private fun cborLength(major: Int, size: Int): ByteArray = when {
    size <= 23 -> byteArrayOf(((major shl 5) or size).toByte())
    size <= 0xff -> byteArrayOf(((major shl 5) or 24).toByte(), size.toByte())
    else -> byteArrayOf(((major shl 5) or 25).toByte(), (size ushr 8).toByte(), size.toByte())
}

private fun <T> IdentityOperationResult<T>.expectOperationSuccess(): T = when (this) {
    is IdentityOperationResult.Success -> value
    is IdentityOperationResult.Failure -> error("Expected operation success, got $code")
}
