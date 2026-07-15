package codes.yousef.aether.auth.summon

import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.CredentialId
import codes.yousef.aether.auth.InvitationId
import codes.yousef.aether.auth.InvitationState
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.MembershipState
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.ServiceCredentialId
import codes.yousef.aether.auth.ServiceCredentialState
import codes.yousef.aether.auth.ServiceIdentityId
import codes.yousef.aether.auth.ServiceIdentityState
import codes.yousef.aether.auth.SessionId
import codes.yousef.aether.auth.UserId

/**
 * Browser-safe state consumed by the identity components.
 *
 * Deliberately do not add authority configuration, session tokens, token digests, secret
 * references, or an [codes.yousef.aether.auth.IdentityRuntime] to this graph. The sole secret
 * exceptions are explicitly one-time display values and the short-lived RFC 8628 human code being
 * entered or approved. Hosts must never persist them. JVM callers map their server models to these
 * summaries before rendering.
 */
data class IdentityUiState(
    val signedInDisplayName: String? = null,
    val registration: RegistrationUiState = RegistrationUiState(),
    val passkeys: List<PasskeyUiModel> = emptyList(),
    val sessions: List<SessionUiModel> = emptyList(),
    val recoveryCodes: RecoveryCodesUiState = RecoveryCodesUiState.Hidden,
    val oneTimeSecret: OneTimeIdentitySecretUiState = OneTimeIdentitySecretUiState.Hidden,
    val administrativeRecovery: AdministrativeRecoveryUiState = AdministrativeRecoveryUiState(),
    val stepUp: StepUpUiState = StepUpUiState(),
    val organizationManagement: OrganizationManagementUiState = OrganizationManagementUiState(),
    val deviceAuthorization: DeviceAuthorizationUiState = DeviceAuthorizationUiState(),
    val deviceApproval: DeviceApprovalUiState? = null,
    val feedback: IdentityUiFeedback? = null,
    val busyAction: IdentityUiActionKind? = null
) {
    init {
        require(passkeys.map { it.id }.distinct().size == passkeys.size) { "Passkey IDs must be unique" }
        require(sessions.map { it.id }.distinct().size == sessions.size) { "Session IDs must be unique" }
    }
}

/** Browser-safe organization state. Route handlers must populate it from authorized service views. */
data class OrganizationManagementUiState(
    val organizations: List<OrganizationUiModel> = emptyList(),
    val selectedOrganizationId: OrganizationId? = null,
    val memberships: List<MembershipUiModel> = emptyList(),
    val invitationDraft: InvitationDraftUiState = InvitationDraftUiState(),
    val invitations: List<InvitationUiModel> = emptyList(),
    val serviceIdentityDraft: ServiceIdentityDraftUiState = ServiceIdentityDraftUiState(),
    val serviceIdentities: List<ServiceIdentityUiModel> = emptyList(),
    val canInviteMembers: Boolean = false,
    val canManageServiceIdentities: Boolean = false
) {
    init {
        require(organizations.map { it.id }.distinct().size == organizations.size) {
            "Organization IDs must be unique"
        }
        require(selectedOrganizationId == null || organizations.any { it.id == selectedOrganizationId }) {
            "The selected organization must be present in the organization list"
        }
        require(memberships.map { it.id }.distinct().size == memberships.size) {
            "Membership IDs must be unique"
        }
        require(invitations.map { it.id }.distinct().size == invitations.size) {
            "Invitation IDs must be unique"
        }
        require(serviceIdentities.map { it.id }.distinct().size == serviceIdentities.size) {
            "Service identity IDs must be unique"
        }
        if (selectedOrganizationId == null) {
            require(memberships.isEmpty() && invitations.isEmpty() && serviceIdentities.isEmpty()) {
                "Organization resources require an explicit selected organization"
            }
        } else {
            require(memberships.all { it.organizationId == selectedOrganizationId }) {
                "Memberships must belong to the selected organization"
            }
            require(invitations.all { it.organizationId == selectedOrganizationId }) {
                "Invitations must belong to the selected organization"
            }
            require(serviceIdentities.all { it.organizationId == selectedOrganizationId }) {
                "Service identities must belong to the selected organization"
            }
        }
    }
}

data class OrganizationUiModel(
    val id: OrganizationId,
    val name: String,
    val slug: String,
    val role: OrganizationRole
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) { "Invalid organization name" }
        require(ORGANIZATION_SLUG_PATTERN.matches(slug)) { "Invalid organization slug" }
    }
}

data class MembershipUiModel(
    val id: MembershipId,
    val organizationId: OrganizationId,
    val userId: UserId,
    val displayName: String,
    val email: String? = null,
    val role: OrganizationRole,
    val state: MembershipState = MembershipState.ACTIVE,
    val allowedRoles: Set<OrganizationRole> = OrganizationRole.entries.toSet(),
    val canChangeRole: Boolean = false,
    val canRemove: Boolean = false
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_NAME_LENGTH) { "Invalid member name" }
        require(email == null || email.length <= MAX_USER_QUERY_LENGTH) { "Member email is too long" }
        require(allowedRoles.isNotEmpty() && role in allowedRoles) { "Membership role must be an allowed role" }
    }
}

data class InvitationDraftUiState(
    val email: String = "",
    val role: OrganizationRole = OrganizationRole.VIEWER,
    val allowedRoles: Set<OrganizationRole> = OrganizationRole.entries.toSet()
) {
    init {
        require(email.length <= MAX_USER_QUERY_LENGTH) { "Invitation email is too long" }
        require(allowedRoles.isNotEmpty() && role in allowedRoles) { "Invitation role must be allowed" }
    }
}

data class InvitationUiModel(
    val id: InvitationId,
    val organizationId: OrganizationId,
    val email: String,
    val role: OrganizationRole,
    val state: InvitationState,
    val expiresAt: String,
    val canRevoke: Boolean = state == InvitationState.PENDING
) {
    init {
        require(email.isNotBlank() && email.length <= MAX_USER_QUERY_LENGTH) { "Invalid invitation email" }
        require(expiresAt.isNotBlank() && expiresAt.length <= MAX_TIMESTAMP_LENGTH) { "Invalid invitation expiry" }
    }
}

/** A public, allowlisted capability choice. It never carries credential or authority material. */
data class CapabilityOptionUiModel(
    val capability: Capability,
    val label: String,
    val description: String? = null
) {
    init {
        require(label.isNotBlank() && label.length <= MAX_NAME_LENGTH) { "Invalid capability label" }
        require(description == null || description.length <= MAX_DESCRIPTION_LENGTH) {
            "Capability description is too long"
        }
    }
}

data class ServiceIdentityDraftUiState(
    val name: String = "",
    val description: String = "",
    val capabilityOptions: List<CapabilityOptionUiModel> = emptyList(),
    val selectedCapabilities: Set<Capability> = emptySet()
) {
    init {
        require(name.length <= MAX_NAME_LENGTH) { "Service identity name is too long" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) { "Service identity description is too long" }
        requireUniqueCapabilityOptions(capabilityOptions)
        require(capabilityOptions.map { it.capability }.containsAll(selectedCapabilities)) {
            "Selected service capabilities must be allowlisted options"
        }
    }
}

data class ServiceIdentityUiModel(
    val id: ServiceIdentityId,
    val organizationId: OrganizationId,
    val name: String,
    val description: String? = null,
    val capabilities: Set<Capability>,
    val state: ServiceIdentityState,
    val credentials: List<ServiceCredentialUiModel> = emptyList(),
    val canManage: Boolean = false
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) { "Invalid service identity name" }
        require(description == null || description.length <= MAX_DESCRIPTION_LENGTH) {
            "Service identity description is too long"
        }
        require(credentials.map { it.id }.distinct().size == credentials.size) {
            "Service credential IDs must be unique"
        }
    }
}

data class ServiceCredentialUiModel(
    val id: ServiceCredentialId,
    val publicPrefix: String,
    val capabilities: Set<Capability>,
    val state: ServiceCredentialState,
    val expiresAt: String? = null
) {
    init {
        require(SERVICE_CREDENTIAL_PREFIX_PATTERN.matches(publicPrefix)) {
            "Invalid service credential prefix"
        }
        require(expiresAt == null || expiresAt.length <= MAX_TIMESTAMP_LENGTH) {
            "Service credential expiry is too long"
        }
    }
}

/**
 * Signed-in manual entry for an RFC 8628 human code. The draft is never read from or written to a
 * URL, browser storage, or diagnostics.
 */
data class DeviceAuthorizationUiState(
    val userCode: String = "",
    val enabled: Boolean = true
) {
    init {
        require(userCode.length <= USER_CODE_WIRE_LENGTH) { "Device user code draft is too long" }
        require(userCode.all { it == '-' || it in USER_CODE_ALPHABET }) { "Invalid device user code draft" }
    }

    val readyToResolve: Boolean get() = USER_CODE_PATTERN.matches(userCode)

    override fun toString(): String =
        "DeviceAuthorizationUiState(userCode=<redacted>, enabled=$enabled, readyToResolve=$readyToResolve)"
}

/**
 * Pending RFC 8628 approval data. The device code and every resulting token stay outside UI state.
 * The organization is deliberately selected here, independently from the management panel.
 */
data class DeviceApprovalUiState(
    val userCode: String,
    val clientName: String,
    val expiresAt: String,
    val organizations: List<OrganizationUiModel>,
    val approvableCapabilitiesByOrganization: Map<OrganizationId, Set<Capability>>,
    val selectedOrganizationId: OrganizationId? = null,
    val capabilityOptions: List<CapabilityOptionUiModel>,
    val selectedCapabilities: Set<Capability> = emptySet(),
    val enabled: Boolean = true
) {
    init {
        require(USER_CODE_PATTERN.matches(userCode)) { "Invalid device user code" }
        require(clientName.isNotBlank() && clientName.length <= MAX_NAME_LENGTH) { "Invalid device client name" }
        require(expiresAt.isNotBlank() && expiresAt.length <= MAX_TIMESTAMP_LENGTH) { "Invalid device grant expiry" }
        require(organizations.isNotEmpty()) { "Device approval requires at least one organization option" }
        require(organizations.map { it.id }.distinct().size == organizations.size) {
            "Device organization IDs must be unique"
        }
        require(approvableCapabilitiesByOrganization.keys == organizations.mapTo(linkedSetOf()) { it.id }) {
            "Every device organization requires an explicit capability grant set"
        }
        require(selectedOrganizationId == null || organizations.any { it.id == selectedOrganizationId }) {
            "The device organization selection must be one of the offered organizations"
        }
        requireUniqueCapabilityOptions(capabilityOptions)
        require(capabilityOptions.isNotEmpty()) { "Device approval requires at least one requested scope" }
        val requested = capabilityOptions.mapTo(linkedSetOf()) { it.capability }
        require(approvableCapabilitiesByOrganization.values.all(requested::containsAll)) {
            "Organization device grants must be subsets of the requested scopes"
        }
        require(approvableCapabilitiesByOrganization.values.all { it.isNotEmpty() }) {
            "Every offered device organization must allow at least one requested scope"
        }
        require(selectedOrganizationId != null || selectedCapabilities.isEmpty()) {
            "Device scopes cannot be selected before an organization"
        }
        require(capabilityOptions.map { it.capability }.containsAll(selectedCapabilities)) {
            "Selected device scopes must be requested capability options"
        }
        require(
            selectedOrganizationId == null ||
                approvableCapabilitiesByOrganization.getValue(selectedOrganizationId).containsAll(selectedCapabilities)
        ) {
            "Selected device scopes must be allowed for the selected organization"
        }
    }

    override fun toString(): String =
        "DeviceApprovalUiState(userCode=<redacted>, clientName=$clientName, expiresAt=$expiresAt, " +
            "organizations=$organizations, selectedOrganizationId=$selectedOrganizationId, " +
            "capabilityOptions=$capabilityOptions, selectedCapabilities=$selectedCapabilities, enabled=$enabled)"
}

data class RegistrationUiState(
    val passkeyName: String = "",
    val enabled: Boolean = true,
    /** False only for constrained bootstrap/recovery enrollment sessions. */
    val signInEnabled: Boolean = true
) {
    init {
        require(passkeyName.length <= MAX_NAME_LENGTH) { "Passkey name is too long" }
    }
}

data class PasskeyUiModel(
    val id: CredentialId,
    val name: String,
    val renameDraft: String = name,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val backedUp: Boolean = false,
    val canRevoke: Boolean = true
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) { "Invalid passkey name" }
        require(renameDraft.length <= MAX_NAME_LENGTH) { "Passkey rename draft is too long" }
    }
}

data class SessionUiModel(
    val id: SessionId,
    val deviceLabel: String,
    val lastUsedAt: String,
    val expiresAt: String,
    val current: Boolean = false,
    val recentPasskey: Boolean = false
) {
    init {
        require(deviceLabel.isNotBlank() && deviceLabel.length <= MAX_DEVICE_LABEL_LENGTH) {
            "Invalid device label"
        }
    }
}

sealed interface RecoveryCodesUiState {
    data object Hidden : RecoveryCodesUiState

    /** The ten newly generated codes. The values are intentionally shown only in this state. */
    class VisibleOnce(codes: List<String>) : RecoveryCodesUiState {
        val codes: List<String> = codes.toList()

        init {
            require(this.codes.size == 10) { "Exactly ten recovery codes must be displayed" }
            require(this.codes.all { it.isNotBlank() && it.length <= MAX_RECOVERY_CODE_LENGTH }) {
                "Invalid recovery code display value"
            }
        }

        override fun toString(): String = "VisibleOnce(codes=[REDACTED])"
    }
}

enum class OneTimeIdentitySecretKind {
    INVITATION_TOKEN,
    SERVICE_CREDENTIAL
}

/**
 * An issued invitation or service-credential token rendered exactly until the user dismisses it.
 * Hosts must construct this only from a mutation response and must never persist or refetch it.
 */
sealed interface OneTimeIdentitySecretUiState {
    data object Hidden : OneTimeIdentitySecretUiState

    class VisibleOnce(
        val kind: OneTimeIdentitySecretKind,
        val label: String,
        secret: String
    ) : OneTimeIdentitySecretUiState {
        val secret: String = secret

        init {
            require(label.isNotBlank() && label.length <= MAX_NAME_LENGTH) { "Invalid one-time secret label" }
            require(secret.length in 20..1_024 && secret.none(Char::isWhitespace)) {
                "Invalid one-time identity secret"
            }
        }

        override fun toString(): String = "VisibleOnce(kind=$kind, label=$label, secret=<redacted>)"
    }
}

data class AdministrativeRecoveryUiState(
    val enabled: Boolean = false,
    val userQuery: String = "",
    val outstandingTicket: AdministrativeRecoveryTicketUiModel? = null,
    val deliveryStatus: String? = null
) {
    init {
        require(userQuery.length <= MAX_USER_QUERY_LENGTH) { "Recovery user query is too long" }
    }
}

/** Contains only a public audit/reference ID. The enrollment ticket secret is never UI state. */
data class AdministrativeRecoveryTicketUiModel(
    val id: ChallengeId,
    val userId: UserId,
    val expiresAt: String
)

data class StepUpUiState(
    val required: Boolean = false,
    val satisfiedAt: String? = null,
    val reason: String? = null
)

data class IdentityUiFeedback(
    val message: String,
    val severity: IdentityUiFeedbackSeverity = IdentityUiFeedbackSeverity.STATUS
) {
    init {
        require(message.isNotBlank() && message.length <= MAX_FEEDBACK_LENGTH) { "Invalid feedback message" }
    }
}

enum class IdentityUiFeedbackSeverity { STATUS, ERROR }

enum class IdentityUiActionKind {
    REGISTER_PASSKEY,
    SIGN_IN,
    RENAME_PASSKEY,
    REVOKE_PASSKEY,
    REVOKE_SESSION,
    REVOKE_OTHER_SESSIONS,
    REVOKE_ALL_SESSIONS,
    GENERATE_RECOVERY_CODES,
    ADMINISTRATIVE_RECOVERY,
    STEP_UP,
    SELECT_ORGANIZATION,
    UPDATE_MEMBERSHIP,
    REMOVE_MEMBERSHIP,
    INVITE_MEMBER,
    REVOKE_INVITATION,
    CREATE_SERVICE_IDENTITY,
    CREATE_SERVICE_CREDENTIAL,
    ROTATE_SERVICE_CREDENTIAL,
    REVOKE_SERVICE_CREDENTIAL,
    REVOKE_SERVICE_IDENTITY,
    RESOLVE_DEVICE,
    APPROVE_DEVICE,
    DENY_DEVICE
}

enum class IdentityLayoutClass { PHONE, DESKTOP }

fun identityLayoutClass(viewportWidthPx: Int): IdentityLayoutClass {
    require(viewportWidthPx >= 0) { "Viewport width must not be negative" }
    return if (viewportWidthPx < DESKTOP_BREAKPOINT_PX) IdentityLayoutClass.PHONE else IdentityLayoutClass.DESKTOP
}

internal const val DESKTOP_BREAKPOINT_PX = 768
private const val MAX_NAME_LENGTH = 200
private const val MAX_DEVICE_LABEL_LENGTH = 200
private const val MAX_USER_QUERY_LENGTH = 320
private const val MAX_FEEDBACK_LENGTH = 1_000
private const val MAX_RECOVERY_CODE_LENGTH = 200
private const val MAX_DESCRIPTION_LENGTH = 2_000
private const val MAX_TIMESTAMP_LENGTH = 100
private const val USER_CODE_WIRE_LENGTH = 9
private const val USER_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
private val ORGANIZATION_SLUG_PATTERN = Regex("[a-z0-9][a-z0-9-]{1,62}")
private val SERVICE_CREDENTIAL_PREFIX_PATTERN = Regex("[A-Za-z0-9_-]{6,64}")
private val USER_CODE_PATTERN = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}")

internal fun normalizeDeviceUserCodeDraft(value: String): String {
    val symbols = value.take(64).uppercase().filter { it in USER_CODE_ALPHABET }.take(8)
    return if (symbols.length <= 4) symbols else "${symbols.take(4)}-${symbols.drop(4)}"
}

private fun requireUniqueCapabilityOptions(options: List<CapabilityOptionUiModel>) {
    require(options.map { it.capability }.distinct().size == options.size) {
        "Capability options must be unique"
    }
}
