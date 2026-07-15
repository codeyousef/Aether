package codes.yousef.aether.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdentityConfigTest {
    @Test
    fun `safe defaults are environment isolated`() {
        val config = developmentConfig()

        assertEquals("aether_development", config.storageNamespace)
        assertEquals("__Host-aether_session", config.cookie.name)
        assertTrue(config.cookie.secure)
        assertTrue(config.cookie.httpOnly)
        assertEquals(RegistrationPolicy.INVITATION_ONLY, config.registrationPolicy)
        assertEquals(IdentityDuration.days(30), config.lifetimes.sessionAbsolute)
        assertEquals(IdentityDuration.minutes(5), config.lifetimes.challenge)
        assertEquals(TrustedProxyMode.DIRECT_ONLY, config.trustedProxy.mode)
        assertEquals(IdentityDuration.days(90), config.audit.retention)
        assertEquals(AuditUserAgentPolicy.OMIT, config.audit.userAgentPolicy)
    }

    @Test
    fun `production rejects loopback and plaintext origins`() {
        assertFailsWith<IllegalArgumentException> {
            IdentityConfig(
                environment = IdentityEnvironment.PRODUCTION,
                publicBaseUrl = "http://localhost:8080",
                relyingParty = RelyingPartyConfig(
                    id = "localhost",
                    name = "Aether",
                    allowedOrigins = setOf("http://localhost:8080")
                ),
                keys = keys(IdentityEnvironment.PRODUCTION)
            )
        }
    }

    @Test
    fun `plain HTTP is accepted only for exact loopback hosts`() {
        IdentityHttpRequest(IdentityHttpMethod.GET, "http://localhost:8080/health")
        IdentityHttpRequest(IdentityHttpMethod.GET, "http://127.0.0.1/health")
        IdentityHttpRequest(IdentityHttpMethod.GET, "http://[::1]:8080/health")

        listOf(
            "http://localhost.evil/health",
            "http://127.0.0.1.evil/health",
            "http://localhost@evil.test/health"
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) {
                IdentityHttpRequest(IdentityHttpMethod.GET, url)
            }
        }
    }

    @Test
    fun `origins are exact canonical origins`() {
        listOf(
            "https://login.example.com/",
            "https://*.example.com",
            "https://user@example.com",
            "ftp://login.example.com"
        ).forEach { origin ->
            assertFailsWith<IllegalArgumentException>(origin) {
                RelyingPartyConfig(
                    id = "example.com",
                    name = "Aether",
                    allowedOrigins = setOf(origin)
                )
            }
        }
    }

    @Test
    fun `secret references must match environment and be distinct`() {
        val developmentKeys = keys(IdentityEnvironment.DEVELOPMENT)
        assertFailsWith<IllegalArgumentException> {
            IdentityConfig(
                environment = IdentityEnvironment.PRODUCTION,
                publicBaseUrl = "https://login.example.com",
                relyingParty = RelyingPartyConfig(
                    id = "example.com",
                    name = "Aether",
                    allowedOrigins = setOf("https://login.example.com")
                ),
                keys = developmentKeys
            )
        }

        val duplicate = reference(IdentityEnvironment.DEVELOPMENT, "same")
        assertFailsWith<IllegalArgumentException> {
            developmentConfig(
                keys = IdentityKeyConfig(
                    sessionPepper = duplicate,
                    recoveryPepper = duplicate,
                    deviceTokenPepper = duplicate,
                    serviceCredentialPepper = duplicate,
                    auditPseudonymizationKey = duplicate,
                    encryptionKey = duplicate,
                    signingKey = duplicate
                )
            )
        }
    }

    @Test
    fun `session and challenge lifetime bounds are enforced`() {
        assertFailsWith<IllegalArgumentException> {
            IdentityLifetimes(
                sessionAbsolute = IdentityDuration.hours(1),
                sessionIdle = IdentityDuration.hours(2)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            IdentityLifetimes(challenge = IdentityDuration.seconds(29))
        }
        assertFailsWith<IllegalArgumentException> {
            IdentityDuration.seconds(0)
        }
    }

    @Test
    fun `audit policy supports only omission or keyed pseudonymization`() {
        val configured = developmentConfig().copy(
            audit = IdentityAuditConfig(
                retention = IdentityDuration.days(30),
                userAgentPolicy = AuditUserAgentPolicy.PSEUDONYMIZE
            )
        )

        assertEquals(IdentityDuration.days(30), configured.audit.retention)
        assertEquals(AuditUserAgentPolicy.PSEUDONYMIZE, configured.audit.userAgentPolicy)
        assertFailsWith<IllegalArgumentException> {
            IdentityAuditConfig(retention = IdentityDuration.days(3_651))
        }
    }

    @Test
    fun `trusted forwarded headers require explicit CIDRs`() {
        assertFailsWith<IllegalArgumentException> {
            TrustedProxyConfig(mode = TrustedProxyMode.TRUSTED_CIDRS)
        }
        val trusted = TrustedProxyConfig(
            mode = TrustedProxyMode.TRUSTED_CIDRS,
            trustedCidrs = setOf("10.0.0.0/8", "2001:db8::/32")
        )
        assertEquals(2, trusted.trustedCidrs.size)
    }

    @Test
    fun `pending production bootstrap requires a secret and retired production forbids one`() {
        assertFailsWith<IllegalArgumentException> {
            IdentityConfig(
                environment = IdentityEnvironment.PRODUCTION,
                publicBaseUrl = "https://login.example.com",
                relyingParty = RelyingPartyConfig(
                    id = "example.com",
                    name = "Aether",
                    allowedOrigins = setOf("https://login.example.com")
                ),
                keys = keys(IdentityEnvironment.PRODUCTION)
            )
        }

        val retired = IdentityConfig(
            environment = IdentityEnvironment.PRODUCTION,
            publicBaseUrl = "https://login.example.com",
            relyingParty = RelyingPartyConfig(
                id = "example.com",
                name = "Aether",
                allowedOrigins = setOf("https://login.example.com")
            ),
            keys = keys(IdentityEnvironment.PRODUCTION),
            bootstrapLifecycle = IdentityBootstrapLifecycle.RETIRED
        )
        assertEquals(IdentityBootstrapLifecycle.RETIRED, retired.bootstrapLifecycle)
        assertEquals("\"retired\"", Json.encodeToString(retired.bootstrapLifecycle))

        assertFailsWith<IllegalArgumentException> {
            retired.copy(
                bootstrapSecret = reference(IdentityEnvironment.PRODUCTION, "retired-bootstrap")
            )
        }

        assertFailsWith<IllegalArgumentException> {
            IdentityConfig(
                environment = IdentityEnvironment.DEVELOPMENT,
                publicBaseUrl = "http://127.0.0.1:8080",
                relyingParty = RelyingPartyConfig(
                    id = "localhost",
                    name = "Aether",
                    allowedOrigins = setOf("http://localhost:8080")
                ),
                keys = keys(IdentityEnvironment.DEVELOPMENT)
            )
        }
    }

    private fun developmentConfig(
        keys: IdentityKeyConfig = keys(IdentityEnvironment.DEVELOPMENT)
    ): IdentityConfig = IdentityConfig(
        environment = IdentityEnvironment.DEVELOPMENT,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig(
            id = "localhost",
            name = "Aether development",
            allowedOrigins = setOf("http://localhost:8080")
        ),
        keys = keys
    )

    private fun keys(environment: IdentityEnvironment): IdentityKeyConfig = IdentityKeyConfig(
        sessionPepper = reference(environment, "session"),
        recoveryPepper = reference(environment, "recovery"),
        deviceTokenPepper = reference(environment, "device"),
        serviceCredentialPepper = reference(environment, "service"),
        auditPseudonymizationKey = reference(environment, "audit"),
        encryptionKey = reference(environment, "encryption"),
        signingKey = reference(environment, "signing")
    )

    private fun reference(environment: IdentityEnvironment, name: String): SecretReference =
        SecretReference(
            provider = "test",
            name = name,
            version = "v1",
            environment = environment
        )
}
