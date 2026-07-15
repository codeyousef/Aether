package codes.yousef.aether.auth.firestore

import codes.yousef.aether.auth.IdentityClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Short-lived OAuth bearer token. Its value is deliberately absent from logs and serialization. */
class FirestoreAccessToken(
    token: String,
    val expiresAt: Instant
) {
    private val value = token

    init {
        require(token.isNotBlank() && token.length <= 16_384) { "Invalid Firestore OAuth access token" }
    }

    internal fun authorizationHeader(): String = "Bearer $value"
    override fun toString(): String = "FirestoreAccessToken(value=<redacted>, expiresAt=$expiresAt)"
}

/** Exchanges workload identity, metadata-server, or service-account credentials for an access token. */
fun interface FirestoreOAuthCredentialSource {
    suspend fun refreshAccessToken(): FirestoreAccessToken
}

fun interface FirestoreAccessTokenProvider {
    /** Returns null only for an explicitly configured local emulator. */
    suspend fun accessToken(): FirestoreAccessToken?

    /** Drops any cached bearer after Firestore rejects it. Stateless providers may do nothing. */
    suspend fun invalidate() = Unit
}

/** Coroutine-safe token cache which refreshes before expiry and never serializes credential state. */
class RefreshingFirestoreAccessTokenProvider(
    private val clock: IdentityClock,
    private val credentialSource: FirestoreOAuthCredentialSource,
    private val refreshSkew: Duration = DEFAULT_REFRESH_SKEW
) : FirestoreAccessTokenProvider {
    private val mutex = Mutex()
    private var cached: FirestoreAccessToken? = null

    init {
        require(refreshSkew >= Duration.ZERO && refreshSkew <= 10.minutesCompat()) {
            "Invalid Firestore OAuth refresh skew"
        }
    }

    override suspend fun accessToken(): FirestoreAccessToken = mutex.withLock {
        val now = clock.now()
        cached?.takeIf { now + refreshSkew < it.expiresAt } ?: credentialSource.refreshAccessToken().also {
            require(it.expiresAt > now + refreshSkew) { "Refreshed Firestore OAuth token expires too soon" }
            cached = it
        }
    }

    override suspend fun invalidate() = mutex.withLock { cached = null }

    companion object {
        val DEFAULT_REFRESH_SKEW: Duration = 60.seconds
    }
}

/** Explicit no-auth provider for the loopback Firestore emulator only. */
object FirestoreEmulatorAccessTokenProvider : FirestoreAccessTokenProvider {
    override suspend fun accessToken(): FirestoreAccessToken? = null
}

private fun Int.minutesCompat(): Duration = (this * 60).seconds
