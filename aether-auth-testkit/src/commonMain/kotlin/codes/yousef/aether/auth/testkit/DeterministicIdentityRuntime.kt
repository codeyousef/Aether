package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.Es256Signature
import codes.yousef.aether.auth.IdentityClock
import codes.yousef.aether.auth.IdentityCrypto
import codes.yousef.aether.auth.IdentityCryptoCapability
import codes.yousef.aether.auth.IdentityHttpClient
import codes.yousef.aether.auth.IdentityHttpRequest
import codes.yousef.aether.auth.IdentityHttpResponse
import codes.yousef.aether.auth.IdentityRuntime
import codes.yousef.aether.auth.IdentitySecret
import codes.yousef.aether.auth.IdentitySecretResolver
import codes.yousef.aether.auth.IdentitySecureRandom
import codes.yousef.aether.auth.P256PublicKey
import codes.yousef.aether.auth.RsaPublicKey
import codes.yousef.aether.auth.RsaSha256Signature
import codes.yousef.aether.auth.SecretReference
import kotlinx.atomicfu.atomic
import kotlin.time.Instant

/** Mutable deterministic wall clock for common tests. */
class DeterministicIdentityClock(initial: Instant = IdentityFixtures.baseInstant) : IdentityClock {
    private val epochMilliseconds = atomic(initial.toEpochMilliseconds())

    override fun now(): Instant = Instant.fromEpochMilliseconds(epochMilliseconds.value)

    fun set(instant: Instant) {
        epochMilliseconds.value = instant.toEpochMilliseconds()
    }

    fun advanceMilliseconds(milliseconds: Long): Instant {
        require(milliseconds >= 0) { "Clock cannot move backwards" }
        val updated = epochMilliseconds.addAndGet(milliseconds)
        return Instant.fromEpochMilliseconds(updated)
    }
}

/**
 * Deterministic byte source. It is concurrency-safe but deliberately not cryptographically secure;
 * it must only be used through the non-published testkit.
 */
class DeterministicIdentitySecureRandom(seed: Long = 0x5A17_4EEDL) : IdentitySecureRandom {
    private val cursor = atomic(0L)
    private val seedValue = seed

    override fun nextBytes(size: Int): ByteArray {
        require(size >= 0) { "Random byte count must not be negative" }
        val start = cursor.getAndAdd(size.toLong())
        return ByteArray(size) { offset -> byteAt(start + offset) }
    }

    fun bytesIssued(): Long = cursor.value

    private fun byteAt(position: Long): Byte {
        val wordIndex = position ushr 3
        val byteIndex = (position and 7).toInt()
        val mixed = mix64(seedValue + wordIndex * -7046029254386353131L)
        return (mixed ushr (byteIndex * 8)).toByte()
    }

    private fun mix64(value: Long): Long {
        var mixed = value
        mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
        mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
        return mixed xor (mixed ushr 31)
    }
}

/**
 * Portable deterministic crypto test double. Hashes are stable 32-byte fixtures, not real
 * cryptographic digests. ES256 verification returns [verifyEs256Result].
 */
class DeterministicIdentityCrypto(
    private val verifyEs256Result: Boolean = true,
    private val verifyRsaSha256Result: Boolean = true,
    private val validateP256Result: Boolean = true
) : IdentityCrypto {
    override val capabilities: Set<IdentityCryptoCapability> = IdentityCryptoCapability.entries.toSet()

    override suspend fun sha256(input: ByteArray): ByteArray = deterministicDigest(input)

    override suspend fun hmacSha256(key: IdentitySecret, input: ByteArray): ByteArray =
        key.useBytes { keyBytes ->
            deterministicDigest(keyBytes + byteArrayOf(0x5c) + input)
        }

    override suspend fun validateP256PublicKey(publicKey: P256PublicKey): Boolean {
        publicKey.copyBytes()
        return validateP256Result
    }

    override suspend fun verifyEs256(
        publicKey: P256PublicKey,
        signedData: ByteArray,
        signature: Es256Signature
    ): Boolean {
        // Read defensive copies so tests also exercise the secret-bearing wrappers.
        publicKey.copyBytes()
        signature.copyBytes()
        signedData.copyOf()
        return verifyEs256Result
    }

    override suspend fun verifyRsaSha256(
        publicKey: RsaPublicKey,
        signedData: ByteArray,
        signature: RsaSha256Signature
    ): Boolean {
        publicKey.copyBytes()
        signature.copyBytes()
        signedData.copyOf()
        return verifyRsaSha256Result
    }

    override suspend fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        var difference = left.size xor right.size
        val length = maxOf(left.size, right.size)
        for (index in 0 until length) {
            val leftByte = if (index < left.size) left[index].toInt() else 0
            val rightByte = if (index < right.size) right[index].toInt() else 0
            difference = difference or (leftByte xor rightByte)
        }
        return difference == 0
    }

    private fun deterministicDigest(input: ByteArray): ByteArray {
        val state = IntArray(8) { index -> 0x6a09e667 + index * 0x11111111 }
        input.forEachIndexed { index, byte ->
            val lane = index and 7
            val value = byte.toInt() and 0xff
            state[lane] = (state[lane] xor (value + index * 257)).rotateLeft((index % 31) + 1)
            state[(lane + 3) and 7] += state[lane] xor 0x5bd1e995
        }
        return ByteArray(32) { index ->
            val word = state[index ushr 2]
            (word ushr ((index and 3) * 8)).toByte()
        }
    }
}

/** FIFO HTTP client with defensive request recording. */
class DeterministicIdentityHttpClient(
    responses: List<IdentityHttpResponse> = emptyList(),
    private val providerReady: Boolean = true
) : IdentityHttpClient {
    private val lock = CoroutineSafeLock()
    private val queuedResponses = responses.toMutableList()
    private val requests = mutableListOf<IdentityHttpRequest>()

    override suspend fun providerSelfTest(): Boolean = providerReady

    override suspend fun execute(request: IdentityHttpRequest): IdentityHttpResponse = lock.withLock {
        requests += request
        check(queuedResponses.isNotEmpty()) { "No deterministic HTTP response is queued" }
        queuedResponses.removeAt(0)
    }

    suspend fun enqueue(response: IdentityHttpResponse) {
        lock.withLock { queuedResponses += response }
    }

    suspend fun recordedRequests(): List<IdentityHttpRequest> = lock.withLock { requests.toList() }

    suspend fun remainingResponses(): Int = lock.withLock { queuedResponses.size }
}

/** Map-backed resolver that never returns the originally registered byte array. */
class DeterministicIdentitySecretResolver(
    secrets: Map<SecretReference, ByteArray> = emptyMap()
) : IdentitySecretResolver {
    private val lock = CoroutineSafeLock()
    private val values = secrets.mapValuesTo(mutableMapOf()) { (_, value) -> value.copyOf() }

    override suspend fun resolve(reference: SecretReference): IdentitySecret = lock.withLock {
        val bytes = values[reference] ?: error("No deterministic secret registered for $reference")
        IdentitySecret.fromBytes(bytes.copyOf())
    }

    suspend fun register(reference: SecretReference, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "Secret must not be empty" }
        lock.withLock { values[reference] = bytes.copyOf() }
    }
}

/** Ready-to-use deterministic runtime with inspectable components. */
class DeterministicIdentityRuntime(
    val deterministicClock: DeterministicIdentityClock = DeterministicIdentityClock(),
    val deterministicRandom: DeterministicIdentitySecureRandom = DeterministicIdentitySecureRandom(),
    val deterministicCrypto: DeterministicIdentityCrypto = DeterministicIdentityCrypto(),
    val deterministicHttp: DeterministicIdentityHttpClient = DeterministicIdentityHttpClient(),
    val deterministicSecrets: DeterministicIdentitySecretResolver = DeterministicIdentitySecretResolver()
) {
    val runtime: IdentityRuntime = IdentityRuntime(
        clock = deterministicClock,
        secureRandom = deterministicRandom,
        crypto = deterministicCrypto,
        http = deterministicHttp,
        secrets = deterministicSecrets
    )
}
