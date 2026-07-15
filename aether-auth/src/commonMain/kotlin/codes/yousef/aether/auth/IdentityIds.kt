package codes.yousef.aether.auth

import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

private val INTERNAL_ID_PATTERN = Regex("[A-Za-z0-9_-][A-Za-z0-9._:-]{0,254}")
private val CANONICAL_UUID_V7_PATTERN = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
)

/** Returns true only for a lowercase, hyphenated RFC 9562 UUIDv7 with the RFC 4122 variant. */
fun isCanonicalIdentityUuidV7(value: String): Boolean = CANONICAL_UUID_V7_PATTERN.matches(value)

@PublishedApi
internal fun requireValidIdentityId(value: String, type: String) {
    require(INTERNAL_ID_PATTERN.matches(value)) {
        "$type must be 1..255 URL-safe opaque characters"
    }
}

private fun requireCanonicalIdentityUuidV7(value: String, type: String): String {
    require(isCanonicalIdentityUuidV7(value)) {
        "$type must be a lowercase canonical UUIDv7 with the RFC 4122 variant"
    }
    return value
}

private inline fun <T> parseIdentityUuidV7(value: String, type: String, constructor: (String) -> T): T =
    constructor(requireCanonicalIdentityUuidV7(value, type))

private inline fun <T> parseIdentityUuidV7OrNull(value: String, type: String, constructor: (String) -> T): T? =
    if (isCanonicalIdentityUuidV7(value)) constructor(value) else null

/** Stable, storage-neutral user identifier. */
@Serializable(with = UserIdUuidV7Serializer::class)
@JvmInline
value class UserId(val value: String) {
    init { requireValidIdentityId(value, "UserId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): UserId = parseIdentityUuidV7(value, "UserId", ::UserId)
        fun parseOrNull(value: String): UserId? = parseIdentityUuidV7OrNull(value, "UserId", ::UserId)
    }
}

/** Stable internal credential-record identifier generated as UUIDv7. */
@Serializable(with = CredentialIdUuidV7Serializer::class)
@JvmInline
value class CredentialId(val value: String) {
    init { requireValidIdentityId(value, "CredentialId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): CredentialId = parseIdentityUuidV7(value, "CredentialId", ::CredentialId)
        fun parseOrNull(value: String): CredentialId? = parseIdentityUuidV7OrNull(value, "CredentialId", ::CredentialId)
    }
}

/** Public selector for an identity session. The session secret is never part of this value. */
@Serializable(with = SessionIdUuidV7Serializer::class)
@JvmInline
value class SessionId(val value: String) {
    init { requireValidIdentityId(value, "SessionId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): SessionId = parseIdentityUuidV7(value, "SessionId", ::SessionId)
        fun parseOrNull(value: String): SessionId? = parseIdentityUuidV7OrNull(value, "SessionId", ::SessionId)
    }
}

@Serializable(with = OrganizationIdUuidV7Serializer::class)
@JvmInline
value class OrganizationId(val value: String) {
    init { requireValidIdentityId(value, "OrganizationId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): OrganizationId = parseIdentityUuidV7(value, "OrganizationId", ::OrganizationId)
        fun parseOrNull(value: String): OrganizationId? =
            parseIdentityUuidV7OrNull(value, "OrganizationId", ::OrganizationId)
    }
}

@Serializable(with = MembershipIdUuidV7Serializer::class)
@JvmInline
value class MembershipId(val value: String) {
    init { requireValidIdentityId(value, "MembershipId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): MembershipId = parseIdentityUuidV7(value, "MembershipId", ::MembershipId)
        fun parseOrNull(value: String): MembershipId? = parseIdentityUuidV7OrNull(value, "MembershipId", ::MembershipId)
    }
}

@Serializable(with = InvitationIdUuidV7Serializer::class)
@JvmInline
value class InvitationId(val value: String) {
    init { requireValidIdentityId(value, "InvitationId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): InvitationId = parseIdentityUuidV7(value, "InvitationId", ::InvitationId)
        fun parseOrNull(value: String): InvitationId? = parseIdentityUuidV7OrNull(value, "InvitationId", ::InvitationId)
    }
}

@Serializable(with = ServiceIdentityIdUuidV7Serializer::class)
@JvmInline
value class ServiceIdentityId(val value: String) {
    init { requireValidIdentityId(value, "ServiceIdentityId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ServiceIdentityId =
            parseIdentityUuidV7(value, "ServiceIdentityId", ::ServiceIdentityId)
        fun parseOrNull(value: String): ServiceIdentityId? =
            parseIdentityUuidV7OrNull(value, "ServiceIdentityId", ::ServiceIdentityId)
    }
}

@Serializable(with = ServiceCredentialIdUuidV7Serializer::class)
@JvmInline
value class ServiceCredentialId(val value: String) {
    init { requireValidIdentityId(value, "ServiceCredentialId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ServiceCredentialId =
            parseIdentityUuidV7(value, "ServiceCredentialId", ::ServiceCredentialId)
        fun parseOrNull(value: String): ServiceCredentialId? =
            parseIdentityUuidV7OrNull(value, "ServiceCredentialId", ::ServiceCredentialId)
    }
}

@Serializable(with = DeviceGrantIdUuidV7Serializer::class)
@JvmInline
value class DeviceGrantId(val value: String) {
    init { requireValidIdentityId(value, "DeviceGrantId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): DeviceGrantId = parseIdentityUuidV7(value, "DeviceGrantId", ::DeviceGrantId)
        fun parseOrNull(value: String): DeviceGrantId? = parseIdentityUuidV7OrNull(value, "DeviceGrantId", ::DeviceGrantId)
    }
}

@Serializable(with = DeviceTokenFamilyIdUuidV7Serializer::class)
@JvmInline
value class DeviceTokenFamilyId(val value: String) {
    init { requireValidIdentityId(value, "DeviceTokenFamilyId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): DeviceTokenFamilyId =
            parseIdentityUuidV7(value, "DeviceTokenFamilyId", ::DeviceTokenFamilyId)
        fun parseOrNull(value: String): DeviceTokenFamilyId? =
            parseIdentityUuidV7OrNull(value, "DeviceTokenFamilyId", ::DeviceTokenFamilyId)
    }
}

@Serializable(with = DeviceAccessTokenIdUuidV7Serializer::class)
@JvmInline
value class DeviceAccessTokenId(val value: String) {
    init { requireValidIdentityId(value, "DeviceAccessTokenId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): DeviceAccessTokenId =
            parseIdentityUuidV7(value, "DeviceAccessTokenId", ::DeviceAccessTokenId)
        fun parseOrNull(value: String): DeviceAccessTokenId? =
            parseIdentityUuidV7OrNull(value, "DeviceAccessTokenId", ::DeviceAccessTokenId)
    }
}

@Serializable(with = DeviceRefreshTokenIdUuidV7Serializer::class)
@JvmInline
value class DeviceRefreshTokenId(val value: String) {
    init { requireValidIdentityId(value, "DeviceRefreshTokenId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): DeviceRefreshTokenId =
            parseIdentityUuidV7(value, "DeviceRefreshTokenId", ::DeviceRefreshTokenId)
        fun parseOrNull(value: String): DeviceRefreshTokenId? =
            parseIdentityUuidV7OrNull(value, "DeviceRefreshTokenId", ::DeviceRefreshTokenId)
    }
}

@Serializable(with = ChallengeIdUuidV7Serializer::class)
@JvmInline
value class ChallengeId(val value: String) {
    init { requireValidIdentityId(value, "ChallengeId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ChallengeId = parseIdentityUuidV7(value, "ChallengeId", ::ChallengeId)
        fun parseOrNull(value: String): ChallengeId? = parseIdentityUuidV7OrNull(value, "ChallengeId", ::ChallengeId)
    }
}

@Serializable(with = RecoveryCodeIdUuidV7Serializer::class)
@JvmInline
value class RecoveryCodeId(val value: String) {
    init { requireValidIdentityId(value, "RecoveryCodeId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): RecoveryCodeId = parseIdentityUuidV7(value, "RecoveryCodeId", ::RecoveryCodeId)
        fun parseOrNull(value: String): RecoveryCodeId? =
            parseIdentityUuidV7OrNull(value, "RecoveryCodeId", ::RecoveryCodeId)
    }
}

@Serializable(with = ExternalIdentityIdUuidV7Serializer::class)
@JvmInline
value class ExternalIdentityId(val value: String) {
    init { requireValidIdentityId(value, "ExternalIdentityId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ExternalIdentityId =
            parseIdentityUuidV7(value, "ExternalIdentityId", ::ExternalIdentityId)
        fun parseOrNull(value: String): ExternalIdentityId? =
            parseIdentityUuidV7OrNull(value, "ExternalIdentityId", ::ExternalIdentityId)
    }
}

@Serializable(with = AuditEventIdUuidV7Serializer::class)
@JvmInline
value class AuditEventId(val value: String) {
    init { requireValidIdentityId(value, "AuditEventId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): AuditEventId = parseIdentityUuidV7(value, "AuditEventId", ::AuditEventId)
        fun parseOrNull(value: String): AuditEventId? = parseIdentityUuidV7OrNull(value, "AuditEventId", ::AuditEventId)
    }
}

/** Idempotency receipt for an assertion received from an external identity provider. */
@Serializable(with = ExternalReplayReceiptIdUuidV7Serializer::class)
@JvmInline
value class ExternalReplayReceiptId(val value: String) {
    init { requireValidIdentityId(value, "ExternalReplayReceiptId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ExternalReplayReceiptId =
            parseIdentityUuidV7(value, "ExternalReplayReceiptId", ::ExternalReplayReceiptId)
        fun parseOrNull(value: String): ExternalReplayReceiptId? =
            parseIdentityUuidV7OrNull(value, "ExternalReplayReceiptId", ::ExternalReplayReceiptId)
    }
}

/** Stable external identifier for an idempotent SCIM operation. */
@Serializable(with = ScimOperationIdUuidV7Serializer::class)
@JvmInline
value class ScimOperationId(val value: String) {
    init { requireValidIdentityId(value, "ScimOperationId") }
    override fun toString(): String = value

    companion object {
        fun parse(value: String): ScimOperationId = parseIdentityUuidV7(value, "ScimOperationId", ::ScimOperationId)
        fun parseOrNull(value: String): ScimOperationId? =
            parseIdentityUuidV7OrNull(value, "ScimOperationId", ::ScimOperationId)
    }
}

/**
 * Constructors intentionally remain source-compatible for internal fixtures and migrations. Public
 * wire code must use a typed `parse` API; every kotlinx serializer below enforces the same rule.
 */
internal abstract class IdentityIdUuidV7Serializer<T>(
    serialName: String,
    private val type: String,
    private val valueOf: (T) -> String,
    private val constructor: (String) -> T
) : KSerializer<T> {
    final override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("codes.yousef.aether.auth.$serialName", PrimitiveKind.STRING)

    final override fun serialize(encoder: Encoder, value: T) {
        val encoded = valueOf(value)
        if (!isCanonicalIdentityUuidV7(encoded)) {
            throw SerializationException("$type must be a lowercase canonical UUIDv7 with the RFC 4122 variant")
        }
        encoder.encodeString(encoded)
    }

    final override fun deserialize(decoder: Decoder): T {
        val encoded = decoder.decodeString()
        if (!isCanonicalIdentityUuidV7(encoded)) {
            throw SerializationException("$type must be a lowercase canonical UUIDv7 with the RFC 4122 variant")
        }
        return constructor(encoded)
    }
}

internal object UserIdUuidV7Serializer :
    IdentityIdUuidV7Serializer<UserId>("UserId", "UserId", UserId::value, ::UserId)
internal object CredentialIdUuidV7Serializer :
    IdentityIdUuidV7Serializer<CredentialId>("CredentialId", "CredentialId", CredentialId::value, ::CredentialId)
internal object SessionIdUuidV7Serializer :
    IdentityIdUuidV7Serializer<SessionId>("SessionId", "SessionId", SessionId::value, ::SessionId)
internal object OrganizationIdUuidV7Serializer :
    IdentityIdUuidV7Serializer<OrganizationId>("OrganizationId", "OrganizationId", OrganizationId::value, ::OrganizationId)
internal object MembershipIdUuidV7Serializer :
    IdentityIdUuidV7Serializer<MembershipId>("MembershipId", "MembershipId", MembershipId::value, ::MembershipId)
internal object InvitationIdUuidV7Serializer :
    IdentityIdUuidV7Serializer<InvitationId>("InvitationId", "InvitationId", InvitationId::value, ::InvitationId)
internal object ServiceIdentityIdUuidV7Serializer : IdentityIdUuidV7Serializer<ServiceIdentityId>(
    "ServiceIdentityId", "ServiceIdentityId", ServiceIdentityId::value, ::ServiceIdentityId
)
internal object ServiceCredentialIdUuidV7Serializer : IdentityIdUuidV7Serializer<ServiceCredentialId>(
    "ServiceCredentialId", "ServiceCredentialId", ServiceCredentialId::value, ::ServiceCredentialId
)
internal object DeviceGrantIdUuidV7Serializer : IdentityIdUuidV7Serializer<DeviceGrantId>(
    "DeviceGrantId", "DeviceGrantId", DeviceGrantId::value, ::DeviceGrantId
)
internal object DeviceTokenFamilyIdUuidV7Serializer : IdentityIdUuidV7Serializer<DeviceTokenFamilyId>(
    "DeviceTokenFamilyId", "DeviceTokenFamilyId", DeviceTokenFamilyId::value, ::DeviceTokenFamilyId
)
internal object DeviceAccessTokenIdUuidV7Serializer : IdentityIdUuidV7Serializer<DeviceAccessTokenId>(
    "DeviceAccessTokenId", "DeviceAccessTokenId", DeviceAccessTokenId::value, ::DeviceAccessTokenId
)
internal object DeviceRefreshTokenIdUuidV7Serializer : IdentityIdUuidV7Serializer<DeviceRefreshTokenId>(
    "DeviceRefreshTokenId", "DeviceRefreshTokenId", DeviceRefreshTokenId::value, ::DeviceRefreshTokenId
)
internal object ChallengeIdUuidV7Serializer :
    IdentityIdUuidV7Serializer<ChallengeId>("ChallengeId", "ChallengeId", ChallengeId::value, ::ChallengeId)
internal object RecoveryCodeIdUuidV7Serializer : IdentityIdUuidV7Serializer<RecoveryCodeId>(
    "RecoveryCodeId", "RecoveryCodeId", RecoveryCodeId::value, ::RecoveryCodeId
)
internal object ExternalIdentityIdUuidV7Serializer : IdentityIdUuidV7Serializer<ExternalIdentityId>(
    "ExternalIdentityId", "ExternalIdentityId", ExternalIdentityId::value, ::ExternalIdentityId
)
internal object AuditEventIdUuidV7Serializer :
    IdentityIdUuidV7Serializer<AuditEventId>("AuditEventId", "AuditEventId", AuditEventId::value, ::AuditEventId)
internal object ExternalReplayReceiptIdUuidV7Serializer : IdentityIdUuidV7Serializer<ExternalReplayReceiptId>(
    "ExternalReplayReceiptId",
    "ExternalReplayReceiptId",
    ExternalReplayReceiptId::value,
    ::ExternalReplayReceiptId
)
internal object ScimOperationIdUuidV7Serializer : IdentityIdUuidV7Serializer<ScimOperationId>(
    "ScimOperationId", "ScimOperationId", ScimOperationId::value, ::ScimOperationId
)
