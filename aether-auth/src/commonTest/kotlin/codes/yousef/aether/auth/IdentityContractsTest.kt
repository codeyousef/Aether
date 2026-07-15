package codes.yousef.aether.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class IdentityContractsTest {
    @Test
    fun `typed IDs reject non-opaque values and serialize as strings`() {
        assertFailsWith<IllegalArgumentException> { UserId("") }
        assertFailsWith<IllegalArgumentException> { UserId("contains whitespace") }
        assertFailsWith<IllegalArgumentException> { CredentialId("contains/slash") }

        val value = "01890f3e-4c7a-7b2c-8d5e-6f708192a3b4"
        val encoded = Json.encodeToString(UserId.parse(value))
        assertEquals("\"$value\"", encoded)
        assertEquals("usr_01HXYZ", UserId("usr_01HXYZ").value)
    }

    @Test
    fun `domain timestamps serialize with kotlin time Instant on every target`() {
        val instant = Instant.parse("2026-07-14T00:00:00Z")
        val user = User(
            id = UserId.parse("01890f3e-4c7a-7b2c-8d5e-6f708192a3b4"),
            state = UserState.ACTIVE,
            displayName = "Serialized user",
            createdAt = instant,
            updatedAt = instant
        )

        assertEquals(user, Json.decodeFromString<User>(Json.encodeToString(user)))
    }

    @Test
    fun `organization roles grant only their scoped capabilities`() {
        assertEquals(Capability.IDENTITY_MANAGEMENT, OrganizationRole.OWNER.capabilities)
        assertTrue(OrganizationRole.ADMIN.grants(Capability.MEMBERSHIP_REMOVE))
        assertTrue(OrganizationRole.OWNER.satisfies(OrganizationRole.ADMIN))
        assertTrue(OrganizationRole.ADMIN.satisfies(OrganizationRole.VIEWER))
        assertFalse(OrganizationRole.VIEWER.satisfies(OrganizationRole.PUBLISHER))
        assertFalse(OrganizationRole.ADMIN.grants(Capability.ORGANIZATION_DELETE))
        assertFalse(OrganizationRole.ADMIN.grants(Capability.ORGANIZATION_TRANSFER_OWNERSHIP))
        assertFalse(OrganizationRole.PUBLISHER.grants(Capability.CONTENT_PUBLISH))
        assertFalse(OrganizationRole.PUBLISHER.grants(Capability.MEMBERSHIP_UPDATE))
        assertFalse(OrganizationRole.VIEWER.grants(Capability.CONTENT_READ))
        assertFalse(OrganizationRole.VIEWER.grants(Capability.CONTENT_PUBLISH))
    }

    @Test
    fun `recovery and service assurance cannot impersonate passkey assurance`() {
        assertTrue(AuthenticationAssurance.STEP_UP.satisfies(AuthenticationAssurance.PASSKEY))
        assertTrue(AuthenticationAssurance.PASSKEY.satisfies(AuthenticationAssurance.SESSION))
        assertFalse(AuthenticationAssurance.RECOVERY.satisfies(AuthenticationAssurance.SESSION))
        assertFalse(AuthenticationAssurance.SERVICE_CREDENTIAL.satisfies(AuthenticationAssurance.PASSKEY))
        assertFalse(AuthenticationAssurance.PASSKEY.satisfies(AuthenticationAssurance.SERVICE_CREDENTIAL))
    }

    @Test
    fun `organization roles remain out of the global principal role set`() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val userId = UserId("usr_test")
        val organization = Organization(
            id = OrganizationId("org_test"),
            name = "Aether",
            slug = "aether-test",
            createdAt = now,
            updatedAt = now
        )
        val session = session(userId, now)
        val principal = IdentityPrincipal(
            kind = IdentityPrincipalKind.USER,
            userId = userId,
            displayName = "Test user",
            assurance = AuthenticationAssurance.PASSKEY,
            authenticatedAt = now,
            sessionId = session.id
        )
        val membership = Membership(
            id = MembershipId("mem_test"),
            organizationId = organization.id,
            userId = userId,
            role = OrganizationRole.PUBLISHER,
            createdAt = now,
            updatedAt = now
        )
        val context = IdentityContext(principal, session, organization, membership)

        assertTrue(principal.roles.isEmpty())
        assertTrue(context.hasRole(OrganizationRole.PUBLISHER))
        assertFalse(context.hasCapability(Capability.CONTENT_PUBLISH))
        assertTrue(
            context.hasCapability(
                Capability.CONTENT_PUBLISH,
                CapabilityResolver { scoped ->
                    if (scoped.membership?.role == OrganizationRole.PUBLISHER) {
                        setOf(Capability.CONTENT_PUBLISH)
                    } else {
                        emptySet()
                    }
                }
            )
        )
        assertFalse(context.hasCapability(Capability.MEMBERSHIP_UPDATE))
        assertFalse(
            context.hasCapability(
                Capability.MEMBERSHIP_UPDATE,
                CapabilityResolver { setOf(Capability.MEMBERSHIP_UPDATE) }
            )
        )

        val recoverySession = session.copy(
            assurance = AuthenticationAssurance.RECOVERY,
            authenticationMethod = SessionAuthenticationMethod.RECOVERY_CODE
        )
        val recoveryContext = IdentityContext(
            principal.copy(assurance = AuthenticationAssurance.RECOVERY),
            recoverySession,
            organization,
            membership
        )
        val overgrantingResolver = CapabilityResolver {
            setOf(Capability.CONTENT_PUBLISH, Capability.ORGANIZATION_DELETE)
        }
        assertTrue(recoveryContext.capabilities(overgrantingResolver).isEmpty())
        assertFalse(recoveryContext.hasRole(OrganizationRole.VIEWER))
    }

    @Test
    fun `secret-bearing values are redacted from string output`() {
        val marker = "never-print-this-secret"
        val digest = SecretDigest(DigestAlgorithm.HMAC_SHA256, marker, "v1")
        val reference = SecretReference(
            provider = "test",
            name = marker,
            version = "v1",
            environment = IdentityEnvironment.TEST
        )
        val request = IdentityHttpRequest(
            method = IdentityHttpMethod.POST,
            url = "https://example.com/token",
            headers = mapOf("Authorization" to marker),
            body = marker.encodeToByteArray()
        )
        val session = session(UserId("usr_redaction"), Instant.parse("2026-07-14T00:00:00Z"), digest)

        listOf(
            digest.toString(),
            reference.toString(),
            IdentitySecret.fromUtf8(marker).toString(),
            request.toString(),
            session.toString(),
            EmailAddress("secret@example.com").toString(),
            ExternalSubject(marker).toString()
        ).forEach { rendered ->
            assertFalse(marker in rendered, rendered)
        }
    }

    @Test
    fun `outbound HTTP requests require a bounded response allocation`() {
        assertEquals(
            DEFAULT_MAXIMUM_IDENTITY_HTTP_RESPONSE_BYTES,
            IdentityHttpRequest(IdentityHttpMethod.GET, "https://example.com").maximumResponseBytes
        )
        assertFailsWith<IllegalArgumentException> {
            IdentityHttpRequest(
                IdentityHttpMethod.GET,
                "https://example.com",
                maximumResponseBytes = MAXIMUM_IDENTITY_HTTP_RESPONSE_BYTES + 1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            IdentityHttpRequest(
                IdentityHttpMethod.POST,
                "https://example.com",
                body = ByteArray(MAXIMUM_IDENTITY_HTTP_REQUEST_BYTES + 1)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            IdentityHttpRequest(
                IdentityHttpMethod.GET,
                "https://example.com",
                headers = mapOf("X-Test" to "safe\r\ninjected: value")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            IdentityHttpRequest(
                IdentityHttpMethod.GET,
                "https://example.com",
                headers = mapOf("not a header" to "value")
            )
        }
    }

    @Test
    fun `wire errors use stable codes and fixed safe messages`() {
        val error = IdentityErrorDto(IdentityErrorCode.INTERNAL_ERROR, requestId = "req_123")
        val encoded = Json.encodeToString(IdentityErrorEnvelope(error))

        assertTrue("\"internal_error\"" in encoded)
        assertTrue(IdentityErrorCode.INTERNAL_ERROR.publicMessage in encoded)
        assertTrue("\"retryable\":false" in encoded)
        assertFalse("exception" in encoded.lowercase())
        assertFailsWith<IllegalArgumentException> {
            IdentityErrorDto(
                code = IdentityErrorCode.INTERNAL_ERROR,
                message = "database password leaked",
                retryable = false
            )
        }
    }

    @Test
    fun `organization audit cursor is opaque canonical and page size is bounded`() {
        val cursor = OrganizationAuditEventCursor(
            organizationId = OrganizationId.parse("01890f3e-4c7a-7b2c-8d5e-6f708192a3b4"),
            occurredAt = Instant.parse("2026-07-14T12:34:56.123456Z"),
            id = AuditEventId.parse("01890f3e-4c7a-7b2c-9d5e-6f708192a3b4")
        )
        val token = cursor.toOpaqueToken()

        assertEquals(cursor, OrganizationAuditEventCursor.fromOpaqueToken(token))
        assertFalse(token.contains(cursor.id.value))
        listOf("", "not+base64", "AQ", token + "A").forEach { malformed ->
            assertEquals(null, OrganizationAuditEventCursor.fromOpaqueToken(malformed))
        }
        assertFailsWith<IllegalArgumentException> {
            OrganizationAuditEventPageRequest(OrganizationId("org_test"), limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            OrganizationAuditEventPageRequest(
                OrganizationId("org_test"),
                limit = OrganizationAuditEventPageRequest.MAXIMUM_LIMIT + 1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OrganizationAuditEventPageRequest(OrganizationId("other_org"), cursor = cursor)
        }
    }

    @Test
    fun `invalid credential and session state combinations fail closed`() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        assertFailsWith<IllegalArgumentException> {
            Credential(
                id = CredentialId("cred_test"),
                webAuthnId = WebAuthnCredentialId("AQ"),
                userId = UserId("usr_test"),
                name = "Primary passkey",
                publicKey = CosePublicKey("pQECAyYgASFYIA"),
                signCount = 0,
                backupEligible = false,
                backedUp = true,
                discoverable = true,
                createdAt = now,
                updatedAt = now
            )
        }
        assertFailsWith<IllegalArgumentException> {
            session(UserId("usr_test"), now).copy(
                state = SessionState.REVOKED,
                revokedAt = null
            )
        }
    }

    private fun session(
        userId: UserId,
        now: Instant,
        digest: SecretDigest = SecretDigest(DigestAlgorithm.HMAC_SHA256, "digest-value", "v1")
    ): IdentitySession = IdentitySession(
        id = SessionId("ses_test"),
        familyId = SessionId("ses_test"),
        userId = userId,
        tokenDigest = digest,
        csrfDigest = digest,
        assurance = AuthenticationAssurance.PASSKEY,
        authenticationMethod = SessionAuthenticationMethod.PASSKEY,
        userSessionEpoch = 0,
        createdAt = now,
        authenticatedAt = now,
        lastUsedAt = now,
        idleExpiresAt = Instant.parse("2026-07-14T01:00:00Z"),
        absoluteExpiresAt = Instant.parse("2026-07-15T00:00:00Z")
    )
}
