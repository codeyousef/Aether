package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.IdentityEnvironment
import codes.yousef.aether.auth.SecretReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PostgresqlIdentityConfigTest {
    @Test
    fun acceptsSecureAndExactLoopbackPostgrestUrls() {
        PostgresqlIdentityConfig(
            environment = IdentityEnvironment.TEST,
            namespace = "aether_test",
            postgrestBaseUrl = "https://identity-db.example.test/api/"
        )
        PostgresqlIdentityConfig(
            environment = IdentityEnvironment.TEST,
            namespace = "aether_test",
            postgrestBaseUrl = "http://localhost:3000"
        )
        PostgresqlIdentityConfig(
            environment = IdentityEnvironment.TEST,
            namespace = "aether_test",
            postgrestBaseUrl = "http://[::1]:3000"
        )
    }

    @Test
    fun rejectsLookalikeLoopbackAndEnvironmentMismatch() {
        assertFailsWith<IllegalArgumentException> {
            PostgresqlIdentityConfig(
                environment = IdentityEnvironment.TEST,
                namespace = "aether_test",
                postgrestBaseUrl = "http://localhost.example.test"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PostgresqlIdentityConfig(
                environment = IdentityEnvironment.PRODUCTION,
                namespace = "aether_test"
            )
        }
    }

    @Test
    fun productionPostgrestRequiresAnEnvironmentBoundSecretAndRedactsIt() {
        val reference = SecretReference(
            provider = "vault",
            name = "postgrest-production-token",
            version = "7",
            environment = IdentityEnvironment.PRODUCTION
        )
        val config = PostgresqlIdentityConfig(
            environment = IdentityEnvironment.PRODUCTION,
            namespace = "aether_production",
            postgrestBaseUrl = "https://identity-db.example.test",
            postgrestAuthorizationSecret = reference
        )

        assertContains(config.toString(), "<redacted>")
        assertFalse(config.toString().contains(reference.name))
    }
}
