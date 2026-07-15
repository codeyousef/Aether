package codes.yousef.aether.auth.webauthn

import codes.yousef.aether.auth.Base64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAuthnProtocolDecoderTest {
    private val decoder = WebAuthnProtocolDecoder()

    @Test
    fun `client data requires canonical base64url exact type and unique JSON keys`() {
        val challenge = ByteArray(32) { it.toByte() }
        val json = """{"type":"webauthn.get","challenge":"${Base64Url.encode(challenge)}","origin":"https://login.example.com","crossOrigin":false}"""
        val decoded = decoder.decodeClientData(Base64Url.encode(json.encodeToByteArray()), WebAuthnCeremonyType.AUTHENTICATION)

        assertContentEquals(challenge, decoded.challenge)
        assertEquals("https://login.example.com", decoded.origin)
        assertFalse(decoded.crossOrigin)

        val duplicate = """{"type":"webauthn.get","type":"webauthn.create","challenge":"AQ","origin":"https://login.example.com"}"""
        assertFailsWith<WebAuthnDecodingException> {
            decoder.decodeClientData(Base64Url.encode(duplicate.encodeToByteArray()), WebAuthnCeremonyType.AUTHENTICATION)
        }
        assertFailsWith<WebAuthnDecodingException> {
            decoder.decodeClientData(Base64Url.encode(json.encodeToByteArray()) + "=", WebAuthnCeremonyType.AUTHENTICATION)
        }
    }

    @Test
    fun `none attestation extracts bounded discoverable ES256 credential`() {
        val credentialId = ByteArray(32) { (it + 1).toByte() }
        val x = ByteArray(32) { (it + 2).toByte() }
        val y = ByteArray(32) { (it + 3).toByte() }
        val cose = map(
            integer(1) to integer(2),
            integer(3) to integer(-7),
            integer(-1) to integer(1),
            integer(-2) to bytes(x),
            integer(-3) to bytes(y)
        )
        val authData = ByteArray(32) { 9 } +
            byteArrayOf(0x45, 0, 0, 0, 0) +
            ByteArray(16) +
            byteArrayOf(0, credentialId.size.toByte()) +
            credentialId + cose
        val attestation = map(
            text("fmt") to text("none"),
            text("authData") to bytes(authData),
            text("attStmt") to map()
        )

        val parsed = decoder.decodeNoneAttestation(Base64Url.encode(attestation)).authenticatorData

        assertTrue(parsed.userPresent)
        assertTrue(parsed.userVerified)
        assertEquals(0, parsed.signCount)
        assertEquals(Base64Url.encode(credentialId), parsed.attestedCredentialData?.credentialId?.encoded)
        assertContentEquals(byteArrayOf(0x04) + x + y, parsed.attestedCredentialData?.p256PublicKey?.copyBytes())
    }

    @Test
    fun `authenticator flags and trailing bytes fail closed`() {
        val invalidBackup = ByteArray(32) + byteArrayOf(0x11, 0, 0, 0, 0)
        assertFailsWith<WebAuthnDecodingException> { decoder.decodeAuthenticatorData(invalidBackup) }

        val reserved = ByteArray(32) + byteArrayOf(0x03, 0, 0, 0, 0)
        assertFailsWith<WebAuthnDecodingException> { decoder.decodeAuthenticatorData(reserved) }

        val trailing = ByteArray(32) + byteArrayOf(0x05, 0, 0, 0, 1, 0)
        assertFailsWith<WebAuthnDecodingException> { decoder.decodeAuthenticatorData(trailing) }
    }

    @Test
    fun `CBOR rejects indefinite lengths noncanonical integers and duplicate keys`() {
        val cbor = BoundedCborDecoder()
        assertFailsWith<CborDecodingException> { cbor.decodeExactly(byteArrayOf(0x9f.toByte(), 0xff.toByte())) }
        assertFailsWith<CborDecodingException> { cbor.decodeExactly(byteArrayOf(0x18, 0x01)) }
        assertFailsWith<CborDecodingException> {
            cbor.decodeExactly(byteArrayOf(0xa2.toByte(), 0x01, 0x01, 0x01, 0x02))
        }
    }

    @Test
    fun `ES256 DER parser rejects negative overlong and trailing encodings`() {
        val r = ByteArray(32).also { it[31] = 1 }
        val s = ByteArray(32).also { it[31] = 2 }
        val der = byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02)
        assertContentEquals(r + s, decoder.decodeDerEs256Signature(Base64Url.encode(der)).copyBytes())

        val trailing = der + 0
        assertFailsWith<WebAuthnDecodingException> { decoder.decodeDerEs256Signature(Base64Url.encode(trailing)) }
        val negative = der.copyOf().also { it[4] = 0x80.toByte() }
        assertFailsWith<WebAuthnDecodingException> { decoder.decodeDerEs256Signature(Base64Url.encode(negative)) }
    }

    private fun integer(value: Long): ByteArray = when {
        value >= 0 && value <= 23 -> byteArrayOf(value.toByte())
        value < 0 && -1 - value <= 23 -> byteArrayOf((0x20 + (-1 - value)).toByte())
        else -> error("test helper only supports small integers")
    }

    private fun bytes(value: ByteArray): ByteArray = length(2, value.size) + value
    private fun text(value: String): ByteArray = value.encodeToByteArray().let { length(3, it.size) + it }
    private fun map(vararg entries: Pair<ByteArray, ByteArray>): ByteArray =
        length(5, entries.size) + entries.flatMap { (key, value) -> (key + value).asIterable() }.toByteArray()

    private fun length(major: Int, size: Int): ByteArray = when {
        size <= 23 -> byteArrayOf(((major shl 5) or size).toByte())
        size <= 0xff -> byteArrayOf(((major shl 5) or 24).toByte(), size.toByte())
        else -> byteArrayOf(((major shl 5) or 25).toByte(), (size ushr 8).toByte(), size.toByte())
    }
}
