package codes.yousef.aether.auth

import kotlin.time.Instant

enum class WasmWasiSigningAlgorithm {
    ES256,
    RSA_SHA256
}

/**
 * Required trusted-host boundary for wasmWasi identity authorities.
 *
 * Aether deliberately has no pure-Kotlin fallback for ES256. Deployments must
 * provide the matching `aether:identity-crypto` host capability backed by the
 * supported OpenSSL runtime bundle. Every returned byte array is copied and
 * validated before it enters identity code.
 */
interface WasmWasiIdentityHost {
    fun nowEpochMilliseconds(): Long
    fun secureRandom(size: Int): ByteArray
    suspend fun sha256(input: ByteArray): ByteArray
    suspend fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray
    suspend fun validateP256PublicKey(publicKey: ByteArray): Boolean
    suspend fun verifyEs256(publicKey: ByteArray, signedData: ByteArray, signature: ByteArray): Boolean
    suspend fun verifyRsaSha256(publicKeyDer: ByteArray, signedData: ByteArray, signature: ByteArray): Boolean
    suspend fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean
    suspend fun sign(
        algorithm: WasmWasiSigningAlgorithm,
        keyHandle: String,
        signedData: ByteArray
    ): ByteArray
    suspend fun selfTest(): Boolean
    suspend fun httpSelfTest(): Boolean
    suspend fun executeHttp(request: IdentityHttpRequest): IdentityHttpResponse
}

class WasmWasiIdentityClock(
    private val host: WasmWasiIdentityHost
) : IdentityClock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(host.nowEpochMilliseconds())
}

class WasmWasiIdentitySecureRandom(
    private val host: WasmWasiIdentityHost
) : IdentitySecureRandom {
    override fun nextBytes(size: Int): ByteArray {
        require(size in 1..1_048_576) { "Random byte request must be between 1 byte and 1 MiB" }
        return host.secureRandom(size).also { bytes ->
            require(bytes.size == size) { "WASI crypto host returned an unexpected random byte count" }
        }.copyOf()
    }
}

class WasmWasiIdentityCrypto(
    private val host: WasmWasiIdentityHost
) : IdentityCrypto {
    override val capabilities: Set<IdentityCryptoCapability> = IdentityCryptoCapability.entries.toSet()

    override suspend fun sha256(input: ByteArray): ByteArray =
        host.sha256(input.copyOf()).validatedDigest()

    override suspend fun hmacSha256(key: IdentitySecret, input: ByteArray): ByteArray =
        key.useBytes { keyBytes -> host.hmacSha256(keyBytes, input.copyOf()).validatedDigest() }

    override suspend fun validateP256PublicKey(publicKey: P256PublicKey): Boolean =
        host.validateP256PublicKey(publicKey.copyBytes())

    override suspend fun verifyEs256(
        publicKey: P256PublicKey,
        signedData: ByteArray,
        signature: Es256Signature
    ): Boolean = host.verifyEs256(
        publicKey.copyBytes(),
        signedData.copyOf(),
        signature.copyBytes()
    )

    override suspend fun verifyRsaSha256(
        publicKey: RsaPublicKey,
        signedData: ByteArray,
        signature: RsaSha256Signature
    ): Boolean = host.verifyRsaSha256(
        publicKey.copyBytes(),
        signedData.copyOf(),
        signature.copyBytes()
    )

    override suspend fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
        host.constantTimeEquals(left.copyOf(), right.copyOf())

    override suspend fun providerSelfTest(): Boolean = host.selfTest()

    private fun ByteArray.validatedDigest(): ByteArray {
        require(size == 32) { "WASI crypto host returned an invalid SHA-256 digest" }
        return copyOf()
    }
}

class WasmWasiIdentityHttpClient(
    private val host: WasmWasiIdentityHost
) : IdentityHttpClient {
    override suspend fun providerSelfTest(): Boolean = host.httpSelfTest()

    override suspend fun execute(request: IdentityHttpRequest): IdentityHttpResponse =
        host.executeHttp(request)
}

fun wasmWasiIdentityRuntime(
    host: WasmWasiIdentityHost,
    secrets: IdentitySecretResolver
): IdentityRuntime = IdentityRuntime(
    clock = WasmWasiIdentityClock(host),
    secureRandom = WasmWasiIdentitySecureRandom(host),
    crypto = WasmWasiIdentityCrypto(host),
    http = WasmWasiIdentityHttpClient(host),
    secrets = secrets
)
