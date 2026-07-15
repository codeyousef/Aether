package codes.yousef.aether.auth

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityIdsWireTest {
    @Test
    fun `every typed identity id enforces canonical UUIDv7 on the wire`() {
        assertWireContract(UserId.serializer(), UserId::parse, UserId::parseOrNull, ::UserId, UserId::value)
        assertWireContract(
            CredentialId.serializer(), CredentialId::parse, CredentialId::parseOrNull, ::CredentialId, CredentialId::value
        )
        assertWireContract(SessionId.serializer(), SessionId::parse, SessionId::parseOrNull, ::SessionId, SessionId::value)
        assertWireContract(
            OrganizationId.serializer(), OrganizationId::parse, OrganizationId::parseOrNull, ::OrganizationId,
            OrganizationId::value
        )
        assertWireContract(
            MembershipId.serializer(), MembershipId::parse, MembershipId::parseOrNull, ::MembershipId, MembershipId::value
        )
        assertWireContract(
            InvitationId.serializer(), InvitationId::parse, InvitationId::parseOrNull, ::InvitationId, InvitationId::value
        )
        assertWireContract(
            ServiceIdentityId.serializer(), ServiceIdentityId::parse, ServiceIdentityId::parseOrNull, ::ServiceIdentityId,
            ServiceIdentityId::value
        )
        assertWireContract(
            ServiceCredentialId.serializer(), ServiceCredentialId::parse, ServiceCredentialId::parseOrNull,
            ::ServiceCredentialId, ServiceCredentialId::value
        )
        assertWireContract(
            DeviceGrantId.serializer(), DeviceGrantId::parse, DeviceGrantId::parseOrNull, ::DeviceGrantId,
            DeviceGrantId::value
        )
        assertWireContract(
            DeviceTokenFamilyId.serializer(), DeviceTokenFamilyId::parse, DeviceTokenFamilyId::parseOrNull,
            ::DeviceTokenFamilyId, DeviceTokenFamilyId::value
        )
        assertWireContract(
            DeviceAccessTokenId.serializer(), DeviceAccessTokenId::parse, DeviceAccessTokenId::parseOrNull,
            ::DeviceAccessTokenId, DeviceAccessTokenId::value
        )
        assertWireContract(
            DeviceRefreshTokenId.serializer(), DeviceRefreshTokenId::parse, DeviceRefreshTokenId::parseOrNull,
            ::DeviceRefreshTokenId, DeviceRefreshTokenId::value
        )
        assertWireContract(
            ChallengeId.serializer(), ChallengeId::parse, ChallengeId::parseOrNull, ::ChallengeId, ChallengeId::value
        )
        assertWireContract(
            RecoveryCodeId.serializer(), RecoveryCodeId::parse, RecoveryCodeId::parseOrNull, ::RecoveryCodeId,
            RecoveryCodeId::value
        )
        assertWireContract(
            ExternalIdentityId.serializer(), ExternalIdentityId::parse, ExternalIdentityId::parseOrNull,
            ::ExternalIdentityId, ExternalIdentityId::value
        )
        assertWireContract(
            AuditEventId.serializer(), AuditEventId::parse, AuditEventId::parseOrNull, ::AuditEventId, AuditEventId::value
        )
        assertWireContract(
            ExternalReplayReceiptId.serializer(), ExternalReplayReceiptId::parse, ExternalReplayReceiptId::parseOrNull,
            ::ExternalReplayReceiptId, ExternalReplayReceiptId::value
        )
        assertWireContract(
            ScimOperationId.serializer(), ScimOperationId::parse, ScimOperationId::parseOrNull, ::ScimOperationId,
            ScimOperationId::value
        )
    }

    @Test
    fun `canonical validator rejects version variant shape and case deviations`() {
        assertTrue(isCanonicalIdentityUuidV7(CANONICAL_UUID_V7))
        INVALID_WIRE_VALUES.forEach { assertEquals(false, isCanonicalIdentityUuidV7(it), it) }
    }

    private fun <T> assertWireContract(
        serializer: KSerializer<T>,
        parse: (String) -> T,
        parseOrNull: (String) -> T?,
        compatibilityConstructor: (String) -> T,
        valueOf: (T) -> String
    ) {
        val parsed = parse(CANONICAL_UUID_V7)
        assertEquals(CANONICAL_UUID_V7, valueOf(parsed))
        assertEquals("\"$CANONICAL_UUID_V7\"", Json.encodeToString(serializer, parsed))
        assertEquals(CANONICAL_UUID_V7, valueOf(Json.decodeFromString(serializer, "\"$CANONICAL_UUID_V7\"")))

        INVALID_WIRE_VALUES.forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) { parse(invalid) }
            assertNull(parseOrNull(invalid), invalid)
            assertFailsWith<SerializationException>(invalid) {
                Json.decodeFromString(serializer, "\"$invalid\"")
            }
        }

        val internalFixture = compatibilityConstructor("internal_fixture_id")
        assertEquals("internal_fixture_id", valueOf(internalFixture))
        assertFailsWith<SerializationException> {
            Json.encodeToString(serializer, internalFixture)
        }
    }

    private companion object {
        const val CANONICAL_UUID_V7 = "01890f3e-4c7a-7b2c-8d5e-6f708192a3b4"
        val INVALID_WIRE_VALUES = listOf(
            "internal_fixture_id",
            "01890f3e-4c7a-4b2c-8d5e-6f708192a3b4",
            "01890f3e-4c7a-7b2c-7d5e-6f708192a3b4",
            "01890F3E-4C7A-7B2C-8D5E-6F708192A3B4",
            "01890f3e4c7a7b2c8d5e6f708192a3b4"
        )
    }
}
