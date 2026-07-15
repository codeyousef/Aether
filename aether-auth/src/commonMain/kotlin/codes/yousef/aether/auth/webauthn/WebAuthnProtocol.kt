package codes.yousef.aether.auth.webauthn

import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.CosePublicKey
import codes.yousef.aether.auth.Es256Signature
import codes.yousef.aether.auth.P256PublicKey
import codes.yousef.aether.auth.WebAuthnCredentialId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class WebAuthnCeremonyType(val wireValue: String) {
    REGISTRATION("webauthn.create"),
    AUTHENTICATION("webauthn.get")
}

data class CollectedClientData(
    val type: String,
    val challenge: ByteArray,
    val origin: String,
    val crossOrigin: Boolean,
    val rawJson: ByteArray
) {
    override fun toString(): String =
        "CollectedClientData(type=$type, challenge=<redacted>, origin=$origin, crossOrigin=$crossOrigin)"
}

data class AuthenticatorData(
    val rpIdHash: ByteArray,
    val flags: Int,
    val signCount: Long,
    val attestedCredentialData: AttestedCredentialData?,
    val extensions: CborValue?,
    val rawBytes: ByteArray
) {
    val userPresent: Boolean get() = flags and FLAG_USER_PRESENT != 0
    val userVerified: Boolean get() = flags and FLAG_USER_VERIFIED != 0
    val backupEligible: Boolean get() = flags and FLAG_BACKUP_ELIGIBLE != 0
    val backedUp: Boolean get() = flags and FLAG_BACKUP_STATE != 0

    companion object {
        const val FLAG_USER_PRESENT = 0x01
        const val FLAG_USER_VERIFIED = 0x04
        const val FLAG_BACKUP_ELIGIBLE = 0x08
        const val FLAG_BACKUP_STATE = 0x10
        const val FLAG_ATTESTED_CREDENTIAL_DATA = 0x40
        const val FLAG_EXTENSION_DATA = 0x80
    }
}

data class AttestedCredentialData(
    val aaguid: ByteArray,
    val credentialId: WebAuthnCredentialId,
    val cosePublicKey: CosePublicKey,
    val p256PublicKey: P256PublicKey
)

data class NoneAttestationObject(val authenticatorData: AuthenticatorData)

class WebAuthnDecodingException : IllegalArgumentException("Malformed WebAuthn input")

class WebAuthnProtocolDecoder(
    private val json: Json = Json,
    private val maximumClientDataBytes: Int = 8_192,
    private val maximumAuthenticatorDataBytes: Int = 65_536,
    private val maximumAttestationObjectBytes: Int = 131_072,
    private val maximumCredentialIdBytes: Int = 1_024
) {
    private val cbor = BoundedCborDecoder(
        maximumInputBytes = maximumAttestationObjectBytes,
        maximumDepth = 8,
        maximumContainerItems = 256,
        maximumTextBytes = 8_192,
        maximumByteStringBytes = maximumAttestationObjectBytes
    )

    init {
        require(maximumClientDataBytes in 256..65_536)
        require(maximumAuthenticatorDataBytes in 37..1_048_576)
        require(maximumAttestationObjectBytes in maximumAuthenticatorDataBytes..2 * 1_048_576)
        require(maximumCredentialIdBytes in 16..4_096)
    }

    fun decodeClientData(
        encoded: String,
        expectedType: WebAuthnCeremonyType
    ): CollectedClientData {
        val raw = decodeWireBytes(encoded, maximumClientDataBytes)
        val text = try {
            raw.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Throwable) {
            malformed()
        }
        rejectDuplicateJsonKeys(text)
        val objectValue = try {
            json.parseToJsonElement(text) as? JsonObject ?: malformed()
        } catch (_: Throwable) {
            malformed()
        }
        val type = objectValue.string("type")
        val challenge = decodeWireBytes(objectValue.string("challenge"), 64)
        val origin = objectValue.string("origin")
        val crossOrigin = objectValue["crossOrigin"]?.jsonPrimitive?.booleanOrNull ?: false
        if (type != expectedType.wireValue || origin.length !in 8..2_048 ||
            objectValue["crossOrigin"] != null && objectValue["crossOrigin"]?.jsonPrimitive?.booleanOrNull == null
        ) malformed()
        return CollectedClientData(type, challenge, origin, crossOrigin, raw)
    }

    fun decodeNoneAttestation(encoded: String): NoneAttestationObject {
        val bytes = decodeWireBytes(encoded, maximumAttestationObjectBytes)
        val root = cbor.decodeExactly(bytes) as? CborValue.MapValue ?: malformed()
        val format = root.text("fmt")
        val authenticatorBytes = root.bytes("authData")
        val statement = root.entries[CborValue.Text("attStmt")] as? CborValue.MapValue ?: malformed()
        if (format != "none" || statement.entries.isNotEmpty() || root.entries.keys != setOf(
                CborValue.Text("fmt"), CborValue.Text("authData"), CborValue.Text("attStmt")
            )
        ) malformed()
        val authenticatorData = decodeAuthenticatorData(authenticatorBytes, requireAttestedCredential = true)
        return NoneAttestationObject(authenticatorData)
    }

    fun decodeAuthenticatorData(
        bytes: ByteArray,
        requireAttestedCredential: Boolean = false
    ): AuthenticatorData {
        if (bytes.size !in 37..maximumAuthenticatorDataBytes) malformed()
        val rpIdHash = bytes.copyOfRange(0, 32)
        val flags = bytes[32].toInt() and 0xff
        if (flags and RESERVED_FLAGS != 0 ||
            flags and AuthenticatorData.FLAG_BACKUP_STATE != 0 &&
            flags and AuthenticatorData.FLAG_BACKUP_ELIGIBLE == 0
        ) malformed()
        val signCount = readUnsigned32(bytes, 33)
        var offset = 37
        val hasAttestedCredential = flags and AuthenticatorData.FLAG_ATTESTED_CREDENTIAL_DATA != 0
        if (requireAttestedCredential != hasAttestedCredential) malformed()

        val attested = if (hasAttestedCredential) {
            if (offset > bytes.size - 18) malformed()
            val aaguid = bytes.copyOfRange(offset, offset + 16)
            offset += 16
            val credentialLength = ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)
            offset += 2
            if (credentialLength !in 1..maximumCredentialIdBytes || offset > bytes.size - credentialLength) malformed()
            val credentialBytes = bytes.copyOfRange(offset, offset + credentialLength)
            offset += credentialLength
            val coseStart = offset
            val decodedCose = cbor.decode(bytes, coseStart)
            offset = decodedCose.nextOffset
            val (serialized, p256) = decodeEs256Cose(decodedCose.value, bytes.copyOfRange(coseStart, offset))
            AttestedCredentialData(
                aaguid = aaguid,
                credentialId = WebAuthnCredentialId(Base64Url.encode(credentialBytes)),
                cosePublicKey = serialized,
                p256PublicKey = p256
            )
        } else {
            null
        }

        val extensions = if (flags and AuthenticatorData.FLAG_EXTENSION_DATA != 0) {
            if (offset >= bytes.size) malformed()
            val decoded = cbor.decode(bytes, offset)
            if (decoded.nextOffset != bytes.size || decoded.value !is CborValue.MapValue) malformed()
            offset = decoded.nextOffset
            decoded.value
        } else {
            null
        }
        if (offset != bytes.size) malformed()
        return AuthenticatorData(rpIdHash, flags, signCount, attested, extensions, bytes.copyOf())
    }

    fun decodeStoredEs256PublicKey(key: CosePublicKey): P256PublicKey {
        val bytes = decodeWireBytes(key.encoded, 2_048)
        return decodeEs256Cose(cbor.decodeExactly(bytes), bytes).second
    }

    /** Converts a browser DER ECDSA signature to fixed-width raw `r || s`. */
    fun decodeDerEs256Signature(encoded: String): Es256Signature {
        val der = decodeWireBytes(encoded, 80)
        var offset = 0
        fun byte(): Int {
            if (offset >= der.size) malformed()
            return der[offset++].toInt() and 0xff
        }
        if (byte() != 0x30) malformed()
        val sequenceLength = byte()
        if (sequenceLength and 0x80 != 0 || sequenceLength != der.size - offset) malformed()
        fun integer(): ByteArray {
            if (byte() != 0x02) malformed()
            val length = byte()
            if (length !in 1..33 || offset > der.size - length) malformed()
            val value = der.copyOfRange(offset, offset + length)
            offset += length
            if (value[0].toInt() and 0x80 != 0 ||
                value.size > 1 && value[0] == 0.toByte() && value[1].toInt() and 0x80 == 0
            ) malformed()
            val magnitude = if (value.size == 33) {
                if (value[0] != 0.toByte()) malformed()
                value.copyOfRange(1, value.size)
            } else value
            if (magnitude.size > 32 || magnitude.all { it == 0.toByte() }) malformed()
            return ByteArray(32 - magnitude.size) + magnitude
        }
        val r = integer()
        val s = integer()
        if (offset != der.size) malformed()
        return Es256Signature(r + s)
    }

    private fun decodeEs256Cose(value: CborValue, serialized: ByteArray): Pair<CosePublicKey, P256PublicKey> {
        val map = value as? CborValue.MapValue ?: malformed()
        fun integer(key: Long): Long = (map.entries[CborValue.Integer(key)] as? CborValue.Integer)?.value ?: malformed()
        fun bytes(key: Long): ByteArray = (map.entries[CborValue.Integer(key)] as? CborValue.Bytes)?.copyBytes() ?: malformed()
        if (map.entries.keys != setOf(
                CborValue.Integer(1), CborValue.Integer(3), CborValue.Integer(-1),
                CborValue.Integer(-2), CborValue.Integer(-3)
            )
        ) malformed()
        if (integer(1) != 2L || integer(3) != -7L || integer(-1) != 1L) malformed()
        val x = bytes(-2)
        val y = bytes(-3)
        if (x.size != 32 || y.size != 32) malformed()
        return CosePublicKey(Base64Url.encode(serialized)) to P256PublicKey(byteArrayOf(0x04) + x + y)
    }

    private fun decodeWireBytes(encoded: String, maximum: Int): ByteArray = try {
        Base64Url.decode(encoded, maximum)
    } catch (_: IllegalArgumentException) {
        malformed()
    }

    private fun JsonObject.string(name: String): String =
        this[name]?.jsonPrimitive?.takeIf { it.isString }?.content ?: malformed()

    private fun CborValue.MapValue.text(name: String): String =
        (entries[CborValue.Text(name)] as? CborValue.Text)?.value ?: malformed()

    private fun CborValue.MapValue.bytes(name: String): ByteArray =
        (entries[CborValue.Text(name)] as? CborValue.Bytes)?.copyBytes() ?: malformed()

    private fun readUnsigned32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    companion object {
        private const val RESERVED_FLAGS = 0x22
    }
}

/** Reject duplicate keys at every JSON object depth before kotlinx.serialization can collapse them. */
private fun rejectDuplicateJsonKeys(input: String) {
    class Cursor(var offset: Int = 0)
    val cursor = Cursor()
    fun skipWhitespace() {
        while (cursor.offset < input.length && input[cursor.offset] in " \t\r\n") cursor.offset++
    }
    fun string(): String {
        if (cursor.offset >= input.length || input[cursor.offset++] != '"') malformed()
        val result = StringBuilder()
        while (cursor.offset < input.length) {
            val character = input[cursor.offset++]
            when (character) {
                '"' -> return result.toString()
                '\\' -> {
                    if (cursor.offset >= input.length) malformed()
                    val escaped = input[cursor.offset++]
                    if (escaped == 'u') {
                        if (cursor.offset > input.length - 4) malformed()
                        val hex = input.substring(cursor.offset, cursor.offset + 4)
                        if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) malformed()
                        result.append(hex.toInt(16).toChar())
                        cursor.offset += 4
                    } else {
                        if (escaped !in "\"\\/bfnrt") malformed()
                        result.append(escaped)
                    }
                }
                else -> {
                    if (character.code < 0x20) malformed()
                    result.append(character)
                }
            }
        }
        malformed()
    }
    lateinit var value: () -> Unit
    fun array() {
        cursor.offset++
        skipWhitespace()
        if (cursor.offset < input.length && input[cursor.offset] == ']') { cursor.offset++; return }
        while (true) {
            value()
            skipWhitespace()
            when (input.getOrNull(cursor.offset++)) {
                ']' -> return
                ',' -> skipWhitespace()
                else -> malformed()
            }
        }
    }
    fun objectValue() {
        cursor.offset++
        val keys = mutableSetOf<String>()
        skipWhitespace()
        if (cursor.offset < input.length && input[cursor.offset] == '}') { cursor.offset++; return }
        while (true) {
            skipWhitespace()
            val key = string()
            if (!keys.add(key)) malformed()
            skipWhitespace()
            if (input.getOrNull(cursor.offset++) != ':') malformed()
            value()
            skipWhitespace()
            when (input.getOrNull(cursor.offset++)) {
                '}' -> return
                ',' -> Unit
                else -> malformed()
            }
        }
    }
    value = value@{
        skipWhitespace()
        when (input.getOrNull(cursor.offset)) {
            '{' -> objectValue()
            '[' -> array()
            '"' -> { string(); Unit }
            null -> malformed()
            else -> {
                val start = cursor.offset
                while (cursor.offset < input.length && input[cursor.offset] !in ",]} \t\r\n") cursor.offset++
                if (cursor.offset == start) malformed()
            }
        }
    }
    skipWhitespace()
    value()
    skipWhitespace()
    if (cursor.offset != input.length) malformed()
}

private fun malformed(): Nothing = throw WebAuthnDecodingException()
