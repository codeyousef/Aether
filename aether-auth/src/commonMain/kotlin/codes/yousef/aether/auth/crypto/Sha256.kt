package codes.yousef.aether.auth.crypto

/**
 * Pure Kotlin implementation of SHA-256 hash algorithm.
 * Used for HMAC-SHA256 in JWT signing on platforms without native crypto.
 */
object Sha256 {
    // SHA-256 round constants (first 32 bits of fractional parts of cube roots of first 64 primes)
    private val K: IntArray = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L,
        0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L,
        0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL,
        0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L,
        0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L,
        0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L,
        0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L,
        0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L,
        0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L
    ).map { it.toInt() }.toIntArray()

    // Initial hash values (first 32 bits of fractional parts of square roots of first 8 primes)
    private val H0: IntArray = longArrayOf(
        0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL,
        0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L
    ).map { it.toInt() }.toIntArray()

    /**
     * Compute SHA-256 hash of input data.
     */
    fun hash(data: ByteArray): ByteArray {
        val padded = pad(data)
        val h = H0.copyOf()
        val w = IntArray(64)

        for (i in padded.indices step 64) {
            // Prepare message schedule
            for (j in 0 until 16) {
                w[j] = ((padded[i + j * 4].toInt() and 0xFF) shl 24) or
                        ((padded[i + j * 4 + 1].toInt() and 0xFF) shl 16) or
                        ((padded[i + j * 4 + 2].toInt() and 0xFF) shl 8) or
                        (padded[i + j * 4 + 3].toInt() and 0xFF)
            }
            for (j in 16 until 64) {
                val s0 = rightRotate(w[j - 15], 7) xor rightRotate(w[j - 15], 18) xor (w[j - 15] ushr 3)
                val s1 = rightRotate(w[j - 2], 17) xor rightRotate(w[j - 2], 19) xor (w[j - 2] ushr 10)
                w[j] = w[j - 16] + s0 + w[j - 7] + s1
            }

            // Initialize working variables
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]

            // Main loop
            for (j in 0 until 64) {
                val s1 = rightRotate(e, 6) xor rightRotate(e, 11) xor rightRotate(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[j] + w[j]
                val s0 = rightRotate(a, 2) xor rightRotate(a, 13) xor rightRotate(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            // Add compressed chunk to hash
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }

        // Produce final hash
        val result = ByteArray(32)
        for (i in 0 until 8) {
            result[i * 4] = (h[i] ushr 24).toByte()
            result[i * 4 + 1] = (h[i] ushr 16).toByte()
            result[i * 4 + 2] = (h[i] ushr 8).toByte()
            result[i * 4 + 3] = h[i].toByte()
        }
        return result
    }

    private fun pad(data: ByteArray): ByteArray {
        val originalLength = data.size
        val bitLength = originalLength.toLong() * 8

        // Calculate padding length: message + 1 byte (0x80) + padding + 8 bytes (length)
        // Total must be multiple of 64 bytes (512 bits)
        var paddingLength = 64 - ((originalLength + 9) % 64)
        if (paddingLength == 64) paddingLength = 0

        val padded = ByteArray(originalLength + 1 + paddingLength + 8)
        data.copyInto(padded)
        padded[originalLength] = 0x80.toByte()

        // Append original length in bits as 64-bit big-endian
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = (bitLength ushr (56 - i * 8)).toByte()
        }

        return padded
    }

    private fun rightRotate(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))
}

/**
 * HMAC-SHA256 implementation.
 */
object HmacSha256 {
    private const val BLOCK_SIZE = 64

    /**
     * Compute HMAC-SHA256 of data with the given key.
     */
    fun compute(key: ByteArray, data: ByteArray): ByteArray {
        // If key is longer than block size, hash it
        val keyBytes = if (key.size > BLOCK_SIZE) {
            Sha256.hash(key)
        } else {
            key
        }

        // Pad key to block size
        val paddedKey = ByteArray(BLOCK_SIZE)
        keyBytes.copyInto(paddedKey)

        // Create inner and outer padded keys
        val innerPad = ByteArray(BLOCK_SIZE) { (paddedKey[it].toInt() xor 0x36).toByte() }
        val outerPad = ByteArray(BLOCK_SIZE) { (paddedKey[it].toInt() xor 0x5c).toByte() }

        // Inner hash: SHA256(innerPad || data)
        val innerData = innerPad + data
        val innerHash = Sha256.hash(innerData)

        // Outer hash: SHA256(outerPad || innerHash)
        val outerData = outerPad + innerHash
        return Sha256.hash(outerData)
    }

    /**
     * Compute HMAC-SHA256 with string key.
     */
    @Suppress("unused") // API for convenience
    fun compute(key: String, data: ByteArray): ByteArray = compute(key.encodeToByteArray(), data)

    /**
     * Compute HMAC-SHA256 with string key and data.
     */
    fun compute(key: String, data: String): ByteArray = compute(key.encodeToByteArray(), data.encodeToByteArray())
}
