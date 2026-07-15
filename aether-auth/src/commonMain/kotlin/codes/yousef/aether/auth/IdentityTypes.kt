package codes.yousef.aether.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

private val CAPABILITY_PATTERN = Regex("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")

@Serializable
enum class IdentityEnvironment(val wireName: String) {
    @SerialName("development") DEVELOPMENT("development"),
    @SerialName("test") TEST("test"),
    @SerialName("staging") STAGING("staging"),
    @SerialName("production") PRODUCTION("production")
}

@Serializable
enum class UserState {
    @SerialName("pending") PENDING,
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
    @SerialName("deactivated") DEACTIVATED
}

@Serializable
enum class CredentialState {
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
    @SerialName("suspected_clone") SUSPECTED_CLONE,
    @SerialName("revoked") REVOKED
}

@Serializable
enum class SessionState {
    @SerialName("active") ACTIVE,
    @SerialName("rotated") ROTATED,
    @SerialName("revoked") REVOKED,
    @SerialName("expired") EXPIRED
}

@Serializable
enum class OrganizationState {
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
    @SerialName("deleted") DELETED
}

@Serializable
enum class MembershipState {
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
    @SerialName("removed") REMOVED
}

@Serializable
enum class InvitationState {
    @SerialName("pending") PENDING,
    @SerialName("accepted") ACCEPTED,
    @SerialName("revoked") REVOKED,
    @SerialName("expired") EXPIRED
}

@Serializable
enum class ServiceIdentityState {
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
    @SerialName("revoked") REVOKED
}

@Serializable
enum class ServiceCredentialState {
    @SerialName("active") ACTIVE,
    @SerialName("rotated") ROTATED,
    @SerialName("revoked") REVOKED,
    @SerialName("expired") EXPIRED
}

@Serializable
enum class DeviceGrantState {
    @SerialName("pending") PENDING,
    @SerialName("authorized") AUTHORIZED,
    @SerialName("denied") DENIED,
    @SerialName("consumed") CONSUMED,
    @SerialName("expired") EXPIRED,
    @SerialName("cancelled") CANCELLED
}

@Serializable
enum class DeviceTokenFamilyState {
    @SerialName("active") ACTIVE,
    @SerialName("revoked") REVOKED,
    @SerialName("expired") EXPIRED
}

@Serializable
enum class DeviceAccessTokenState {
    @SerialName("active") ACTIVE,
    @SerialName("revoked") REVOKED,
    @SerialName("expired") EXPIRED
}

@Serializable
enum class DeviceRefreshTokenState {
    @SerialName("active") ACTIVE,
    @SerialName("rotated") ROTATED,
    @SerialName("revoked") REVOKED,
    @SerialName("expired") EXPIRED
}

@Serializable
enum class ChallengeState {
    @SerialName("pending") PENDING,
    @SerialName("consumed") CONSUMED,
    @SerialName("failed") FAILED,
    @SerialName("expired") EXPIRED
}

@Serializable
enum class RecoveryCodeState {
    @SerialName("active") ACTIVE,
    @SerialName("consumed") CONSUMED,
    @SerialName("revoked") REVOKED,
    @SerialName("expired") EXPIRED
}

@Serializable
enum class ExternalIdentityState {
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
    @SerialName("unlinked") UNLINKED
}

@Serializable
enum class OrganizationRole(val wireName: String, private val rank: Int) {
    @SerialName("owner") OWNER("owner", 3),
    @SerialName("admin") ADMIN("admin", 2),
    @SerialName("publisher") PUBLISHER("publisher", 1),
    @SerialName("viewer") VIEWER("viewer", 0);

    /** Capabilities granted inside the membership's organization only. */
    val capabilities: Set<Capability>
        get() = when (this) {
            OWNER -> Capability.IDENTITY_MANAGEMENT
            ADMIN -> Capability.IDENTITY_MANAGEMENT
                .filterNot { it == Capability.ORGANIZATION_DELETE || it == Capability.ORGANIZATION_TRANSFER_OWNERSHIP }
                .toSet()
            PUBLISHER -> setOf(
                Capability.IDENTITY_PROFILE,
                Capability.IDENTITY_ORGANIZATIONS,
                Capability.ORGANIZATION_READ,
                Capability.MEMBERSHIP_READ
            )
            VIEWER -> setOf(
                Capability.IDENTITY_PROFILE,
                Capability.IDENTITY_ORGANIZATIONS,
                Capability.ORGANIZATION_READ,
                Capability.MEMBERSHIP_READ
            )
        }

    fun grants(capability: Capability): Boolean = capability in capabilities

    /** Returns true when this role meets or exceeds [required] in the fixed organization hierarchy. */
    fun satisfies(required: OrganizationRole): Boolean = rank >= required.rank
}

@Serializable
@JvmInline
value class Capability(val wireName: String) {
    init {
        require(wireName.length in 3..200 && CAPABILITY_PATTERN.matches(wireName)) {
            "Capability must be a lowercase dotted identifier"
        }
    }

    override fun toString(): String = wireName

    companion object {
        val ORGANIZATION_READ = Capability("organization.read")
        /** Allows an organization-bound device token to read its stable user summary. */
        val IDENTITY_PROFILE = Capability("identity.profile")
        /** Allows an organization-bound device token to read its granted organization. */
        val IDENTITY_ORGANIZATIONS = Capability("identity.organizations")
        val ORGANIZATION_UPDATE = Capability("organization.update")
        val ORGANIZATION_DELETE = Capability("organization.delete")
        val ORGANIZATION_TRANSFER_OWNERSHIP = Capability("organization.transfer_ownership")
        val MEMBERSHIP_READ = Capability("membership.read")
        val MEMBERSHIP_INVITE = Capability("membership.invite")
        val MEMBERSHIP_UPDATE = Capability("membership.update")
        val MEMBERSHIP_REMOVE = Capability("membership.remove")
        val CONTENT_READ = Capability("content.read")
        val CONTENT_PUBLISH = Capability("content.publish")
        val AUDIT_READ = Capability("audit.read")
        val SERVICE_IDENTITY_READ = Capability("service_identity.read")
        val SERVICE_IDENTITY_MANAGE = Capability("service_identity.manage")
        val DEVICE_GRANT_APPROVE = Capability("device_grant.approve")
        /** Platform-level capability; never granted by an organization role. */
        val ACCOUNT_RECOVERY_ADMIN = Capability("identity.account.recover")
        val EXTERNAL_IDENTITY_MANAGE = Capability("external_identity.manage")
        val SCIM_MANAGE = Capability("scim.manage")

        /** Closed set of capabilities owned by Aether's organization identity layer. */
        val IDENTITY_MANAGEMENT: Set<Capability> = setOf(
            IDENTITY_PROFILE,
            IDENTITY_ORGANIZATIONS,
            ORGANIZATION_READ,
            ORGANIZATION_UPDATE,
            ORGANIZATION_DELETE,
            ORGANIZATION_TRANSFER_OWNERSHIP,
            MEMBERSHIP_READ,
            MEMBERSHIP_INVITE,
            MEMBERSHIP_UPDATE,
            MEMBERSHIP_REMOVE,
            AUDIT_READ,
            SERVICE_IDENTITY_READ,
            SERVICE_IDENTITY_MANAGE,
            DEVICE_GRANT_APPROVE,
            EXTERNAL_IDENTITY_MANAGE,
            SCIM_MANAGE
        )

        /** Capabilities reserved to Aether policy and never grantable by an application resolver. */
        val AETHER_OWNED: Set<Capability> = IDENTITY_MANAGEMENT + ACCOUNT_RECOVERY_ADMIN
    }
}

/** Application-owned grants, such as mapping publisher to `package.publish`. */
fun interface CapabilityResolver {
    fun resolve(context: IdentityContext): Set<Capability>

    companion object {
        val NONE: CapabilityResolver = CapabilityResolver { emptySet() }
    }
}

typealias OrgRole = OrganizationRole
typealias AssuranceLevel = AuthenticationAssurance

/** Primary authentication method which established the current session credential. */
@Serializable
enum class SessionAuthenticationMethod {
    @SerialName("passkey") PASSKEY,
    @SerialName("recovery_code") RECOVERY_CODE,
    @SerialName("administrative_recovery") ADMINISTRATIVE_RECOVERY,
    @SerialName("bootstrap") BOOTSTRAP,
    @SerialName("invitation") INVITATION,
    @SerialName("oidc") OIDC,
    @SerialName("saml") SAML
}

@Serializable
enum class AuthenticationAssurance(val level: Int) {
    @SerialName("anonymous") ANONYMOUS(0),
    @SerialName("recovery") RECOVERY(10),
    @SerialName("session") SESSION(20),
    @SerialName("passkey") PASSKEY(50),
    @SerialName("step_up") STEP_UP(80),
    @SerialName("device_token") DEVICE_TOKEN(20),
    @SerialName("service_credential") SERVICE_CREDENTIAL(50);

    fun satisfies(required: AuthenticationAssurance): Boolean = when {
        required == ANONYMOUS -> true
        this == RECOVERY || required == RECOVERY -> this == required
        this == SERVICE_CREDENTIAL || required == SERVICE_CREDENTIAL -> this == required
        this == DEVICE_TOKEN || required == DEVICE_TOKEN -> this == required
        else -> level >= required.level
    }
}

@Serializable
enum class AuthenticatorTransport {
    @SerialName("usb") USB,
    @SerialName("nfc") NFC,
    @SerialName("ble") BLE,
    @SerialName("internal") INTERNAL,
    @SerialName("hybrid") HYBRID,
    @SerialName("smart_card") SMART_CARD
}

@Serializable
enum class ChallengePurpose {
    @SerialName("webauthn_registration") WEBAUTHN_REGISTRATION,
    @SerialName("webauthn_authentication") WEBAUTHN_AUTHENTICATION,
    @SerialName("step_up") STEP_UP,
    @SerialName("account_recovery") ACCOUNT_RECOVERY,
    @SerialName("invitation_acceptance") INVITATION_ACCEPTANCE,
    @SerialName("device_authorization") DEVICE_AUTHORIZATION,
    @SerialName("external_identity_link") EXTERNAL_IDENTITY_LINK,
    @SerialName("service_credential_rotation") SERVICE_CREDENTIAL_ROTATION
}

@Serializable
enum class RegistrationPolicy {
    @SerialName("invitation_only") INVITATION_ONLY,
    @SerialName("open") OPEN,
    @SerialName("admin_only") ADMIN_ONLY,
    @SerialName("disabled") DISABLED
}

@Serializable
enum class SameSitePolicy {
    @SerialName("strict") STRICT,
    @SerialName("lax") LAX,
    @SerialName("none") NONE
}

@Serializable
enum class TrustedProxyMode {
    @SerialName("direct_only") DIRECT_ONLY,
    @SerialName("trusted_cidrs") TRUSTED_CIDRS
}

@Serializable
enum class AuditActorType {
    @SerialName("anonymous") ANONYMOUS,
    @SerialName("user") USER,
    @SerialName("service") SERVICE,
    @SerialName("system") SYSTEM
}

@Serializable
enum class AuditOutcome {
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("denied") DENIED,
    @SerialName("failed") FAILED
}

@Serializable
enum class AuditAction {
    @SerialName("identity.bootstrapped") IDENTITY_BOOTSTRAPPED,
    @SerialName("user.created") USER_CREATED,
    @SerialName("user.state_changed") USER_STATE_CHANGED,
    @SerialName("webauthn.ceremony_rejected") WEBAUTHN_CEREMONY_REJECTED,
    @SerialName("credential.registered") CREDENTIAL_REGISTERED,
    @SerialName("credential.authenticated") CREDENTIAL_AUTHENTICATED,
    @SerialName("credential.renamed") CREDENTIAL_RENAMED,
    @SerialName("credential.quarantined") CREDENTIAL_QUARANTINED,
    @SerialName("credential.revoked") CREDENTIAL_REVOKED,
    @SerialName("session.created") SESSION_CREATED,
    @SerialName("session.rotated") SESSION_ROTATED,
    @SerialName("session.revoked") SESSION_REVOKED,
    @SerialName("organization.created") ORGANIZATION_CREATED,
    @SerialName("organization.changed") ORGANIZATION_CHANGED,
    @SerialName("organization.deleted") ORGANIZATION_DELETED,
    @SerialName("membership.created") MEMBERSHIP_CREATED,
    @SerialName("membership.changed") MEMBERSHIP_CHANGED,
    @SerialName("invitation.created") INVITATION_CREATED,
    @SerialName("invitation.accepted") INVITATION_ACCEPTED,
    @SerialName("invitation.revoked") INVITATION_REVOKED,
    @SerialName("recovery.codes_replaced") RECOVERY_CODES_REPLACED,
    @SerialName("recovery.code_used") RECOVERY_CODE_USED,
    @SerialName("recovery.enrollment_completed") RECOVERY_ENROLLMENT_COMPLETED,
    @SerialName("recovery.admin_ticket_created") RECOVERY_ADMIN_TICKET_CREATED,
    @SerialName("recovery.admin_ticket_delivered") RECOVERY_ADMIN_TICKET_DELIVERED,
    @SerialName("recovery.admin_ticket_used") RECOVERY_ADMIN_TICKET_USED,
    @SerialName("recovery.admin_ticket_expired") RECOVERY_ADMIN_TICKET_EXPIRED,
    @SerialName("recovery.admin_ticket_cancelled") RECOVERY_ADMIN_TICKET_CANCELLED,
    @SerialName("service_identity.created") SERVICE_IDENTITY_CREATED,
    @SerialName("service_identity.changed") SERVICE_IDENTITY_CHANGED,
    @SerialName("service_identity.revoked") SERVICE_IDENTITY_REVOKED,
    @SerialName("service_credential.created") SERVICE_CREDENTIAL_CREATED,
    @SerialName("service_credential.rotated") SERVICE_CREDENTIAL_ROTATED,
    @SerialName("service_credential.revoked") SERVICE_CREDENTIAL_REVOKED,
    @SerialName("device_grant.changed") DEVICE_GRANT_CHANGED,
    @SerialName("device_token.issued") DEVICE_TOKEN_ISSUED,
    @SerialName("device_token.refreshed") DEVICE_TOKEN_REFRESHED,
    @SerialName("device_token.replay_detected") DEVICE_TOKEN_REPLAY_DETECTED,
    @SerialName("device_token.revoked") DEVICE_TOKEN_REVOKED,
    @SerialName("external_identity.linked") EXTERNAL_IDENTITY_LINKED,
    @SerialName("federation_provider.disabled") FEDERATION_PROVIDER_DISABLED,
    @SerialName("federation_provider.enabled") FEDERATION_PROVIDER_ENABLED,
    @SerialName("scim.mutation_applied") SCIM_MUTATION_APPLIED,
    @SerialName("scim.group_changed") SCIM_GROUP_CHANGED
}

@Serializable
enum class AuditTargetType {
    @SerialName("user") USER,
    @SerialName("challenge") CHALLENGE,
    @SerialName("credential") CREDENTIAL,
    @SerialName("session") SESSION,
    @SerialName("organization") ORGANIZATION,
    @SerialName("membership") MEMBERSHIP,
    @SerialName("invitation") INVITATION,
    @SerialName("service_identity") SERVICE_IDENTITY,
    @SerialName("service_credential") SERVICE_CREDENTIAL,
    @SerialName("device_grant") DEVICE_GRANT,
    @SerialName("external_identity") EXTERNAL_IDENTITY,
    @SerialName("federation_provider") FEDERATION_PROVIDER,
    @SerialName("scim_group") SCIM_GROUP
}

@Serializable
enum class ScimGroupState {
    @SerialName("active") ACTIVE,
    @SerialName("deleted") DELETED
}

@Serializable
enum class ScimMutationType {
    @SerialName("upsert_user") UPSERT_USER,
    @SerialName("deactivate_user") DEACTIVATE_USER,
    @SerialName("upsert_membership") UPSERT_MEMBERSHIP,
    @SerialName("remove_membership") REMOVE_MEMBERSHIP
}
