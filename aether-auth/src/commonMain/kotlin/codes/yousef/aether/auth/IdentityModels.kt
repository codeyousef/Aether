package codes.yousef.aether.auth

import kotlin.time.Instant
import kotlinx.serialization.Serializable

private fun requireVersion(version: Long) {
    require(version >= 0) { "Version must not be negative" }
}

private fun requireTimestampOrder(earlier: Instant, later: Instant, description: String) {
    require(later >= earlier) { description }
}

private fun requireOAuthClientId(clientId: String) {
    require(clientId.length in 1..200 && clientId.all { it.code in 0x21..0x7e }) {
        "OAuth client ID must be 1..200 visible ASCII characters"
    }
}

@Serializable
data class User(
    val id: UserId,
    val state: UserState,
    val displayName: String,
    val primaryEmail: EmailAddress? = null,
    val avatarUrl: String? = null,
    val locale: String? = null,
    val timeZone: String? = null,
    val sessionEpoch: Long = 0,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val activatedAt: Instant? = null,
    val deactivatedAt: Instant? = null
) {
    init {
        require(displayName.isNotBlank()) { "Display name must not be blank" }
        require(displayName.length <= 200) { "Display name is too long" }
        require(sessionEpoch >= 0) { "Session epoch must not be negative" }
        requireVersion(version)
        requireTimestampOrder(createdAt, updatedAt, "updatedAt must not precede createdAt")
        activatedAt?.let { requireTimestampOrder(createdAt, it, "activatedAt must not precede createdAt") }
        deactivatedAt?.let { requireTimestampOrder(createdAt, it, "deactivatedAt must not precede createdAt") }
    }
}

@Serializable
data class Credential(
    val id: CredentialId,
    val webAuthnId: WebAuthnCredentialId,
    val userId: UserId,
    val name: String,
    val publicKey: CosePublicKey,
    val signCount: Long,
    val transports: Set<AuthenticatorTransport> = emptySet(),
    val backupEligible: Boolean,
    val backedUp: Boolean,
    val discoverable: Boolean,
    val state: CredentialState = CredentialState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastUsedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val revocationReasonCode: String? = null
) {
    init {
        require(name.isNotBlank() && name.length <= 200) { "Credential name must be 1..200 characters" }
        require(signCount in 0..4_294_967_295L) { "WebAuthn signCount must fit an unsigned 32-bit value" }
        require(!backedUp || backupEligible) { "A backed-up credential must be backup eligible" }
        requireVersion(version)
        requireTimestampOrder(createdAt, updatedAt, "updatedAt must not precede createdAt")
        lastUsedAt?.let { requireTimestampOrder(createdAt, it, "lastUsedAt must not precede createdAt") }
        revokedAt?.let { requireTimestampOrder(createdAt, it, "revokedAt must not precede createdAt") }
        require(state != CredentialState.REVOKED || revokedAt != null) { "A revoked credential requires revokedAt" }
        require(revocationReasonCode == null || revocationReasonCode.isNotBlank()) {
            "Credential revocation reason code must not be blank"
        }
    }
}

@Serializable
data class DeviceMetadata(
    val label: String? = null,
    val platform: String? = null,
    val userAgent: String? = null,
    val clientIpDigest: PseudonymousValue? = null
) {
    init {
        require(label == null || label.length <= 200) { "Device label is too long" }
        require(platform == null || platform.length <= 200) { "Device platform is too long" }
        require(userAgent == null || userAgent.length <= 2048) { "User agent is too long" }
    }

    override fun toString(): String =
        "DeviceMetadata(label=${if (label == null) "none" else "<redacted>"}, " +
            "platform=${if (platform == null) "none" else "<redacted>"}, " +
            "userAgent=${if (userAgent == null) "none" else "<redacted>"}, clientIpDigest=$clientIpDigest)"
}

@Serializable
data class IdentitySession(
    val id: SessionId,
    val familyId: SessionId,
    val userId: UserId,
    val tokenDigest: SecretDigest,
    val csrfDigest: SecretDigest,
    val device: DeviceMetadata = DeviceMetadata(),
    val assurance: AuthenticationAssurance,
    val authenticationMethod: SessionAuthenticationMethod,
    val federationOrganizationId: OrganizationId? = null,
    val federationProviderKey: String? = null,
    val federationProviderSessionEpoch: Long? = null,
    val externalIdentityId: ExternalIdentityId? = null,
    val userSessionEpoch: Long,
    val rotationCounter: Long = 0,
    val state: SessionState = SessionState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val authenticatedAt: Instant,
    val lastUsedAt: Instant,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
    val rotatedFromId: SessionId? = null,
    val rotatedToId: SessionId? = null,
    val revokedAt: Instant? = null,
    val revocationReasonCode: String? = null
) {
    init {
        require(assurance != AuthenticationAssurance.ANONYMOUS) { "An identity session must be authenticated" }
        require(assurance != AuthenticationAssurance.SERVICE_CREDENTIAL) { "Service credentials do not create user sessions" }
        when (authenticationMethod) {
            SessionAuthenticationMethod.PASSKEY -> require(
                assurance == AuthenticationAssurance.PASSKEY ||
                    assurance == AuthenticationAssurance.STEP_UP
            ) { "Passkey sessions require passkey or step-up assurance" }
            SessionAuthenticationMethod.RECOVERY_CODE,
            SessionAuthenticationMethod.ADMINISTRATIVE_RECOVERY,
            SessionAuthenticationMethod.BOOTSTRAP,
            SessionAuthenticationMethod.INVITATION -> require(
                assurance == AuthenticationAssurance.RECOVERY
            ) { "Recovery sessions require recovery assurance" }
            SessionAuthenticationMethod.OIDC,
            SessionAuthenticationMethod.SAML -> require(
                assurance == AuthenticationAssurance.SESSION
            ) { "Federated sessions require session assurance until passkey step-up" }
        }
        val federationValues = listOf(
            federationOrganizationId,
            federationProviderKey,
            federationProviderSessionEpoch,
            externalIdentityId
        )
        if (authenticationMethod == SessionAuthenticationMethod.OIDC ||
            authenticationMethod == SessionAuthenticationMethod.SAML
        ) {
            require(federationValues.all { it != null }) {
                "Federated sessions require tenant, provider, and external identity provenance"
            }
            requireFederationProviderStorageKey(
                requireNotNull(authenticationMethod.federationProviderKindOrNull()),
                requireNotNull(federationProviderKey)
            )
            require(federationProviderSessionEpoch != null && federationProviderSessionEpoch >= 0) {
                "Federation provider session epoch must not be negative"
            }
        } else {
            require(federationValues.all { it == null }) {
                "Non-federated sessions must not carry federation provenance"
            }
        }
        require(userSessionEpoch >= 0) { "User session epoch must not be negative" }
        require(rotationCounter >= 0) { "Rotation counter must not be negative" }
        requireVersion(version)
        requireTimestampOrder(createdAt, authenticatedAt, "authenticatedAt must not precede createdAt")
        requireTimestampOrder(createdAt, lastUsedAt, "lastUsedAt must not precede createdAt")
        requireTimestampOrder(lastUsedAt, idleExpiresAt, "idleExpiresAt must follow lastUsedAt")
        requireTimestampOrder(createdAt, absoluteExpiresAt, "absoluteExpiresAt must follow createdAt")
        require(idleExpiresAt <= absoluteExpiresAt) { "Idle expiration must not exceed absolute expiration" }
        revokedAt?.let { requireTimestampOrder(createdAt, it, "revokedAt must not precede createdAt") }
        require(state != SessionState.ROTATED || rotatedToId != null) { "A rotated session requires rotatedToId" }
        require(state != SessionState.REVOKED || revokedAt != null) { "A revoked session requires revokedAt" }
        require(revocationReasonCode == null || revocationReasonCode.isNotBlank()) {
            "Session revocation reason code must not be blank"
        }
    }
}

@Serializable
data class Organization(
    val id: OrganizationId,
    val name: String,
    val slug: String,
    val state: OrganizationState = OrganizationState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
) {
    init {
        require(name.isNotBlank() && name.length <= 200) { "Organization name must be 1..200 characters" }
        require(Regex("[a-z0-9][a-z0-9-]{1,62}").matches(slug)) { "Invalid organization slug" }
        requireVersion(version)
        requireTimestampOrder(createdAt, updatedAt, "updatedAt must not precede createdAt")
        deletedAt?.let { requireTimestampOrder(createdAt, it, "deletedAt must not precede createdAt") }
        require(state != OrganizationState.DELETED || deletedAt != null) { "A deleted organization requires deletedAt" }
    }
}

@Serializable
data class Membership(
    val id: MembershipId,
    val organizationId: OrganizationId,
    val userId: UserId,
    val role: OrganizationRole,
    val state: MembershipState = MembershipState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val removedAt: Instant? = null
) {
    init {
        requireVersion(version)
        requireTimestampOrder(createdAt, updatedAt, "updatedAt must not precede createdAt")
        removedAt?.let { requireTimestampOrder(createdAt, it, "removedAt must not precede createdAt") }
        require(state != MembershipState.REMOVED || removedAt != null) { "A removed membership requires removedAt" }
    }
}

@Serializable
data class Invitation(
    val id: InvitationId,
    val organizationId: OrganizationId,
    val email: EmailAddress,
    val role: OrganizationRole,
    val tokenDigest: SecretDigest,
    val invitedByUserId: UserId? = null,
    val state: InvitationState = InvitationState.PENDING,
    val version: Long = 0,
    val createdAt: Instant,
    val expiresAt: Instant,
    val acceptedAt: Instant? = null,
    val acceptedByUserId: UserId? = null,
    val revokedAt: Instant? = null
) {
    init {
        requireVersion(version)
        require(expiresAt > createdAt) { "Invitation expiration must follow creation" }
        acceptedAt?.let { requireTimestampOrder(createdAt, it, "acceptedAt must not precede createdAt") }
        revokedAt?.let { requireTimestampOrder(createdAt, it, "revokedAt must not precede createdAt") }
        require((acceptedAt == null) == (acceptedByUserId == null)) {
            "acceptedAt and acceptedByUserId must either both be set or both be absent"
        }
        require(state != InvitationState.ACCEPTED || acceptedAt != null) { "An accepted invitation requires acceptance metadata" }
        require(state != InvitationState.REVOKED || revokedAt != null) { "A revoked invitation requires revokedAt" }
    }
}

@Serializable
data class ServiceIdentity(
    val id: ServiceIdentityId,
    val organizationId: OrganizationId,
    val name: String,
    val description: String? = null,
    val capabilities: Set<Capability>,
    val state: ServiceIdentityState = ServiceIdentityState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revokedAt: Instant? = null
) {
    init {
        require(name.isNotBlank() && name.length <= 200) { "Service identity name must be 1..200 characters" }
        require(description == null || description.length <= 2000) { "Service identity description is too long" }
        requireVersion(version)
        requireTimestampOrder(createdAt, updatedAt, "updatedAt must not precede createdAt")
        revokedAt?.let { requireTimestampOrder(createdAt, it, "revokedAt must not precede createdAt") }
        require(state != ServiceIdentityState.REVOKED || revokedAt != null) { "A revoked service identity requires revokedAt" }
    }
}

@Serializable
data class ServiceCredential(
    val id: ServiceCredentialId,
    val serviceIdentityId: ServiceIdentityId,
    val publicPrefix: String,
    val secretDigest: SecretDigest,
    val capabilities: Set<Capability>,
    val state: ServiceCredentialState = ServiceCredentialState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    val lastUsedAt: Instant? = null,
    val rotatedToId: ServiceCredentialId? = null,
    val rotatedAt: Instant? = null,
    val revokedAt: Instant? = null
) {
    init {
        require(Regex("[A-Za-z0-9_-]{6,64}").matches(publicPrefix)) { "Invalid service credential prefix" }
        requireVersion(version)
        expiresAt?.let { require(it > createdAt) { "Credential expiration must follow creation" } }
        lastUsedAt?.let { requireTimestampOrder(createdAt, it, "lastUsedAt must not precede createdAt") }
        rotatedAt?.let { requireTimestampOrder(createdAt, it, "rotatedAt must not precede createdAt") }
        revokedAt?.let { requireTimestampOrder(createdAt, it, "revokedAt must not precede createdAt") }
        require(state != ServiceCredentialState.ROTATED || rotatedToId != null && rotatedAt != null) {
            "A rotated service credential requires replacement and rotation time"
        }
        require(state != ServiceCredentialState.REVOKED || revokedAt != null) { "A revoked service credential requires revokedAt" }
    }
}

@Serializable
data class DeviceGrant(
    val id: DeviceGrantId,
    val deviceCodeDigest: SecretDigest,
    val userCodeDigest: SecretDigest,
    val clientId: String,
    val clientName: String,
    val requestedCapabilities: Set<Capability>,
    val approvedCapabilities: Set<Capability> = emptySet(),
    val state: DeviceGrantState = DeviceGrantState.PENDING,
    val userId: UserId? = null,
    val organizationId: OrganizationId? = null,
    /** Membership snapshot which authorized this tenant-scoped grant. */
    val membershipId: MembershipId? = null,
    val membershipVersion: Long? = null,
    val authorizedByUserId: UserId? = null,
    val version: Long = 0,
    val createdAt: Instant,
    val expiresAt: Instant,
    val pollingIntervalSeconds: Int = 5,
    val pollCount: Int = 0,
    val lastPolledAt: Instant? = null,
    val authorizedAt: Instant? = null,
    val deniedAt: Instant? = null,
    val consumedAt: Instant? = null,
    val expiredAt: Instant? = null,
    val cancelledAt: Instant? = null
) {
    init {
        requireOAuthClientId(clientId)
        require(clientName.isNotBlank() && clientName.length <= 200) { "Device client name must be 1..200 characters" }
        require(requestedCapabilities.isNotEmpty()) { "A device grant must request at least one capability" }
        require(requestedCapabilities.containsAll(approvedCapabilities)) {
            "Approved device capabilities must be a subset of requested capabilities"
        }
        require(pollingIntervalSeconds in 5..300) { "Device polling interval must be 5..300 seconds" }
        require(pollCount >= 0) { "Device poll count must not be negative" }
        requireVersion(version)
        require(expiresAt > createdAt) { "Device grant expiration must follow creation" }
        lastPolledAt?.let { requireTimestampOrder(createdAt, it, "lastPolledAt must not precede createdAt") }
        authorizedAt?.let { requireTimestampOrder(createdAt, it, "authorizedAt must not precede createdAt") }
        deniedAt?.let { requireTimestampOrder(createdAt, it, "deniedAt must not precede createdAt") }
        consumedAt?.let { requireTimestampOrder(createdAt, it, "consumedAt must not precede createdAt") }
        expiredAt?.let { requireTimestampOrder(createdAt, it, "expiredAt must not precede createdAt") }
        cancelledAt?.let { requireTimestampOrder(createdAt, it, "cancelledAt must not precede createdAt") }
        if (state == DeviceGrantState.AUTHORIZED || state == DeviceGrantState.CONSUMED) {
            require(userId != null && organizationId != null && membershipId != null &&
                membershipVersion != null && authorizedByUserId != null && authorizedAt != null
            ) {
                "An authorized device grant requires user, organization, membership, approver, and timestamp"
            }
            require(membershipVersion >= 0) { "Authorized membership version must not be negative" }
            require(approvedCapabilities.isNotEmpty()) { "An authorized device grant requires approved capabilities" }
        }
        if (state == DeviceGrantState.PENDING) {
            require(approvedCapabilities.isEmpty()) { "A pending device grant cannot have approved capabilities" }
            require(userId == null && organizationId == null && membershipId == null &&
                membershipVersion == null && authorizedByUserId == null && authorizedAt == null
            ) { "A pending device grant cannot carry authorization bindings" }
        }
        require(state != DeviceGrantState.DENIED || deniedAt != null) { "A denied device grant requires deniedAt" }
        require(state != DeviceGrantState.CONSUMED || consumedAt != null) { "A consumed device grant requires consumedAt" }
        require(state != DeviceGrantState.EXPIRED || expiredAt != null) { "An expired device grant requires expiredAt" }
        require(state != DeviceGrantState.CANCELLED || cancelledAt != null) { "A cancelled device grant requires cancelledAt" }
    }
}

@Serializable
data class DeviceTokenFamily(
    val id: DeviceTokenFamilyId,
    val deviceGrantId: DeviceGrantId,
    val clientId: String,
    val userId: UserId,
    val organizationId: OrganizationId,
    /** Exact active membership snapshot from which this family's scopes were derived. */
    val membershipId: MembershipId,
    val membershipVersion: Long,
    val capabilities: Set<Capability>,
    val state: DeviceTokenFamilyState = DeviceTokenFamilyState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val revocationReasonCode: String? = null
) {
    init {
        requireOAuthClientId(clientId)
        require(membershipVersion >= 0) { "Device token membership version must not be negative" }
        require(capabilities.isNotEmpty()) { "A device token family requires at least one capability" }
        requireVersion(version)
        require(expiresAt > createdAt) { "Device token family expiration must follow creation" }
        revokedAt?.let { requireTimestampOrder(createdAt, it, "revokedAt must not precede createdAt") }
        require(state != DeviceTokenFamilyState.REVOKED || revokedAt != null) {
            "A revoked device token family requires revokedAt"
        }
        require(revocationReasonCode == null || revocationReasonCode.isNotBlank()) {
            "Device token family revocation reason must not be blank"
        }
    }
}

@Serializable
data class DeviceAccessToken(
    val id: DeviceAccessTokenId,
    val familyId: DeviceTokenFamilyId,
    val publicSelector: String,
    val secretDigest: SecretDigest,
    val state: DeviceAccessTokenState = DeviceAccessTokenState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant? = null
) {
    init {
        require(Regex("[A-Za-z0-9_-]{6,64}").matches(publicSelector)) { "Invalid access-token selector" }
        requireVersion(version)
        require(expiresAt > createdAt) { "Access-token expiration must follow creation" }
        revokedAt?.let { requireTimestampOrder(createdAt, it, "revokedAt must not precede createdAt") }
        require(state != DeviceAccessTokenState.REVOKED || revokedAt != null) {
            "A revoked access token requires revokedAt"
        }
    }
}

@Serializable
data class DeviceRefreshToken(
    val id: DeviceRefreshTokenId,
    val familyId: DeviceTokenFamilyId,
    val publicSelector: String,
    val secretDigest: SecretDigest,
    val rotationCounter: Long,
    val state: DeviceRefreshTokenState = DeviceRefreshTokenState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val expiresAt: Instant,
    val rotatedToId: DeviceRefreshTokenId? = null,
    val consumedAt: Instant? = null,
    val revokedAt: Instant? = null
) {
    init {
        require(Regex("[A-Za-z0-9_-]{6,64}").matches(publicSelector)) { "Invalid refresh-token selector" }
        require(rotationCounter >= 0) { "Refresh-token rotation counter must not be negative" }
        requireVersion(version)
        require(expiresAt > createdAt) { "Refresh-token expiration must follow creation" }
        consumedAt?.let { requireTimestampOrder(createdAt, it, "consumedAt must not precede createdAt") }
        revokedAt?.let { requireTimestampOrder(createdAt, it, "revokedAt must not precede createdAt") }
        require(state != DeviceRefreshTokenState.ROTATED || rotatedToId != null && consumedAt != null) {
            "A rotated refresh token requires replacement and consumption metadata"
        }
        require(state != DeviceRefreshTokenState.REVOKED || revokedAt != null) {
            "A revoked refresh token requires revokedAt"
        }
    }
}

@Serializable
data class Challenge(
    val id: ChallengeId,
    val purpose: ChallengePurpose,
    val challengeDigest: SecretDigest,
    val bindingDigest: SecretDigest,
    val payloadDigest: SecretDigest? = null,
    val userId: UserId? = null,
    val organizationId: OrganizationId? = null,
    val federationProviderLease: FederationProviderLease? = null,
    val state: ChallengeState = ChallengeState.PENDING,
    val attemptCount: Int = 0,
    val version: Long = 0,
    val createdAt: Instant,
    val expiresAt: Instant,
    val activatedAt: Instant? = null,
    val consumedAt: Instant? = null
) {
    init {
        if (purpose == ChallengePurpose.EXTERNAL_IDENTITY_LINK) {
            require(federationProviderLease != null && organizationId == federationProviderLease.organizationId) {
                "An external identity challenge requires its tenant provider lease"
            }
        } else {
            require(federationProviderLease == null) {
                "Only external identity challenges may carry a federation provider lease"
            }
        }
        require(attemptCount >= 0) { "Challenge attempt count must not be negative" }
        requireVersion(version)
        require(expiresAt > createdAt) { "Challenge expiration must follow creation" }
        activatedAt?.let {
            require(purpose == ChallengePurpose.ACCOUNT_RECOVERY) {
                "Only an administrative recovery challenge has a separate activation step"
            }
            requireTimestampOrder(createdAt, it, "activatedAt must not precede createdAt")
            require(it < expiresAt) { "Challenge activation must precede expiration" }
        }
        consumedAt?.let { requireTimestampOrder(createdAt, it, "consumedAt must not precede createdAt") }
        require(state !in setOf(ChallengeState.CONSUMED, ChallengeState.FAILED, ChallengeState.EXPIRED) || consumedAt != null) {
            "A terminal challenge requires consumedAt"
        }
    }
}

@Serializable
data class RecoveryCode(
    val id: RecoveryCodeId,
    val userId: UserId,
    val generation: Long,
    val publicSelector: String,
    val secretDigest: SecretDigest,
    val state: RecoveryCodeState = RecoveryCodeState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    val consumedAt: Instant? = null
) {
    init {
        require(generation >= 0) { "Recovery generation must not be negative" }
        require(Regex("[A-Za-z0-9_-]{6,64}").matches(publicSelector)) { "Invalid recovery-code selector" }
        requireVersion(version)
        expiresAt?.let { require(it > createdAt) { "Recovery-code expiration must follow creation" } }
        consumedAt?.let { requireTimestampOrder(createdAt, it, "consumedAt must not precede createdAt") }
        require(state != RecoveryCodeState.CONSUMED || consumedAt != null) {
            "A consumed recovery code requires consumedAt"
        }
    }
}

@Serializable
data class ExternalIdentity(
    val id: ExternalIdentityId,
    val userId: UserId,
    val provider: String,
    val subject: ExternalSubject,
    val email: EmailAddress? = null,
    val state: ExternalIdentityState = ExternalIdentityState.ACTIVE,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastAuthenticatedAt: Instant? = null,
    val unlinkedAt: Instant? = null
) {
    init {
        require(provider.isNotBlank() && provider.length <= 200) { "External identity provider must be 1..200 characters" }
        requireVersion(version)
        requireTimestampOrder(createdAt, updatedAt, "updatedAt must not precede createdAt")
        lastAuthenticatedAt?.let { requireTimestampOrder(createdAt, it, "lastAuthenticatedAt must not precede createdAt") }
        unlinkedAt?.let { requireTimestampOrder(createdAt, it, "unlinkedAt must not precede createdAt") }
        require(state != ExternalIdentityState.UNLINKED || unlinkedAt != null) { "An unlinked identity requires unlinkedAt" }
    }
}

@Serializable
data class ExternalIdentityReplayReceipt(
    val id: ExternalReplayReceiptId,
    val provider: String,
    val assertionDigest: SecretDigest,
    val receivedAt: Instant,
    val expiresAt: Instant
) {
    init {
        require(provider.isNotBlank()) { "External identity provider must not be blank" }
        require(expiresAt > receivedAt) { "Replay receipt expiration must follow receipt" }
    }
}

@Serializable
data class AuditActor(
    val type: AuditActorType,
    val userId: UserId? = null,
    val serviceIdentityId: ServiceIdentityId? = null
) {
    init {
        when (type) {
            AuditActorType.USER -> require(userId != null && serviceIdentityId == null) { "A user actor requires only userId" }
            AuditActorType.SERVICE -> require(serviceIdentityId != null && userId == null) {
                "A service actor requires only serviceIdentityId"
            }
            AuditActorType.ANONYMOUS, AuditActorType.SYSTEM -> require(userId == null && serviceIdentityId == null) {
                "Anonymous and system actors must not contain a subject ID"
            }
        }
    }
}

@Serializable
data class AuditTarget(
    val type: AuditTargetType,
    val id: String
) {
    init { requireValidIdentityId(id, "AuditTarget.id") }
}

@Serializable
data class AuditRequestMetadata(
    val requestId: String,
    val method: String,
    val path: String,
    /** Keyed pseudonym only. The raw User-Agent value is not a valid audit-model value. */
    val userAgent: PseudonymousValue? = null,
    val clientIpDigest: PseudonymousValue? = null,
    val trustedProxy: Boolean = false
) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 255) { "Invalid request ID" }
        require(method.isNotBlank() && method.length <= 16) { "Invalid HTTP method" }
        require(path.startsWith('/') && path.length <= 4096) { "Invalid request path" }
    }

    override fun toString(): String =
        "AuditRequestMetadata(requestId=$requestId, method=$method, path=$path, userAgent=${if (userAgent == null) "none" else "<redacted>"}, clientIpDigest=$clientIpDigest, trustedProxy=$trustedProxy)"
}

@Serializable
data class AuditEvent(
    val id: AuditEventId,
    val actor: AuditActor,
    val organizationId: OrganizationId? = null,
    val action: AuditAction,
    val target: AuditTarget? = null,
    val outcome: AuditOutcome,
    val reasonCode: String? = null,
    val request: AuditRequestMetadata? = null,
    val occurredAt: Instant
) {
    init {
        require(reasonCode == null || (reasonCode.isNotBlank() && reasonCode.length <= 200)) {
            "Audit reason code must be absent or 1..200 characters"
        }
    }
}

/** Storage-neutral, idempotent change received from a SCIM adapter. */
@Serializable
data class ScimMutation(
    val operationId: ScimOperationId,
    val provider: String,
    val type: ScimMutationType,
    val externalSubject: ExternalSubject,
    val user: User? = null,
    val membership: Membership? = null,
    val occurredAt: Instant
) {
    init {
        require(provider.isNotBlank()) { "SCIM provider must not be blank" }
        when (type) {
            ScimMutationType.UPSERT_USER, ScimMutationType.DEACTIVATE_USER -> require(user != null) {
                "$type requires a user"
            }
            ScimMutationType.UPSERT_MEMBERSHIP, ScimMutationType.REMOVE_MEMBERSHIP -> require(membership != null) {
                "$type requires a membership"
            }
        }
    }
}

/**
 * Canonical tenant-scoped SCIM Group state kept with identity mutations.
 *
 * The optional SCIM module may keep a richer protocol projection, but this aggregate is the
 * storage-neutral identity boundary used to make a Group role change and every affected
 * membership one atomic operation.
 */
@Serializable
data class ScimGroup(
    val id: String,
    val organizationId: OrganizationId,
    val provider: String,
    val externalId: String? = null,
    val displayName: String,
    val memberUserIds: Set<UserId> = emptySet(),
    val state: ScimGroupState = ScimGroupState.ACTIVE,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
) {
    init {
        require(Regex("[A-Za-z0-9_-][A-Za-z0-9._:-]{0,254}").matches(id)) { "Invalid SCIM Group ID" }
        require(provider.isNotBlank() && provider.length <= 512) { "SCIM provider key must be bounded" }
        require(externalId == null || (externalId.isNotBlank() && externalId.length <= 1_024)) {
            "SCIM Group external ID must be bounded"
        }
        require(displayName.isNotBlank() && displayName.length <= 200) { "SCIM Group display name must be bounded" }
        require(memberUserIds.size <= 5_000) { "SCIM Group has too many members" }
        require(version >= 1) { "SCIM Group version must be positive" }
        require(updatedAt >= createdAt) { "SCIM Group update must not precede creation" }
        require(deletedAt == null || deletedAt >= createdAt) { "SCIM Group deletion must not precede creation" }
        require(state != ScimGroupState.DELETED || deletedAt != null) { "Deleted SCIM Group requires deletedAt" }
        require(state != ScimGroupState.ACTIVE || deletedAt == null) { "Active SCIM Group cannot have deletedAt" }
    }
}
