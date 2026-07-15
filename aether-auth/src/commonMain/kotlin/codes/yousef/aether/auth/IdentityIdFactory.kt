package codes.yousef.aether.auth

/** Generates canonical UUIDv7 entity IDs from the configured wall clock and CSPRNG. */
class IdentityIdFactory(
    private val clock: IdentityClock,
    private val random: IdentitySecureRandom
) {
    constructor(runtime: IdentityRuntime) : this(runtime.clock, runtime.secureRandom)

    fun newUserId(): UserId = UserId.parse(nextUuidV7())
    fun newCredentialId(): CredentialId = CredentialId.parse(nextUuidV7())
    fun newSessionId(): SessionId = SessionId.parse(nextUuidV7())
    fun newOrganizationId(): OrganizationId = OrganizationId.parse(nextUuidV7())
    fun newMembershipId(): MembershipId = MembershipId.parse(nextUuidV7())
    fun newInvitationId(): InvitationId = InvitationId.parse(nextUuidV7())
    fun newServiceIdentityId(): ServiceIdentityId = ServiceIdentityId.parse(nextUuidV7())
    fun newServiceCredentialId(): ServiceCredentialId = ServiceCredentialId.parse(nextUuidV7())
    fun newDeviceGrantId(): DeviceGrantId = DeviceGrantId.parse(nextUuidV7())
    fun newDeviceTokenFamilyId(): DeviceTokenFamilyId = DeviceTokenFamilyId.parse(nextUuidV7())
    fun newDeviceAccessTokenId(): DeviceAccessTokenId = DeviceAccessTokenId.parse(nextUuidV7())
    fun newDeviceRefreshTokenId(): DeviceRefreshTokenId = DeviceRefreshTokenId.parse(nextUuidV7())
    fun newChallengeId(): ChallengeId = ChallengeId.parse(nextUuidV7())
    fun newRecoveryCodeId(): RecoveryCodeId = RecoveryCodeId.parse(nextUuidV7())
    fun newExternalIdentityId(): ExternalIdentityId = ExternalIdentityId.parse(nextUuidV7())
    fun newAuditEventId(): AuditEventId = AuditEventId.parse(nextUuidV7())
    fun newExternalReplayReceiptId(): ExternalReplayReceiptId = ExternalReplayReceiptId.parse(nextUuidV7())
    fun newScimOperationId(): ScimOperationId = ScimOperationId.parse(nextUuidV7())

    private fun nextUuidV7(): String {
        val timestamp = clock.now().toEpochMilliseconds()
        require(timestamp in 0..0x0000_ffff_ffff_ffffL) { "Clock is outside the UUIDv7 timestamp range" }
        val entropy = random.nextBytes(10)
        require(entropy.size == 10) { "IdentitySecureRandom returned an unexpected byte count" }
        val bytes = ByteArray(16)
        for (index in 0 until 6) {
            bytes[index] = (timestamp ushr (40 - index * 8)).toByte()
        }
        bytes[6] = (0x70 or (entropy[0].toInt() and 0x0f)).toByte()
        bytes[7] = entropy[1]
        bytes[8] = (0x80 or (entropy[2].toInt() and 0x3f)).toByte()
        entropy.copyInto(bytes, destinationOffset = 9, startIndex = 3)
        return buildString(36) {
            bytes.forEachIndexed { index, byte ->
                if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
                append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
            }
        }
    }
}
