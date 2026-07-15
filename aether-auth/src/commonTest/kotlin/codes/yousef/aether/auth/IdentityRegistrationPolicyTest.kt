package codes.yousef.aether.auth

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityRegistrationPolicyTest {
    @Test
    fun `every registration policy has an explicit source matrix`() {
        val expectedOnboardingSources = mapOf(
            RegistrationPolicy.OPEN to setOf(
                IdentityRegistrationSource.PUBLIC,
                IdentityRegistrationSource.INVITATION,
                IdentityRegistrationSource.ADMIN_INVITATION
            ),
            RegistrationPolicy.INVITATION_ONLY to setOf(
                IdentityRegistrationSource.INVITATION,
                IdentityRegistrationSource.ADMIN_INVITATION
            ),
            RegistrationPolicy.ADMIN_ONLY to setOf(
                IdentityRegistrationSource.ADMIN_INVITATION
            ),
            RegistrationPolicy.DISABLED to emptySet()
        )
        val policyControlledSources = setOf(
            IdentityRegistrationSource.PUBLIC,
            IdentityRegistrationSource.INVITATION,
            IdentityRegistrationSource.ADMIN_INVITATION
        )

        RegistrationPolicy.entries.forEach { policy ->
            val config = developmentConfig(policy)
            assertEquals(
                expectedOnboardingSources.getValue(policy),
                policyControlledSources.filterTo(linkedSetOf()) { config.allowsRegistration(it) },
                "Unexpected registration source matrix for $policy"
            )
            assertTrue(config.allowsRegistration(IdentityRegistrationSource.BOOTSTRAP))
            assertTrue(config.allowsRegistration(IdentityRegistrationSource.RECOVERY))
            assertTrue(config.allowsRegistration(IdentityRegistrationSource.EXISTING_ACCOUNT))
        }

        assertTrue(
            developmentConfig(RegistrationPolicy.INVITATION_ONLY).copy(
                bootstrapLifecycle = IdentityBootstrapLifecycle.RETIRED,
                bootstrapSecret = null
            ).allowsRegistration(IdentityRegistrationSource.BOOTSTRAP)
        )
    }

    @Test
    fun `public source requires both open policy and development environment`() {
        assertTrue(developmentConfig(RegistrationPolicy.OPEN).allowsRegistration(IdentityRegistrationSource.PUBLIC))
        assertFalse(
            testConfig(RegistrationPolicy.INVITATION_ONLY)
                .allowsRegistration(IdentityRegistrationSource.PUBLIC)
        )
    }

    @Test
    fun `durable account credential mutations require a recent passkey except constrained enrollment`() {
        val now = Instant.parse("2026-07-15T00:00:00Z")
        val lifetime = IdentityDuration.minutes(5)
        val mutations = setOf(
            IdentityAccountSecurityAction.ENROLL_PASSKEY,
            IdentityAccountSecurityAction.REVOKE_PASSKEY,
            IdentityAccountSecurityAction.REPLACE_RECOVERY_CODES
        )

        mutations.forEach { action ->
            assertNull(
                securityContext(SessionAuthenticationMethod.PASSKEY, now - 5.minutes)
                    .accountSecurityError(action, now, lifetime)
            )
            assertEquals(
                IdentityErrorCode.STEP_UP_REQUIRED,
                securityContext(SessionAuthenticationMethod.PASSKEY, now - 301.seconds)
                    .accountSecurityError(action, now, lifetime)
            )
            assertEquals(
                IdentityErrorCode.STEP_UP_REQUIRED,
                securityContext(SessionAuthenticationMethod.OIDC, now)
                    .accountSecurityError(action, now, lifetime)
            )
            assertEquals(
                IdentityErrorCode.STEP_UP_REQUIRED,
                securityContext(SessionAuthenticationMethod.SAML, now)
                    .accountSecurityError(action, now, lifetime)
            )
        }

        for (method in listOf(
            SessionAuthenticationMethod.BOOTSTRAP,
            SessionAuthenticationMethod.INVITATION,
            SessionAuthenticationMethod.RECOVERY_CODE,
            SessionAuthenticationMethod.ADMINISTRATIVE_RECOVERY
        )) {
            val context = securityContext(method, now)
            assertNull(
                context.accountSecurityError(
                    IdentityAccountSecurityAction.ENROLL_PASSKEY,
                    now,
                    lifetime
                ),
                "$method must retain its constrained first-passkey enrollment path"
            )
            assertEquals(
                IdentityErrorCode.STEP_UP_REQUIRED,
                context.accountSecurityError(IdentityAccountSecurityAction.REVOKE_PASSKEY, now, lifetime)
            )
            assertEquals(
                IdentityErrorCode.STEP_UP_REQUIRED,
                context.accountSecurityError(IdentityAccountSecurityAction.REPLACE_RECOVERY_CODES, now, lifetime)
            )
        }
    }

    private fun developmentConfig(policy: RegistrationPolicy): IdentityConfig = config(
        environment = IdentityEnvironment.DEVELOPMENT,
        policy = policy
    )

    private fun testConfig(policy: RegistrationPolicy): IdentityConfig = config(
        environment = IdentityEnvironment.TEST,
        policy = policy
    )

    private fun config(
        environment: IdentityEnvironment,
        policy: RegistrationPolicy
    ): IdentityConfig {
        fun secret(name: String) = SecretReference("test", name, "v1", environment)
        return IdentityConfig(
            environment = environment,
            publicBaseUrl = "http://localhost:8080",
            relyingParty = RelyingPartyConfig(
                "localhost",
                "Registration policy test",
                setOf("http://localhost:8080")
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
            registrationPolicy = policy,
            bootstrapSecret = secret("bootstrap")
        )
    }

    private fun securityContext(method: SessionAuthenticationMethod, authenticatedAt: Instant): IdentityContext {
        val userId = UserId("01900000-0000-7000-8000-000000000001")
        val sessionId = SessionId("01900000-0000-7000-8000-000000000002")
        val assurance = when (method) {
            SessionAuthenticationMethod.PASSKEY -> AuthenticationAssurance.PASSKEY
            SessionAuthenticationMethod.OIDC,
            SessionAuthenticationMethod.SAML -> AuthenticationAssurance.SESSION
            else -> AuthenticationAssurance.RECOVERY
        }
        val federated = method == SessionAuthenticationMethod.OIDC || method == SessionAuthenticationMethod.SAML
        val session = IdentitySession(
            id = sessionId,
            familyId = sessionId,
            userId = userId,
            tokenDigest = SecretDigest(DigestAlgorithm.HMAC_SHA256, "digest", "v1"),
            csrfDigest = SecretDigest(DigestAlgorithm.HMAC_SHA256, "csrf", "v1"),
            assurance = assurance,
            authenticationMethod = method,
            federationOrganizationId = if (federated) {
                OrganizationId("01900000-0000-7000-8000-000000000003")
            } else null,
            federationProviderKey = if (federated) {
                val prefix = if (method == SessionAuthenticationMethod.OIDC) "oidc." else "saml."
                prefix + Base64Url.encode(ByteArray(32))
            } else null,
            federationProviderSessionEpoch = if (federated) 0 else null,
            externalIdentityId = if (federated) {
                ExternalIdentityId("01900000-0000-7000-8000-000000000004")
            } else null,
            userSessionEpoch = 0,
            createdAt = authenticatedAt,
            authenticatedAt = authenticatedAt,
            lastUsedAt = authenticatedAt,
            idleExpiresAt = authenticatedAt + 1.hours,
            absoluteExpiresAt = authenticatedAt + 12.hours
        )
        return IdentityContext(
            principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = userId,
                displayName = "Identity user",
                assurance = assurance,
                authenticatedAt = authenticatedAt,
                sessionId = sessionId
            ),
            session = session
        )
    }
}
