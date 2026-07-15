package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.AuditAction
import codes.yousef.aether.auth.AuditActor
import codes.yousef.aether.auth.AuditActorType
import codes.yousef.aether.auth.AuditEvent
import codes.yousef.aether.auth.AuditEventId
import codes.yousef.aether.auth.AuditOutcome
import codes.yousef.aether.auth.AuditTarget
import codes.yousef.aether.auth.AuditTargetType
import codes.yousef.aether.auth.AuthenticationAssurance
import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.Challenge
import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.ChallengePurpose
import codes.yousef.aether.auth.ChallengeState
import codes.yousef.aether.auth.CosePublicKey
import codes.yousef.aether.auth.Credential
import codes.yousef.aether.auth.CredentialId
import codes.yousef.aether.auth.CredentialState
import codes.yousef.aether.auth.DeviceGrant
import codes.yousef.aether.auth.DeviceGrantId
import codes.yousef.aether.auth.DeviceGrantState
import codes.yousef.aether.auth.DeviceAccessTokenId
import codes.yousef.aether.auth.DeviceRefreshTokenId
import codes.yousef.aether.auth.DeviceTokenFamilyId
import codes.yousef.aether.auth.DigestAlgorithm
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.aether.auth.ExternalIdentity
import codes.yousef.aether.auth.ExternalIdentityId
import codes.yousef.aether.auth.ExternalIdentityReplayReceipt
import codes.yousef.aether.auth.ExternalIdentityState
import codes.yousef.aether.auth.ExternalReplayReceiptId
import codes.yousef.aether.auth.ExternalSubject
import codes.yousef.aether.auth.FederationProviderControl
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.FederationProviderState
import codes.yousef.aether.auth.IdentitySession
import codes.yousef.aether.auth.Invitation
import codes.yousef.aether.auth.InvitationId
import codes.yousef.aether.auth.InvitationState
import codes.yousef.aether.auth.Membership
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.MembershipState
import codes.yousef.aether.auth.Organization
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.OrganizationState
import codes.yousef.aether.auth.RecoveryCode
import codes.yousef.aether.auth.RecoveryCodeId
import codes.yousef.aether.auth.RecoveryCodeState
import codes.yousef.aether.auth.ScimMutation
import codes.yousef.aether.auth.ScimMutationType
import codes.yousef.aether.auth.ScimOperationId
import codes.yousef.aether.auth.SecretDigest
import codes.yousef.aether.auth.ServiceCredential
import codes.yousef.aether.auth.ServiceCredentialId
import codes.yousef.aether.auth.ServiceCredentialState
import codes.yousef.aether.auth.ServiceIdentity
import codes.yousef.aether.auth.ServiceIdentityId
import codes.yousef.aether.auth.ServiceIdentityState
import codes.yousef.aether.auth.SessionId
import codes.yousef.aether.auth.SessionAuthenticationMethod
import codes.yousef.aether.auth.SessionState
import codes.yousef.aether.auth.User
import codes.yousef.aether.auth.UserId
import codes.yousef.aether.auth.UserState
import codes.yousef.aether.auth.WebAuthnCredentialId
import codes.yousef.aether.auth.WEBAUTHN_STORE_REJECTION_REASON_CODE
import kotlin.time.Instant

/** Valid, deterministic identity model fixtures shared by adapter conformance suites. */
object IdentityFixtures {
    val baseInstant: Instant = Instant.fromEpochMilliseconds(1_735_689_600_000L)

    /** Deterministic canonical UUIDv7 values for fixtures that cross a serialization or HTTP boundary. */
    fun canonicalUuidV7(sequence: Long): String {
        require(sequence in 0..0xFFFF_FFFF_FFFFL) { "Fixture UUIDv7 sequence is out of range" }
        return "018f0000-0000-7000-8000-${sequence.toString(16).padStart(12, '0')}"
    }

    fun userId(sequence: Long = 1): UserId = UserId.parse(canonicalUuidV7(0x10_0000 + sequence))
    fun userId(label: String): UserId = UserId.parse(canonicalUuidV7(labeledSequence(1, label)))
    fun credentialId(sequence: Long = 1): CredentialId = CredentialId.parse(canonicalUuidV7(0x20_0000 + sequence))
    fun credentialId(label: String): CredentialId = CredentialId.parse(canonicalUuidV7(labeledSequence(2, label)))
    fun sessionId(sequence: Long = 1): SessionId = SessionId.parse(canonicalUuidV7(0x30_0000 + sequence))
    fun sessionId(label: String): SessionId = SessionId.parse(canonicalUuidV7(labeledSequence(3, label)))
    fun organizationId(sequence: Long = 1): OrganizationId = OrganizationId.parse(canonicalUuidV7(0x40_0000 + sequence))
    fun organizationId(label: String): OrganizationId = OrganizationId.parse(canonicalUuidV7(labeledSequence(4, label)))
    fun membershipId(sequence: Long = 1): MembershipId = MembershipId.parse(canonicalUuidV7(0x50_0000 + sequence))
    fun membershipId(label: String): MembershipId = MembershipId.parse(canonicalUuidV7(labeledSequence(5, label)))
    fun invitationId(sequence: Long = 1): InvitationId = InvitationId.parse(canonicalUuidV7(0x60_0000 + sequence))
    fun invitationId(label: String): InvitationId = InvitationId.parse(canonicalUuidV7(labeledSequence(6, label)))
    fun serviceIdentityId(sequence: Long = 1): ServiceIdentityId =
        ServiceIdentityId.parse(canonicalUuidV7(0x70_0000 + sequence))
    fun serviceIdentityId(label: String): ServiceIdentityId =
        ServiceIdentityId.parse(canonicalUuidV7(labeledSequence(7, label)))
    fun serviceCredentialId(sequence: Long = 1): ServiceCredentialId =
        ServiceCredentialId.parse(canonicalUuidV7(0x80_0000 + sequence))
    fun serviceCredentialId(label: String): ServiceCredentialId =
        ServiceCredentialId.parse(canonicalUuidV7(labeledSequence(8, label)))
    fun deviceGrantId(sequence: Long = 1): DeviceGrantId = DeviceGrantId.parse(canonicalUuidV7(0x90_0000 + sequence))
    fun deviceGrantId(label: String): DeviceGrantId = DeviceGrantId.parse(canonicalUuidV7(labeledSequence(9, label)))
    fun deviceTokenFamilyId(sequence: Long = 1): DeviceTokenFamilyId =
        DeviceTokenFamilyId.parse(canonicalUuidV7(0xA0_0000 + sequence))
    fun deviceTokenFamilyId(label: String): DeviceTokenFamilyId =
        DeviceTokenFamilyId.parse(canonicalUuidV7(labeledSequence(10, label)))
    fun deviceAccessTokenId(sequence: Long = 1): DeviceAccessTokenId =
        DeviceAccessTokenId.parse(canonicalUuidV7(0xB0_0000 + sequence))
    fun deviceAccessTokenId(label: String): DeviceAccessTokenId =
        DeviceAccessTokenId.parse(canonicalUuidV7(labeledSequence(11, label)))
    fun deviceRefreshTokenId(sequence: Long = 1): DeviceRefreshTokenId =
        DeviceRefreshTokenId.parse(canonicalUuidV7(0xC0_0000 + sequence))
    fun deviceRefreshTokenId(label: String): DeviceRefreshTokenId =
        DeviceRefreshTokenId.parse(canonicalUuidV7(labeledSequence(12, label)))
    fun challengeId(sequence: Long = 1): ChallengeId = ChallengeId.parse(canonicalUuidV7(0xD0_0000 + sequence))
    fun challengeId(label: String): ChallengeId = ChallengeId.parse(canonicalUuidV7(labeledSequence(13, label)))
    fun recoveryCodeId(sequence: Long = 1): RecoveryCodeId = RecoveryCodeId.parse(canonicalUuidV7(0xE0_0000 + sequence))
    fun recoveryCodeId(label: String): RecoveryCodeId = RecoveryCodeId.parse(canonicalUuidV7(labeledSequence(14, label)))
    fun externalIdentityId(sequence: Long = 1): ExternalIdentityId =
        ExternalIdentityId.parse(canonicalUuidV7(0xF0_0000 + sequence))
    fun externalIdentityId(label: String): ExternalIdentityId =
        ExternalIdentityId.parse(canonicalUuidV7(labeledSequence(15, label)))
    fun auditEventId(sequence: Long = 1): AuditEventId = AuditEventId.parse(canonicalUuidV7(0x100_0000 + sequence))
    fun auditEventId(label: String): AuditEventId = AuditEventId.parse(canonicalUuidV7(labeledSequence(16, label)))
    fun replayReceiptId(sequence: Long = 1): ExternalReplayReceiptId =
        ExternalReplayReceiptId.parse(canonicalUuidV7(0x110_0000 + sequence))
    fun replayReceiptId(label: String): ExternalReplayReceiptId =
        ExternalReplayReceiptId.parse(canonicalUuidV7(labeledSequence(17, label)))
    fun scimOperationId(sequence: Long = 1): ScimOperationId =
        ScimOperationId.parse(canonicalUuidV7(0x120_0000 + sequence))
    fun scimOperationId(label: String): ScimOperationId =
        ScimOperationId.parse(canonicalUuidV7(labeledSequence(18, label)))

    private fun labeledSequence(namespace: Int, label: String): Long {
        require(label.isNotEmpty()) { "Fixture UUIDv7 label must not be empty" }
        var hash = 0L
        label.forEach { character -> hash = (hash * 131 + character.code) and 0xFF_FFFF_FFFFL }
        return (namespace.toLong() shl 40) or hash
    }

    fun instant(offsetMilliseconds: Long = 0): Instant =
        Instant.fromEpochMilliseconds(baseInstant.toEpochMilliseconds() + offsetMilliseconds)

    fun digest(label: String = "fixture"): SecretDigest = SecretDigest(
        algorithm = DigestAlgorithm.HMAC_SHA256,
        encoded = "digest-$label",
        keyVersion = "test-v1"
    )

    fun federationProviderStorageKey(
        kind: FederationProviderKind = FederationProviderKind.OIDC,
        label: String = "workforce"
    ): String {
        require(label.isNotEmpty()) { "Federation provider fixture label must not be empty" }
        val labelBytes = label.encodeToByteArray()
        val digest = ByteArray(32) { index ->
            (labelBytes[index % labelBytes.size].toInt() xor (index * 29)).toByte()
        }
        return try {
            val prefix = if (kind == FederationProviderKind.OIDC) "oidc." else "saml."
            prefix + Base64Url.encode(digest)
        } finally {
            digest.fill(0)
            labelBytes.fill(0)
        }
    }

    fun federationProviderControl(
        organizationId: OrganizationId = organizationId(),
        kind: FederationProviderKind = FederationProviderKind.OIDC,
        providerId: String = "workforce",
        storageKey: String = federationProviderStorageKey(kind, providerId),
        state: FederationProviderState = FederationProviderState.ENABLED,
        sessionEpoch: Long = if (state == FederationProviderState.ENABLED) 0 else 1,
        version: Long = 0,
        createdAt: Instant = instant(),
        updatedAt: Instant = if (state == FederationProviderState.ENABLED) createdAt else instant(1_000),
        disabledReasonCode: String = "fixture_provider_disabled"
    ): FederationProviderControl = FederationProviderControl(
        organizationId = organizationId,
        kind = kind,
        providerId = providerId,
        storageKey = storageKey,
        state = state,
        sessionEpoch = sessionEpoch,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
        disabledAt = updatedAt.takeIf { state == FederationProviderState.DISABLED },
        disabledReasonCode = disabledReasonCode.takeIf { state == FederationProviderState.DISABLED }
    )

    fun federationProviderLease(
        control: FederationProviderControl = federationProviderControl()
    ): FederationProviderLease {
        require(control.state == FederationProviderState.ENABLED) {
            "Only an enabled federation provider fixture can issue a lease"
        }
        return FederationProviderLease(
            organizationId = control.organizationId,
            kind = control.kind,
            providerId = control.providerId,
            storageKey = control.storageKey,
            sessionEpoch = control.sessionEpoch,
            version = control.version
        )
    }

    fun user(
        id: UserId = userId(),
        state: UserState = UserState.ACTIVE,
        version: Long = 0,
        sessionEpoch: Long = 0
    ): User = User(
        id = id,
        state = state,
        displayName = "Test User ${id.value}",
        primaryEmail = EmailAddress("${id.value}@example.test"),
        sessionEpoch = sessionEpoch,
        version = version,
        createdAt = instant(),
        updatedAt = instant(),
        activatedAt = if (state == UserState.ACTIVE) instant() else null,
        deactivatedAt = if (state == UserState.DEACTIVATED) instant(1_000) else null
    )

    fun credential(
        id: CredentialId = credentialId(),
        webAuthnId: WebAuthnCredentialId = WebAuthnCredentialId(
            Base64Url.encode(id.value.encodeToByteArray())
        ),
        userId: UserId = userId(),
        state: CredentialState = CredentialState.ACTIVE,
        version: Long = 0,
        signCount: Long = 0
    ): Credential = Credential(
        id = id,
        webAuthnId = webAuthnId,
        userId = userId,
        name = "Test passkey",
        publicKey = CosePublicKey("cose-${id.value}"),
        signCount = signCount,
        backupEligible = false,
        backedUp = false,
        discoverable = true,
        state = state,
        version = version,
        createdAt = instant(),
        updatedAt = instant(),
        revokedAt = if (state == CredentialState.REVOKED) instant(1_000) else null,
        revocationReasonCode = if (state == CredentialState.REVOKED) "test_revocation" else null
    )

    fun session(
        id: SessionId = sessionId(),
        familyId: SessionId = id,
        userId: UserId = userId(),
        state: SessionState = SessionState.ACTIVE,
        version: Long = 0,
        userSessionEpoch: Long = 0,
        assurance: AuthenticationAssurance = AuthenticationAssurance.PASSKEY,
        authenticationMethod: SessionAuthenticationMethod = when (assurance) {
            AuthenticationAssurance.RECOVERY -> SessionAuthenticationMethod.RECOVERY_CODE
            else -> SessionAuthenticationMethod.PASSKEY
        },
        federationOrganizationId: OrganizationId? = null,
        federationProviderKey: String? = null,
        federationProviderSessionEpoch: Long? = if (federationProviderKey == null) null else 0,
        externalIdentityId: ExternalIdentityId? = null,
        rotationCounter: Long = 0,
        createdAt: Instant = instant(),
        rotatedFromId: SessionId? = null,
        rotatedToId: SessionId? = if (state == SessionState.ROTATED) sessionId(2) else null
    ): IdentitySession = IdentitySession(
        id = id,
        familyId = familyId,
        userId = userId,
        tokenDigest = digest("session-${id.value}"),
        csrfDigest = digest("csrf-${id.value}"),
        assurance = assurance,
        authenticationMethod = authenticationMethod,
        federationOrganizationId = federationOrganizationId,
        federationProviderKey = federationProviderKey,
        federationProviderSessionEpoch = federationProviderSessionEpoch,
        externalIdentityId = externalIdentityId,
        userSessionEpoch = userSessionEpoch,
        rotationCounter = rotationCounter,
        state = state,
        version = version,
        createdAt = createdAt,
        authenticatedAt = createdAt,
        lastUsedAt = createdAt,
        idleExpiresAt = Instant.fromEpochMilliseconds(createdAt.toEpochMilliseconds() + 3_600_000),
        absoluteExpiresAt = Instant.fromEpochMilliseconds(createdAt.toEpochMilliseconds() + 86_400_000),
        rotatedFromId = rotatedFromId,
        rotatedToId = rotatedToId,
        revokedAt = if (state == SessionState.REVOKED) instant(1_000) else null,
        revocationReasonCode = if (state == SessionState.REVOKED) "test_revocation" else null
    )

    fun organization(
        id: OrganizationId = organizationId(),
        slug: String = "test-org",
        state: OrganizationState = OrganizationState.ACTIVE,
        version: Long = 0
    ): Organization = Organization(
        id = id,
        name = "Test Organization",
        slug = slug,
        state = state,
        version = version,
        createdAt = instant(),
        updatedAt = instant(),
        deletedAt = if (state == OrganizationState.DELETED) instant(1_000) else null
    )

    fun membership(
        id: MembershipId = membershipId(),
        organizationId: OrganizationId = organizationId(),
        userId: UserId = userId(),
        role: OrganizationRole = OrganizationRole.OWNER,
        state: MembershipState = MembershipState.ACTIVE,
        version: Long = 0
    ): Membership = Membership(
        id = id,
        organizationId = organizationId,
        userId = userId,
        role = role,
        state = state,
        version = version,
        createdAt = instant(),
        updatedAt = instant(),
        removedAt = if (state == MembershipState.REMOVED) instant(1_000) else null
    )

    fun invitation(
        id: InvitationId = invitationId(),
        organizationId: OrganizationId = organizationId(),
        state: InvitationState = InvitationState.PENDING,
        version: Long = 0
    ): Invitation = Invitation(
        id = id,
        organizationId = organizationId,
        email = EmailAddress("invitee@example.test"),
        role = OrganizationRole.VIEWER,
        tokenDigest = digest("invitation-${id.value}"),
        state = state,
        version = version,
        createdAt = instant(),
        expiresAt = instant(86_400_000),
        acceptedAt = if (state == InvitationState.ACCEPTED) instant(1_000) else null,
        acceptedByUserId = if (state == InvitationState.ACCEPTED) userId(2) else null,
        revokedAt = if (state == InvitationState.REVOKED) instant(1_000) else null
    )

    fun serviceIdentity(
        id: ServiceIdentityId = serviceIdentityId(),
        organizationId: OrganizationId = organizationId(),
        state: ServiceIdentityState = ServiceIdentityState.ACTIVE,
        version: Long = 0
    ): ServiceIdentity = ServiceIdentity(
        id = id,
        organizationId = organizationId,
        name = "Test Service",
        capabilities = setOf(Capability.CONTENT_READ),
        state = state,
        version = version,
        createdAt = instant(),
        updatedAt = instant(),
        revokedAt = if (state == ServiceIdentityState.REVOKED) instant(1_000) else null
    )

    fun serviceCredential(
        id: ServiceCredentialId = serviceCredentialId(),
        serviceIdentityId: ServiceIdentityId = serviceIdentityId(),
        state: ServiceCredentialState = ServiceCredentialState.ACTIVE,
        version: Long = 0,
        rotatedToId: ServiceCredentialId? = if (state == ServiceCredentialState.ROTATED) {
            serviceCredentialId(2)
        } else null
    ): ServiceCredential = ServiceCredential(
        id = id,
        serviceIdentityId = serviceIdentityId,
        publicPrefix = "prefix_${id.value}",
        secretDigest = digest("service-credential-${id.value}"),
        capabilities = setOf(Capability.CONTENT_READ),
        state = state,
        version = version,
        createdAt = instant(),
        expiresAt = instant(86_400_000),
        rotatedToId = rotatedToId,
        rotatedAt = if (state == ServiceCredentialState.ROTATED) instant(1_000) else null,
        revokedAt = if (state == ServiceCredentialState.REVOKED) instant(1_000) else null
    )

    fun deviceGrant(
        id: DeviceGrantId = deviceGrantId(),
        state: DeviceGrantState = DeviceGrantState.PENDING,
        version: Long = 0
    ): DeviceGrant {
        val authorized = state == DeviceGrantState.AUTHORIZED || state == DeviceGrantState.CONSUMED
        return DeviceGrant(
            id = id,
            deviceCodeDigest = digest("device-${id.value}"),
            userCodeDigest = digest("user-code-${id.value}"),
            clientId = "aether-test-client",
            clientName = "Test CLI",
            requestedCapabilities = setOf(Capability.CONTENT_READ),
            approvedCapabilities = if (authorized) setOf(Capability.CONTENT_READ) else emptySet(),
            state = state,
            userId = if (authorized) userId() else null,
            organizationId = if (authorized) organizationId() else null,
            membershipId = if (authorized) membershipId() else null,
            membershipVersion = if (authorized) 0 else null,
            authorizedByUserId = if (authorized) userId() else null,
            version = version,
            createdAt = instant(),
            expiresAt = instant(600_000),
            authorizedAt = if (authorized) instant(1_000) else null,
            deniedAt = if (state == DeviceGrantState.DENIED) instant(1_000) else null,
            consumedAt = if (state == DeviceGrantState.CONSUMED) instant(2_000) else null,
            expiredAt = if (state == DeviceGrantState.EXPIRED) instant(1_000) else null,
            cancelledAt = if (state == DeviceGrantState.CANCELLED) instant(1_000) else null
        )
    }

    fun challenge(
        id: ChallengeId = challengeId(),
        purpose: ChallengePurpose = ChallengePurpose.WEBAUTHN_AUTHENTICATION,
        userId: UserId? = userId(),
        organizationId: OrganizationId? = null,
        federationProviderLease: FederationProviderLease? = null,
        state: ChallengeState = ChallengeState.PENDING,
        version: Long = 0
    ): Challenge = Challenge(
        id = id,
        purpose = purpose,
        challengeDigest = digest("challenge-${id.value}"),
        bindingDigest = digest("binding-${id.value}"),
        userId = userId,
        organizationId = organizationId,
        federationProviderLease = federationProviderLease,
        state = state,
        version = version,
        createdAt = instant(),
        expiresAt = instant(300_000),
        consumedAt = if (state == ChallengeState.PENDING) null else instant(1_000)
    )

    fun recoveryCode(
        id: RecoveryCodeId = recoveryCodeId(),
        userId: UserId = userId(),
        state: RecoveryCodeState = RecoveryCodeState.ACTIVE,
        version: Long = 0,
        generation: Long = 0
    ): RecoveryCode = RecoveryCode(
        id = id,
        userId = userId,
        generation = generation,
        publicSelector = "selector_${id.value}",
        secretDigest = digest("recovery-${id.value}"),
        state = state,
        version = version,
        createdAt = instant(),
        consumedAt = if (state == RecoveryCodeState.CONSUMED || state == RecoveryCodeState.EXPIRED) {
            instant(1_000)
        } else null
    )

    fun externalIdentity(
        id: ExternalIdentityId = externalIdentityId(),
        userId: UserId = userId(),
        provider: String = federationProviderStorageKey(),
        subject: ExternalSubject = ExternalSubject("subject-1"),
        state: ExternalIdentityState = ExternalIdentityState.ACTIVE,
        version: Long = 0
    ): ExternalIdentity = ExternalIdentity(
        id = id,
        userId = userId,
        provider = provider,
        subject = subject,
        email = EmailAddress("external@example.test"),
        state = state,
        version = version,
        createdAt = instant(),
        updatedAt = instant(),
        unlinkedAt = if (state == ExternalIdentityState.UNLINKED) instant(1_000) else null
    )

    fun replayReceipt(
        id: ExternalReplayReceiptId = replayReceiptId(),
        provider: String = federationProviderStorageKey()
    ): ExternalIdentityReplayReceipt = ExternalIdentityReplayReceipt(
        id = id,
        provider = provider,
        assertionDigest = digest("assertion-${id.value}"),
        receivedAt = instant(),
        expiresAt = instant(600_000)
    )

    fun auditEvent(
        id: AuditEventId = auditEventId(),
        action: AuditAction = AuditAction.USER_CREATED,
        targetId: String = userId().value
    ): AuditEvent = AuditEvent(
        id = id,
        actor = AuditActor(AuditActorType.SYSTEM),
        action = action,
        target = AuditTarget(AuditTargetType.USER, targetId),
        outcome = AuditOutcome.SUCCEEDED,
        occurredAt = instant()
    )

    fun webAuthnStoreRejectionAudit(
        challengeId: ChallengeId,
        occurredAt: Instant = instant(),
        id: AuditEventId = auditEventId("webauthn-rejection-${challengeId.value}")
    ): AuditEvent = AuditEvent(
        id = id,
        actor = AuditActor(AuditActorType.SYSTEM),
        action = AuditAction.WEBAUTHN_CEREMONY_REJECTED,
        target = AuditTarget(AuditTargetType.CHALLENGE, challengeId.value),
        outcome = AuditOutcome.DENIED,
        reasonCode = WEBAUTHN_STORE_REJECTION_REASON_CODE,
        occurredAt = occurredAt
    )

    fun scimMutation(
        operationId: ScimOperationId = scimOperationId(),
        user: User = user()
    ): ScimMutation = ScimMutation(
        operationId = operationId,
        provider = "test-scim",
        type = ScimMutationType.UPSERT_USER,
        externalSubject = ExternalSubject("scim-subject-1"),
        user = user,
        occurredAt = instant()
    )
}
