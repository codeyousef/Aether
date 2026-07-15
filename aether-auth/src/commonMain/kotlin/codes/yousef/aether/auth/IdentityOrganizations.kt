package codes.yousef.aether.auth

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class InvitationView(
    val id: InvitationId,
    val organizationId: OrganizationId,
    val email: EmailAddress,
    val role: OrganizationRole,
    val state: InvitationState,
    val createdAt: Instant,
    val expiresAt: Instant,
    val acceptedAt: Instant? = null,
    val revokedAt: Instant? = null
)

/** Safe organization projection including only the caller's tenant-scoped role. */
@Serializable
data class OrganizationAccessView(
    val id: OrganizationId,
    val name: String,
    val slug: String,
    val role: String
)

/** Public audit request metadata. User-agent and client-address digests remain storage-only. */
@Serializable
data class AuditRequestView(
    val requestId: String,
    val method: String,
    val path: String,
    val trustedProxy: Boolean
)

/** Explicitly safe tenant audit projection; it contains no provider payload, credential, or digest. */
@Serializable
data class AuditEventView(
    val id: AuditEventId,
    val actor: AuditActor,
    val organizationId: OrganizationId,
    val action: AuditAction,
    val target: AuditTarget? = null,
    val outcome: AuditOutcome,
    val reasonCode: String? = null,
    val request: AuditRequestView? = null,
    val occurredAt: Instant
)

data class OrganizationAuditEventPageView(
    val events: List<AuditEventView>,
    val nextCursor: OrganizationAuditEventCursor?
)

class IssuedInvitation internal constructor(
    val invitation: InvitationView,
    private val token: String
) {
    fun revealToken(): String = token
    override fun toString(): String = "IssuedInvitation(invitation=$invitation, token=<redacted>)"
}

class EnrolledInvitation internal constructor(
    val invitation: InvitationView,
    val user: User,
    val membership: Membership,
    val issuedEnrollmentSession: IssuedIdentitySession
) {
    override fun toString(): String =
        "EnrolledInvitation(invitation=${invitation.id}, user=${user.id}, " +
            "membership=${membership.id}, issuedEnrollmentSession=<redacted>)"
}

/** Tenant lifecycle and built-in RBAC policy. All tenant misses and authorization failures are indistinguishable. */
class IdentityOrganizationService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    private val capabilityResolver: CapabilityResolver = CapabilityResolver.NONE,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime),
    private val sessions: IdentitySessionIssuer = IdentitySessionIssuer(runtime, config, ids)
) {
    suspend fun createOrganization(
        actor: IdentityContext,
        name: String,
        slug: String,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Organization> {
        val userId = authenticatedUser(actor) ?: return notFound()
        val now = runtime.clock.now()
        if (!isRecentPasskey(actor, now) || name.isBlank() || name.length > 200 ||
            !Regex("[a-z0-9][a-z0-9-]{1,62}").matches(slug)
        ) return IdentityOperationResult.Failure(
            if (isRecentPasskey(actor, now)) IdentityErrorCode.REQUEST_INVALID else IdentityErrorCode.STEP_UP_REQUIRED
        )
        val organization = Organization(
            id = ids.newOrganizationId(),
            name = name,
            slug = slug,
            createdAt = now,
            updatedAt = now
        )
        val owner = Membership(
            id = ids.newMembershipId(),
            organizationId = organization.id,
            userId = userId,
            role = OrganizationRole.OWNER,
            createdAt = now,
            updatedAt = now
        )
        val audit = audit(
            actor = AuditActor(AuditActorType.USER, userId = userId),
            action = AuditAction.ORGANIZATION_CREATED,
            target = AuditTarget(AuditTargetType.ORGANIZATION, organization.id.value),
            organizationId = organization.id,
            at = now,
            request = request
        )
        return when (val result = store.createOrganization(CreateOrganizationCommand(organization, owner, audit))) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value.organization)
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun listOrganizations(actor: IdentityContext): IdentityOperationResult<List<Organization>> {
        val userId = authenticatedUser(actor) ?: return notFound()
        return when (val result = store.listOrganizationsForUser(userId)) {
            is StoreResult.Success -> IdentityOperationResult.Success(
                result.value.filterToFederationOrganization(actor.session?.federationOrganizationId)
            )
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun listOrganizationAccess(
        actor: IdentityContext
    ): IdentityOperationResult<List<OrganizationAccessView>> {
        val userId = authenticatedUser(actor) ?: return notFound()
        val organizations = when (val result = store.listOrganizationsForUser(userId)) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return operationFailure(result.error)
        }
        val views = mutableListOf<OrganizationAccessView>()
        for (organization in organizations.filterToFederationOrganization(actor.session?.federationOrganizationId)) {
            if (organization.state != OrganizationState.ACTIVE) continue
            val membership = when (val result = store.findMembershipForUser(userId, organization.id)) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> return operationFailure(result.error)
            }
            if (membership?.state == MembershipState.ACTIVE) {
                views += organization.toAccessView(membership.role.wireName)
            }
        }
        return IdentityOperationResult.Success(views.sortedBy { it.id.value })
    }

    /** Missing organizations and organizations inaccessible to the caller are intentionally identical. */
    suspend fun getOrganizationAccess(
        actor: IdentityContext,
        organizationId: OrganizationId
    ): IdentityOperationResult<OrganizationAccessView> {
        val userId = authenticatedUser(actor) ?: return notFound()
        if (actor.session?.federationOrganizationId?.let { it != organizationId } == true) {
            return notFound()
        }
        val organization = when (val result = store.findOrganization(organizationId)) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return operationFailure(result.error)
        } ?: return notFound()
        val membership = when (val result = store.findMembershipForUser(userId, organizationId)) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return operationFailure(result.error)
        } ?: return notFound()
        if (organization.state != OrganizationState.ACTIVE || membership.state != MembershipState.ACTIVE) {
            return notFound()
        }
        return IdentityOperationResult.Success(organization.toAccessView(membership.role.wireName))
    }

    suspend fun updateOrganization(
        actor: IdentityContext,
        organizationId: OrganizationId,
        name: String,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Organization> {
        if (!authorized(actor, organizationId, Capability.ORGANIZATION_UPDATE) || name.isBlank() || name.length > 200) {
            return if (name.isBlank() || name.length > 200) {
                IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
            } else notFound()
        }
        val organization = requireNotNull(actor.organization)
        val now = runtime.clock.now()
        val replacement = organization.copy(
            name = name,
            version = organization.version + 1,
            updatedAt = now
        )
        return mutateOrganization(actor, organization, replacement, AuditAction.ORGANIZATION_CHANGED, now, request)
    }

    suspend fun deleteOrganization(
        actor: IdentityContext,
        organizationId: OrganizationId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Organization> {
        val now = runtime.clock.now()
        if (!authorized(actor, organizationId, Capability.ORGANIZATION_DELETE)) return notFound()
        if (!isRecentPasskey(actor, now)) return IdentityOperationResult.Failure(IdentityErrorCode.STEP_UP_REQUIRED)
        val organization = requireNotNull(actor.organization)
        val replacement = organization.copy(
            state = OrganizationState.DELETED,
            version = organization.version + 1,
            updatedAt = now,
            deletedAt = now
        )
        return mutateOrganization(actor, organization, replacement, AuditAction.ORGANIZATION_DELETED, now, request)
    }

    suspend fun listMemberships(
        actor: IdentityContext,
        organizationId: OrganizationId
    ): IdentityOperationResult<List<Membership>> {
        if (!authorized(actor, organizationId, Capability.MEMBERSHIP_READ)) return notFound()
        return when (val result = store.listMembershipsForOrganization(organizationId)) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value)
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    /** Audit authorization failures deliberately match a missing tenant resource. */
    suspend fun listAuditEvents(
        actor: IdentityContext,
        organizationId: OrganizationId,
        cursor: OrganizationAuditEventCursor? = null,
        limit: Int = OrganizationAuditEventPageRequest.DEFAULT_LIMIT
    ): IdentityOperationResult<OrganizationAuditEventPageView> {
        if (!authorized(actor, organizationId, Capability.AUDIT_READ)) return notFound()
        val request = runCatching {
            OrganizationAuditEventPageRequest(organizationId, cursor, limit)
        }.getOrElse { return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID) }
        return when (val result = store.listAuditEventsForOrganization(request)) {
            is StoreResult.Success -> IdentityOperationResult.Success(
                OrganizationAuditEventPageView(
                    events = result.value.events.map(AuditEvent::toSafeView),
                    nextCursor = result.value.nextCursor
                )
            )
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun changeMembershipRole(
        actor: IdentityContext,
        organizationId: OrganizationId,
        membershipId: MembershipId,
        role: OrganizationRole,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Membership> = changeMembership(
        actor,
        organizationId,
        membershipId,
        role,
        MembershipState.ACTIVE,
        request
    )

    suspend fun removeMembership(
        actor: IdentityContext,
        organizationId: OrganizationId,
        membershipId: MembershipId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Membership> = changeMembership(
        actor,
        organizationId,
        membershipId,
        role = null,
        state = MembershipState.REMOVED,
        request = request
    )

    suspend fun invite(
        actor: IdentityContext,
        organizationId: OrganizationId,
        email: EmailAddress,
        role: OrganizationRole,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<IssuedInvitation> {
        if (!authorized(actor, organizationId, Capability.MEMBERSHIP_INVITE)) return notFound()
        val now = runtime.clock.now()
        if (role == OrganizationRole.OWNER && actor.membership?.role != OrganizationRole.OWNER) return notFound()
        if (role == OrganizationRole.OWNER && !isRecentPasskey(actor, now)) {
            return IdentityOperationResult.Failure(IdentityErrorCode.STEP_UP_REQUIRED)
        }
        val secret = runtime.secureRandom.nextBytes(INVITATION_SECRET_BYTES)
        require(secret.size == INVITATION_SECRET_BYTES) { "Secure random provider returned invalid invitation material" }
        return try {
            val invitation = Invitation(
                id = ids.newInvitationId(),
                organizationId = organizationId,
                email = email,
                role = role,
                tokenDigest = invitationDigest(secret, config.keys.recoveryPepper),
                invitedByUserId = authenticatedUser(actor),
                createdAt = now,
                expiresAt = now + config.lifetimes.invitation.seconds.seconds
            )
            val audit = audit(
                actor = AuditActor(AuditActorType.USER, userId = authenticatedUser(actor)),
                action = AuditAction.INVITATION_CREATED,
                target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value),
                organizationId = organizationId,
                at = now,
                request = request
            )
            when (val result = store.createInvitation(CreateInvitationCommand(invitation, audit))) {
                is StoreResult.Success -> IdentityOperationResult.Success(
                    IssuedInvitation(result.value.toView(), Base64Url.encode(secret))
                )
                is StoreResult.Failure -> operationFailure(result.error)
            }
        } finally {
            secret.fill(0)
        }
    }

    suspend fun listInvitations(
        actor: IdentityContext,
        organizationId: OrganizationId
    ): IdentityOperationResult<List<InvitationView>> {
        if (!authorized(actor, organizationId, Capability.MEMBERSHIP_READ)) return notFound()
        return when (val result = store.listInvitationsForOrganization(organizationId)) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value.map(Invitation::toView))
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun revokeInvitation(
        actor: IdentityContext,
        organizationId: OrganizationId,
        invitationId: InvitationId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<InvitationView> {
        if (!authorized(actor, organizationId, Capability.MEMBERSHIP_INVITE)) return notFound()
        val invitation = when (val found = store.findInvitation(invitationId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return operationFailure(found.error)
        } ?: return notFound()
        if (invitation.organizationId != organizationId || invitation.state != InvitationState.PENDING) return notFound()
        val now = runtime.clock.now()
        if (invitation.role == OrganizationRole.OWNER && actor.membership?.role != OrganizationRole.OWNER) return notFound()
        if (invitation.role == OrganizationRole.OWNER && !isRecentPasskey(actor, now)) {
            return IdentityOperationResult.Failure(IdentityErrorCode.STEP_UP_REQUIRED)
        }
        val replacement = invitation.copy(
            state = InvitationState.REVOKED,
            version = invitation.version + 1,
            revokedAt = now
        )
        val audit = audit(
            actor = AuditActor(AuditActorType.USER, userId = authenticatedUser(actor)),
            action = AuditAction.INVITATION_REVOKED,
            target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value),
            organizationId = organizationId,
            at = now,
            request = request
        )
        return when (val result = store.mutateInvitation(
            MutateInvitationCommand(invitation.id, invitation.version, replacement, audit)
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value.toView())
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun acceptInvitation(
        actor: IdentityContext,
        token: String,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<Membership> {
        val userId = authenticatedUser(actor) ?: return notFound()
        val secret = runCatching { Base64Url.decode(token, maximumBytes = INVITATION_SECRET_BYTES) }.getOrNull()
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        if (secret.size != INVITATION_SECRET_BYTES) {
            secret.fill(0)
            return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        }
        try {
            val invitation = findInvitation(secret)
                ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            val now = runtime.clock.now()
            if (invitation.state != InvitationState.PENDING || now >= invitation.expiresAt) {
                return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            }
            val user = when (val found = store.findUser(userId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return operationFailure(found.error)
            } ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            if (user.state != UserState.ACTIVE || user.primaryEmail?.value?.lowercase() != invitation.email.value.lowercase()) {
                return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            }
            val membership = Membership(
                id = ids.newMembershipId(),
                organizationId = invitation.organizationId,
                userId = user.id,
                role = invitation.role,
                createdAt = now,
                updatedAt = now
            )
            val audit = audit(
                actor = AuditActor(AuditActorType.USER, userId = user.id),
                action = AuditAction.INVITATION_ACCEPTED,
                target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value),
                organizationId = invitation.organizationId,
                at = now,
                request = request
            )
            return when (val result = store.createMembership(
                CreateMembershipCommand(membership, invitation.id, invitation.version, audit)
            )) {
                is StoreResult.Success -> IdentityOperationResult.Success(result.value)
                is StoreResult.Failure -> operationFailure(result.error)
            }
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Uses an invite as the only pre-authentication credential for a new user. The returned
     * recovery-assurance session is restricted by middleware to first-passkey enrollment.
     */
    suspend fun enrollInvitation(
        token: String,
        displayName: String,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<EnrolledInvitation> {
        if (displayName.isBlank() || displayName.length > 200) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        val secret = runCatching { Base64Url.decode(token, maximumBytes = INVITATION_SECRET_BYTES) }.getOrNull()
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        if (secret.size != INVITATION_SECRET_BYTES) {
            secret.fill(0)
            return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        }
        try {
            val matched = findInvitationMatch(secret)
                ?: return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            val invitation = matched.invitation
            val registrationSource = if (invitation.invitedByUserId == null) {
                IdentityRegistrationSource.INVITATION
            } else {
                IdentityRegistrationSource.ADMIN_INVITATION
            }
            if (!config.allowsRegistration(registrationSource)) {
                // Keep valid-token and deployment-policy failures indistinguishable to an
                // unauthenticated caller.
                return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            }
            val now = runtime.clock.now()
            if (invitation.state != InvitationState.PENDING || now >= invitation.expiresAt) {
                return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            }
            val user = User(
                id = ids.newUserId(),
                state = UserState.ACTIVE,
                displayName = displayName,
                primaryEmail = invitation.email,
                createdAt = now,
                updatedAt = now,
                activatedAt = now
            )
            val membership = Membership(
                id = ids.newMembershipId(),
                organizationId = invitation.organizationId,
                userId = user.id,
                role = invitation.role,
                createdAt = now,
                updatedAt = now
            )
            val issuedSession = sessions.issue(
                user = user,
                assurance = AuthenticationAssurance.RECOVERY,
                authenticationMethod = SessionAuthenticationMethod.INVITATION,
                authenticatedAt = now,
                absoluteLifetime = INVITATION_ENROLLMENT_LIFETIME,
                idleLifetime = INVITATION_ENROLLMENT_LIFETIME
            )
            val audit = audit(
                actor = AuditActor(AuditActorType.USER, userId = user.id),
                action = AuditAction.INVITATION_ACCEPTED,
                target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value),
                organizationId = invitation.organizationId,
                at = now,
                request = request
            )
            return when (val result = store.enrollInvitation(
                EnrollInvitationCommand(
                    invitationId = invitation.id,
                    expectedInvitationVersion = invitation.version,
                    expectedTokenDigest = matched.digest,
                    user = user,
                    membership = membership,
                    enrollmentSession = issuedSession.session,
                    enrolledAt = now,
                    auditEvent = audit
                )
            )) {
                is StoreResult.Success -> IdentityOperationResult.Success(
                    EnrolledInvitation(
                        invitation = result.value.invitation.toView(),
                        user = result.value.user,
                        membership = result.value.membership,
                        issuedEnrollmentSession = issuedSession
                    )
                )
                is StoreResult.Failure -> IdentityOperationResult.Failure(when (result.error.code) {
                    IdentityStoreErrorCode.UNAVAILABLE,
                    IdentityStoreErrorCode.INTERNAL -> IdentityErrorCode.SERVICE_UNAVAILABLE
                    else -> IdentityErrorCode.INVALID_CREDENTIALS
                })
            }
        } finally {
            secret.fill(0)
        }
    }

    private suspend fun changeMembership(
        actor: IdentityContext,
        organizationId: OrganizationId,
        membershipId: MembershipId,
        role: OrganizationRole?,
        state: MembershipState,
        request: AuditRequestMetadata?
    ): IdentityOperationResult<Membership> {
        val required = if (state == MembershipState.REMOVED) Capability.MEMBERSHIP_REMOVE else Capability.MEMBERSHIP_UPDATE
        if (!authorized(actor, organizationId, required)) return notFound()
        val target = when (val found = store.findMembership(membershipId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return operationFailure(found.error)
        } ?: return notFound()
        if (target.organizationId != organizationId || target.state == MembershipState.REMOVED) return notFound()
        val actorRole = requireNotNull(actor.membership).role
        val desiredRole = role ?: target.role
        val ownerChange = target.role == OrganizationRole.OWNER || desiredRole == OrganizationRole.OWNER
        if (ownerChange && actorRole != OrganizationRole.OWNER) return notFound()
        if (!ownerChange && actorRole !in setOf(OrganizationRole.OWNER, OrganizationRole.ADMIN)) return notFound()
        val now = runtime.clock.now()
        if ((ownerChange || state == MembershipState.REMOVED) && !isRecentPasskey(actor, now)) {
            return IdentityOperationResult.Failure(IdentityErrorCode.STEP_UP_REQUIRED)
        }
        if (target.role == desiredRole && target.state == state) return IdentityOperationResult.Success(target)
        val user = when (val found = store.findUser(target.userId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return operationFailure(found.error)
        } ?: return notFound()
        val replacement = target.copy(
            role = desiredRole,
            state = state,
            version = target.version + 1,
            updatedAt = now,
            removedAt = if (state == MembershipState.REMOVED) now else null
        )
        val audit = audit(
            actor = AuditActor(AuditActorType.USER, userId = authenticatedUser(actor)),
            action = AuditAction.MEMBERSHIP_CHANGED,
            target = AuditTarget(AuditTargetType.MEMBERSHIP, target.id.value),
            organizationId = organizationId,
            at = now,
            request = request,
            reasonCode = if (state == MembershipState.REMOVED) "membership_removed" else "membership_role_changed"
        )
        return when (val result = store.mutateMembership(
            MutateMembershipCommand(
                membershipId = target.id,
                expectedVersion = target.version,
                replacement = replacement,
                auditEvent = audit,
                expectedUserVersion = user.version,
                expectedSessionEpoch = user.sessionEpoch,
                newSessionEpoch = user.sessionEpoch + 1,
                sessionsRevokedAt = now,
                sessionRevocationReasonCode = "organization_privilege_changed"
            )
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value)
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    private suspend fun mutateOrganization(
        actor: IdentityContext,
        existing: Organization,
        replacement: Organization,
        action: AuditAction,
        now: Instant,
        request: AuditRequestMetadata?
    ): IdentityOperationResult<Organization> {
        val audit = audit(
            actor = AuditActor(AuditActorType.USER, userId = authenticatedUser(actor)),
            action = action,
            target = AuditTarget(AuditTargetType.ORGANIZATION, existing.id.value),
            organizationId = existing.id,
            at = now,
            request = request
        )
        return when (val result = store.mutateOrganization(
            MutateOrganizationCommand(existing.id, existing.version, replacement, audit)
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value)
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    private suspend fun findInvitation(secret: ByteArray): Invitation? =
        findInvitationMatch(secret)?.invitation

    private suspend fun findInvitationMatch(secret: ByteArray): MatchedInvitation? {
        val references = listOf(config.keys.recoveryPepper) + config.keys.previousRecoveryPeppers
        for (reference in references) {
            val digest = invitationDigest(secret, reference)
            when (val found = store.findInvitationByTokenDigest(digest)) {
                is StoreResult.Success -> found.value?.let { return MatchedInvitation(it, digest) }
                is StoreResult.Failure -> return null
            }
        }
        return null
    }

    private suspend fun invitationDigest(secret: ByteArray, reference: SecretReference): SecretDigest {
        val input = INVITATION_DIGEST_CONTEXT.encodeToByteArray() + secret
        return try {
            val digest = runtime.crypto.hmacSha256(runtime.secrets.resolve(reference), input)
            try {
                require(digest.size == 32) { "HMAC-SHA-256 provider returned an invalid digest" }
                SecretDigest(DigestAlgorithm.HMAC_SHA256, Base64Url.encode(digest), reference.version)
            } finally {
                digest.fill(0)
            }
        } finally {
            input.fill(0)
        }
    }

    private fun authenticatedUser(context: IdentityContext): UserId? = context.principal?.takeIf {
        it.kind == IdentityPrincipalKind.USER && context.session?.state == SessionState.ACTIVE &&
            context.session.assurance != AuthenticationAssurance.RECOVERY
    }?.userId

    private fun authorized(context: IdentityContext, organizationId: OrganizationId, capability: Capability): Boolean =
        authenticatedUser(context) != null && context.organization?.id == organizationId &&
            context.organization.state == OrganizationState.ACTIVE && context.membership?.state == MembershipState.ACTIVE &&
            context.hasCapability(capability, capabilityResolver)

    private fun isRecentPasskey(context: IdentityContext, now: Instant): Boolean {
        val principal = context.principal ?: return false
        return principal.assurance.satisfies(AuthenticationAssurance.PASSKEY) &&
            principal.authenticatedAt <= now &&
            now - principal.authenticatedAt <= config.lifetimes.recentPasskey.seconds.seconds
    }

    private fun audit(
        actor: AuditActor,
        action: AuditAction,
        target: AuditTarget,
        organizationId: OrganizationId?,
        at: Instant,
        request: AuditRequestMetadata?,
        reasonCode: String? = null
    ): AuditEvent = AuditEvent(
        id = ids.newAuditEventId(),
        actor = actor,
        action = action,
        target = target,
        organizationId = organizationId,
        outcome = AuditOutcome.SUCCEEDED,
        reasonCode = reasonCode,
        request = request,
        occurredAt = at
    )

    private fun <T> notFound(): IdentityOperationResult<T> = IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)

    private fun <T> operationFailure(error: IdentityStoreError): IdentityOperationResult<T> =
        IdentityOperationResult.Failure(when (error.code) {
            IdentityStoreErrorCode.NOT_FOUND -> IdentityErrorCode.NOT_FOUND
            IdentityStoreErrorCode.VERSION_CONFLICT,
            IdentityStoreErrorCode.LAST_OWNER,
            IdentityStoreErrorCode.ALREADY_EXISTS,
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT -> IdentityErrorCode.CONFLICT
            IdentityStoreErrorCode.UNAVAILABLE,
            IdentityStoreErrorCode.INTERNAL -> IdentityErrorCode.SERVICE_UNAVAILABLE
            else -> IdentityErrorCode.INVALID_CREDENTIALS
        })

    companion object {
        const val INVITATION_SECRET_BYTES: Int = 32
        private const val INVITATION_DIGEST_CONTEXT = "aether-invitation-v1\u0000"
        private val INVITATION_ENROLLMENT_LIFETIME = IdentityDuration.minutes(15)
    }
}

private data class MatchedInvitation(val invitation: Invitation, val digest: SecretDigest)

private fun Organization.toAccessView(role: String): OrganizationAccessView = OrganizationAccessView(
    id = id,
    name = name,
    slug = slug,
    role = role
)

private fun List<Organization>.filterToFederationOrganization(
    federationOrganizationId: OrganizationId?
): List<Organization> = if (federationOrganizationId == null) {
    this
} else {
    filter { it.id == federationOrganizationId }
}

private fun Invitation.toView(): InvitationView = InvitationView(
    id = id,
    organizationId = organizationId,
    email = email,
    role = role,
    state = state,
    createdAt = createdAt,
    expiresAt = expiresAt,
    acceptedAt = acceptedAt,
    revokedAt = revokedAt
)

private fun AuditEvent.toSafeView(): AuditEventView = AuditEventView(
    id = id,
    actor = actor,
    organizationId = requireNotNull(organizationId),
    action = action,
    target = target,
    outcome = outcome,
    reasonCode = reasonCode,
    request = request?.let {
        AuditRequestView(
            requestId = it.requestId,
            method = it.method,
            path = it.path,
            trustedProxy = it.trustedProxy
        )
    },
    occurredAt = occurredAt
)
