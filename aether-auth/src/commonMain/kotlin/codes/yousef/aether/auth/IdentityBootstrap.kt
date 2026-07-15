package codes.yousef.aether.auth

import kotlinx.serialization.Serializable

@Serializable
data class BootstrapIdentityCommand(
    val bootstrapSecretDigest: SecretDigest,
    val user: User,
    val organization: Organization,
    val ownerMembership: Membership,
    val enrollmentSession: IdentitySession,
    val auditEvent: AuditEvent
) {
    init {
        require(bootstrapSecretDigest.algorithm == DigestAlgorithm.SHA256) {
            "Bootstrap receipts use an unkeyed one-way digest"
        }
        require(user.version == 0L && user.state == UserState.ACTIVE) {
            "Bootstrap user must be active at version zero"
        }
        require(organization.version == 0L && organization.state == OrganizationState.ACTIVE) {
            "Bootstrap organization must be active at version zero"
        }
        require(ownerMembership.version == 0L && ownerMembership.state == MembershipState.ACTIVE &&
            ownerMembership.role == OrganizationRole.OWNER && ownerMembership.userId == user.id &&
            ownerMembership.organizationId == organization.id
        ) { "Bootstrap must establish exactly one matching owner membership" }
        require(enrollmentSession.version == 0L && enrollmentSession.state == SessionState.ACTIVE &&
            enrollmentSession.userId == user.id && enrollmentSession.userSessionEpoch == user.sessionEpoch &&
            enrollmentSession.assurance == AuthenticationAssurance.RECOVERY &&
            enrollmentSession.authenticationMethod == SessionAuthenticationMethod.BOOTSTRAP
        ) { "Bootstrap must create one constrained enrollment session for the first owner" }
        require(auditEvent.action == AuditAction.IDENTITY_BOOTSTRAPPED &&
            auditEvent.target == AuditTarget(AuditTargetType.USER, user.id.value)
        ) { "Bootstrap requires an identity-bootstrapped audit event" }
    }
}

@Serializable
data class BootstrapIdentityCommit(
    val user: User,
    val organization: Organization,
    val ownerMembership: Membership,
    val enrollmentSession: IdentitySession,
    val auditEvent: AuditEvent
)

class BootstrappedIdentity(
    val user: User,
    val organization: Organization,
    val ownerMembership: Membership,
    val issuedEnrollmentSession: IssuedIdentitySession
) {
    override fun toString(): String =
        "BootstrappedIdentity(user=${user.id}, organization=${organization.id}, " +
            "ownerMembership=${ownerMembership.id}, issuedEnrollmentSession=<redacted>)"
}

/** Consumes the configured deployment secret once to establish the first owner identity. */
class IdentityBootstrapService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime),
    private val sessions: IdentitySessionIssuer = IdentitySessionIssuer(runtime, config, ids)
) {
    val enabled: Boolean get() =
        config.bootstrapLifecycle == IdentityBootstrapLifecycle.PENDING && config.bootstrapSecret != null

    suspend fun bootstrap(
        providedSecret: String,
        displayName: String,
        primaryEmail: EmailAddress,
        organizationName: String,
        organizationSlug: String,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<BootstrappedIdentity> {
        if (!config.allowsRegistration(IdentityRegistrationSource.BOOTSTRAP)) {
            return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        }
        val reference = config.bootstrapSecret
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        if (providedSecret.length !in 16..512 || displayName.isBlank() || displayName.length > 200 ||
            organizationName.isBlank() || organizationName.length > 200 ||
            !Regex("[a-z0-9][a-z0-9-]{1,62}").matches(organizationSlug)
        ) return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        val provided = providedSecret.encodeToByteArray()
        try {
            val matches = runtime.secrets.resolve(reference).useBytes { expected ->
                runtime.crypto.constantTimeEquals(expected, provided)
            }
            if (!matches) return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            val now = runtime.clock.now()
            val digestBytes = runtime.crypto.sha256(provided)
            val digest = try {
                SecretDigest(DigestAlgorithm.SHA256, Base64Url.encode(digestBytes))
            } finally {
                digestBytes.fill(0)
            }
            val user = User(
                id = ids.newUserId(),
                state = UserState.ACTIVE,
                displayName = displayName,
                primaryEmail = primaryEmail,
                createdAt = now,
                updatedAt = now,
                activatedAt = now
            )
            val organization = Organization(
                id = ids.newOrganizationId(),
                name = organizationName,
                slug = organizationSlug,
                createdAt = now,
                updatedAt = now
            )
            val owner = Membership(
                id = ids.newMembershipId(),
                organizationId = organization.id,
                userId = user.id,
                role = OrganizationRole.OWNER,
                createdAt = now,
                updatedAt = now
            )
            val enrollmentSession = sessions.issue(
                user = user,
                assurance = AuthenticationAssurance.RECOVERY,
                authenticationMethod = SessionAuthenticationMethod.BOOTSTRAP,
                authenticatedAt = now,
                absoluteLifetime = config.lifetimes.recoverySession,
                idleLifetime = config.lifetimes.recoverySession
            )
            val audit = AuditEvent(
                id = ids.newAuditEventId(),
                actor = AuditActor(AuditActorType.SYSTEM),
                action = AuditAction.IDENTITY_BOOTSTRAPPED,
                target = AuditTarget(AuditTargetType.USER, user.id.value),
                organizationId = organization.id,
                outcome = AuditOutcome.SUCCEEDED,
                request = request,
                occurredAt = now
            )
            return when (val committed = store.bootstrapIdentity(
                BootstrapIdentityCommand(
                    digest,
                    user,
                    organization,
                    owner,
                    enrollmentSession.session,
                    audit
                )
            )) {
                is StoreResult.Success -> IdentityOperationResult.Success(
                    BootstrappedIdentity(
                        committed.value.user,
                        committed.value.organization,
                        committed.value.ownerMembership,
                        enrollmentSession
                    )
                )
                is StoreResult.Failure -> IdentityOperationResult.Failure(when (committed.error.code) {
                    IdentityStoreErrorCode.ALREADY_EXISTS,
                    IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
                    IdentityStoreErrorCode.INVALID_TRANSITION -> IdentityErrorCode.CONFLICT
                    IdentityStoreErrorCode.VERSION_CONFLICT -> IdentityErrorCode.CONFLICT
                    IdentityStoreErrorCode.UNAVAILABLE,
                    IdentityStoreErrorCode.INTERNAL -> IdentityErrorCode.SERVICE_UNAVAILABLE
                    else -> IdentityErrorCode.INVALID_CREDENTIALS
                })
            }
        } finally {
            provided.fill(0)
        }
    }
}
