package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AccountSecurityServiceTest {
    @Test
    fun realJcaProviderAndInMemoryStoreExerciseAccountLifecycles() = runBlocking {
        val runtime = jvmIdentityRuntime(secrets = IdentitySecretResolver { IdentitySecret.fromUtf8("test-only-account-security-key") })
        AccountSecurityConformanceSuite(InMemoryIdentityStore(), runtime, JvmPasswordProvider(), "memory").runAll()
    }

    @Test
    fun hashingRejectsOverloadInsteadOfQueueingAllRequests() = runBlocking {
        val provider = JvmPasswordProvider()
        val results = List(16) { async { runCatching { provider.hash("bounded-password-input-2522") } } }.awaitAll()
        assertTrue(results.count { it.isSuccess } <= 2)
        assertTrue(results.any { it.exceptionOrNull() is PasswordProviderOverloadedException })
    }

    @Test
    fun providerUsesRandomSaltsAndNeverTruncatesInputs() = runBlocking {
        val provider = JvmPasswordProvider()
        val secret = "PRIVATE_PASSWORD_CANARY_2522"
        val first = provider.hash(secret)
        val second = provider.hash(secret)
        assertTrue(first.salt != second.salt && first.hash != second.hash)
        assertTrue(provider.verify(secret, first))
        assertFalse(provider.verify(secret + "x", first))
        assertFalse(provider.verify("wrong-password", first))
        assertFalse(first.toString().contains(secret))
        assertFailsWith<IllegalArgumentException> { provider.hash("x".repeat(1025)) }
        assertFailsWith<IllegalArgumentException> { provider.hash("short") }
    }
}
