package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityBootstrapServiceTest {
    @Test
    fun `bootstrap secret establishes the first owner exactly once without persistence`() = runTest {
        val fixture = BootstrapFixture()

        val bootstrapped = fixture.service.bootstrap(
            fixture.secret,
            "Platform Owner",
            EmailAddress("owner@example.test"),
            "Aether Platform",
            "aether-platform"
        ).expectBootstrapSuccess()

        val snapshot = fixture.store.snapshot()
        assertTrue(snapshot.bootstrapCompleted)
        assertEquals(bootstrapped.user, snapshot.users.single())
        assertEquals(bootstrapped.organization, snapshot.organizations.single())
        assertEquals(OrganizationRole.OWNER, snapshot.memberships.single().role)
        assertEquals(bootstrapped.issuedEnrollmentSession.session, snapshot.sessions.single())
        assertEquals(AuthenticationAssurance.RECOVERY, snapshot.sessions.single().assurance)
        assertEquals(SessionAuthenticationMethod.BOOTSTRAP, snapshot.sessions.single().authenticationMethod)
        assertFalse(snapshot.toString().contains(bootstrapped.issuedEnrollmentSession.cookieValue()))
        assertEquals(AuditAction.IDENTITY_BOOTSTRAPPED, snapshot.auditEvents.single().action)
        assertFalse(snapshot.toString().contains(fixture.secret))
        assertEquals(
            IdentityErrorCode.CONFLICT,
            fixture.service.bootstrap(
                fixture.secret,
                "Second Owner",
                EmailAddress("second@example.test"),
                "Second Platform",
                "second-platform"
            ).expectBootstrapFailure()
        )
    }

    @Test
    fun `invalid bootstrap secret creates no identity state`() = runTest {
        val fixture = BootstrapFixture()

        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            fixture.service.bootstrap(
                "this-is-not-the-configured-secret",
                "Platform Owner",
                EmailAddress("owner@example.test"),
                "Aether Platform",
                "aether-platform"
            ).expectBootstrapFailure()
        )
        val snapshot = fixture.store.snapshot()
        assertFalse(snapshot.bootstrapCompleted)
        assertTrue(snapshot.users.isEmpty())
        assertTrue(snapshot.auditEvents.isEmpty())
    }

    @Test
    fun `concurrent bootstrap attempts have one winner`() = runTest {
        val fixture = BootstrapFixture()

        val results = List(2) { index ->
            async {
                fixture.service.bootstrap(
                    fixture.secret,
                    "Owner $index",
                    EmailAddress("owner$index@example.test"),
                    "Platform $index",
                    "platform-$index"
                )
            }
        }.awaitAll()

        assertEquals(1, results.count { it is IdentityOperationResult.Success })
        assertEquals(1, results.count {
            it is IdentityOperationResult.Failure && it.code == IdentityErrorCode.CONFLICT
        })
        assertEquals(1, fixture.store.snapshot().users.size)
        assertEquals(1, fixture.store.snapshot().sessions.size)
    }

    @Test
    fun `bootstrap is disabled when no deployment secret is configured`() = runTest {
        val fixture = BootstrapFixture(configureSecret = false)

        assertFalse(fixture.service.enabled)
        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            fixture.service.bootstrap(
                fixture.secret,
                "Platform Owner",
                EmailAddress("owner@example.test"),
                "Aether Platform",
                "aether-platform"
            ).expectBootstrapFailure()
        )
    }

    @Test
    fun `single use bootstrap remains an explicit escape hatch under every registration policy`() = runTest {
        RegistrationPolicy.entries.forEach { policy ->
            val fixture = BootstrapFixture(registrationPolicy = policy)
            fixture.service.bootstrap(
                fixture.secret,
                "Platform Owner",
                EmailAddress("owner@example.test"),
                "Aether Platform",
                "aether-platform"
            ).expectBootstrapSuccess()
            assertTrue(fixture.store.snapshot().bootstrapCompleted, "Bootstrap must remain available under $policy")
        }
    }
}

private class BootstrapFixture(
    configureSecret: Boolean = true,
    registrationPolicy: RegistrationPolicy = RegistrationPolicy.INVITATION_ONLY
) {
    val secret = "test-bootstrap-secret-with-256-bits-of-entropy"
    private val environment = if (registrationPolicy == RegistrationPolicy.OPEN) {
        IdentityEnvironment.DEVELOPMENT
    } else {
        IdentityEnvironment.TEST
    }
    private val reference = SecretReference("test", "bootstrap", "v1", environment)
    private val base = bootstrapConfig(environment, registrationPolicy)
    val config = base.copy(bootstrapSecret = reference.takeIf { configureSecret })
    val runtime = DeterministicIdentityRuntime(
        deterministicSecrets = DeterministicIdentitySecretResolver(
            mapOf(
                reference to secret.encodeToByteArray(),
                base.keys.sessionPepper to ByteArray(32) { (it + 1).toByte() }
            )
        )
    )
    val store = InMemoryIdentityStore()
    val service = IdentityBootstrapService(store, runtime.runtime, config)
}

private fun bootstrapConfig(
    environment: IdentityEnvironment = IdentityEnvironment.TEST,
    registrationPolicy: RegistrationPolicy = RegistrationPolicy.INVITATION_ONLY
): IdentityConfig {
    fun secret(name: String) = SecretReference("test", name, "v1", environment)
    return IdentityConfig(
        environment = environment,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig("localhost", "Aether Bootstrap Test", setOf("http://localhost:8080")),
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

private fun <T> IdentityOperationResult<T>.expectBootstrapSuccess(): T = when (this) {
    is IdentityOperationResult.Success -> value
    is IdentityOperationResult.Failure -> error("Expected bootstrap success, got $code")
}

private fun IdentityOperationResult<*>.expectBootstrapFailure(): IdentityErrorCode = when (this) {
    is IdentityOperationResult.Success -> error("Expected bootstrap failure")
    is IdentityOperationResult.Failure -> code
}
