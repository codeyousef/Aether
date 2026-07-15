package codes.yousef.aether.auth

import codes.yousef.aether.core.auth.Principal
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class IdentityPrincipalKind {
    @SerialName("user") USER,
    @SerialName("device") DEVICE,
    @SerialName("service") SERVICE
}

/** Canonical authenticated subject placed into Aether's principal attribute. */
@Serializable
data class IdentityPrincipal(
    val kind: IdentityPrincipalKind,
    val userId: UserId? = null,
    val serviceIdentityId: ServiceIdentityId? = null,
    val displayName: String,
    val assurance: AuthenticationAssurance,
    val authenticatedAt: Instant,
    val sessionId: SessionId? = null,
    val deviceTokenFamilyId: DeviceTokenFamilyId? = null,
    val directCapabilities: Set<Capability> = emptySet()
) : Principal {
    init {
        require(displayName.isNotBlank() && displayName.length <= 200) {
            "Principal display name must be 1..200 characters"
        }
        when (kind) {
            IdentityPrincipalKind.USER -> {
                require(userId != null && serviceIdentityId == null) { "A user principal requires only userId" }
                require(assurance != AuthenticationAssurance.ANONYMOUS &&
                    assurance != AuthenticationAssurance.SERVICE_CREDENTIAL &&
                    assurance != AuthenticationAssurance.DEVICE_TOKEN
                ) {
                    "A user principal requires user assurance"
                }
                require(sessionId != null && deviceTokenFamilyId == null) { "A user principal requires only a session" }
            }
            IdentityPrincipalKind.DEVICE -> {
                require(userId != null && serviceIdentityId == null && sessionId == null &&
                    deviceTokenFamilyId != null
                ) { "A device principal requires a user and token family only" }
                require(assurance == AuthenticationAssurance.DEVICE_TOKEN) {
                    "A device principal requires device-token assurance"
                }
            }
            IdentityPrincipalKind.SERVICE -> {
                require(serviceIdentityId != null && userId == null) { "A service principal requires only serviceIdentityId" }
                require(assurance == AuthenticationAssurance.SERVICE_CREDENTIAL) {
                    "A service principal requires service-credential assurance"
                }
                require(sessionId == null && deviceTokenFamilyId == null) {
                    "A service principal must not have a user session or device token family"
                }
            }
        }
    }

    @Transient
    override val id: String = userId?.value ?: requireNotNull(serviceIdentityId).value

    @Transient
    override val name: String = displayName

    /** Organization roles are intentionally scoped through [IdentityContext], never globally. */
    @Transient
    override val roles: Set<String> = emptySet()

    @Transient
    override val attributes: Map<String, Any?> = emptyMap()

    override fun hasPermission(permission: String): Boolean =
        directCapabilities.any { it.wireName == permission }
}

/**
 * Request-scoped identity state. Missing organization or membership never grants organization
 * capabilities; middleware must explicitly require the appropriate scope.
 */
@Serializable
data class IdentityContext(
    val principal: IdentityPrincipal? = null,
    val session: IdentitySession? = null,
    val organization: Organization? = null,
    val membership: Membership? = null
) {
    init {
        if (principal == null) {
            require(session == null && organization == null && membership == null) {
                "Anonymous identity context must not carry authenticated state"
            }
        } else {
            if (principal.kind == IdentityPrincipalKind.USER) {
                require(session != null && session.id == principal.sessionId && session.userId == principal.userId) {
                    "User principal and session must refer to the same subject"
                }
            } else if (principal.kind == IdentityPrincipalKind.SERVICE) {
                require(session == null && membership == null) {
                    "Service principals do not use user sessions or memberships"
                }
            } else {
                require(session == null && organization != null) {
                    "Device principals require an organization and no user session"
                }
            }
            membership?.let {
                require(principal.kind != IdentityPrincipalKind.SERVICE && it.userId == principal.userId) {
                    "Membership must belong to a user-backed principal"
                }
                require(organization != null && it.organizationId == organization.id) {
                    "Membership and organization scope must match"
                }
            }
        }
    }

    @Transient
    val effectiveCapabilities: Set<Capability> = buildSet {
        if (session?.assurance != AuthenticationAssurance.RECOVERY) {
            principal?.directCapabilities?.let(::addAll)
            if (principal?.kind == IdentityPrincipalKind.USER &&
                membership?.state == MembershipState.ACTIVE && organization?.state == OrganizationState.ACTIVE
            ) {
                addAll(membership.role.capabilities)
            }
        }
    }

    val isAuthenticated: Boolean get() = principal != null

    fun hasRole(role: OrganizationRole): Boolean =
        session?.assurance != AuthenticationAssurance.RECOVERY &&
        membership?.state == MembershipState.ACTIVE &&
            organization?.state == OrganizationState.ACTIVE &&
            membership.role.satisfies(role)

    fun capabilities(resolver: CapabilityResolver = CapabilityResolver.NONE): Set<Capability> {
        if (session?.assurance == AuthenticationAssurance.RECOVERY) return emptySet()
        val applicationCapabilities = resolver.resolve(this).filterNot { it in Capability.AETHER_OWNED }
        return effectiveCapabilities + applicationCapabilities
    }

    fun hasCapability(
        capability: Capability,
        resolver: CapabilityResolver = CapabilityResolver.NONE
    ): Boolean = capability in capabilities(resolver)

    fun satisfiesAssurance(required: AuthenticationAssurance): Boolean =
        principal?.assurance?.satisfies(required) == true

    fun isSessionUsableAt(now: Instant): Boolean {
        val value = session ?: return principal?.kind == IdentityPrincipalKind.SERVICE ||
            principal?.kind == IdentityPrincipalKind.DEVICE
        return value.state == SessionState.ACTIVE &&
            now < value.idleExpiresAt &&
            now < value.absoluteExpiresAt
    }

    companion object {
        val Anonymous: IdentityContext = IdentityContext()
    }
}
