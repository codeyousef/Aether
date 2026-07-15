package codes.yousef.aether.auth.summon

import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.CredentialId
import codes.yousef.aether.auth.InvitationId
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.ServiceCredentialId
import codes.yousef.aether.auth.ServiceIdentityId
import codes.yousef.aether.auth.SessionId

sealed interface IdentityUiAction {
    data class ChangeRegistrationName(val value: String) : IdentityUiAction
    data object RegisterPasskey : IdentityUiAction
    data object DiscoverableSignIn : IdentityUiAction
    data class ChangePasskeyName(val credentialId: CredentialId, val value: String) : IdentityUiAction
    data class RenamePasskey(val credentialId: CredentialId) : IdentityUiAction
    data class RevokePasskey(val credentialId: CredentialId) : IdentityUiAction
    data class RevokeSession(val sessionId: SessionId) : IdentityUiAction
    data object RevokeOtherSessions : IdentityUiAction
    data object RevokeAllSessions : IdentityUiAction
    data object GenerateRecoveryCodes : IdentityUiAction
    data object DismissRecoveryCodes : IdentityUiAction
    data object DismissOneTimeSecret : IdentityUiAction
    data class ChangeAdministrativeRecoveryUser(val value: String) : IdentityUiAction
    data object IssueAdministrativeRecovery : IdentityUiAction
    data class CancelAdministrativeRecovery(val ticketId: ChallengeId) : IdentityUiAction
    data object StepUpWithPasskey : IdentityUiAction
    data class SelectOrganization(val organizationId: OrganizationId) : IdentityUiAction
    data class ChangeMembershipRole(
        val organizationId: OrganizationId,
        val membershipId: MembershipId,
        val role: OrganizationRole
    ) : IdentityUiAction
    data class RemoveMembership(val organizationId: OrganizationId, val membershipId: MembershipId) : IdentityUiAction
    data class ChangeInvitationEmail(val value: String) : IdentityUiAction
    data class ChangeInvitationRole(val role: OrganizationRole) : IdentityUiAction
    data class InviteMember(val organizationId: OrganizationId) : IdentityUiAction
    data class RevokeInvitation(val organizationId: OrganizationId, val invitationId: InvitationId) : IdentityUiAction
    data class ChangeServiceIdentityName(val value: String) : IdentityUiAction
    data class ChangeServiceIdentityDescription(val value: String) : IdentityUiAction
    data class ToggleServiceIdentityCapability(val capability: Capability, val selected: Boolean) : IdentityUiAction
    data class CreateServiceIdentity(val organizationId: OrganizationId) : IdentityUiAction
    data class CreateServiceCredential(
        val organizationId: OrganizationId,
        val serviceIdentityId: ServiceIdentityId
    ) : IdentityUiAction
    data class RotateServiceCredential(
        val organizationId: OrganizationId,
        val credentialId: ServiceCredentialId
    ) : IdentityUiAction
    data class RevokeServiceCredential(
        val organizationId: OrganizationId,
        val credentialId: ServiceCredentialId
    ) : IdentityUiAction
    data class RevokeServiceIdentity(
        val organizationId: OrganizationId,
        val serviceIdentityId: ServiceIdentityId
    ) : IdentityUiAction
    data class ChangeDeviceUserCode(val value: String) : IdentityUiAction {
        override fun toString(): String = "ChangeDeviceUserCode(value=<redacted>)"
    }
    data object ResolveDeviceAuthorization : IdentityUiAction
    data class SelectDeviceOrganization(val organizationId: OrganizationId) : IdentityUiAction
    data class ToggleDeviceCapability(val capability: Capability, val selected: Boolean) : IdentityUiAction
    data class ApproveDeviceAuthorization(
        val userCode: String,
        val organizationId: OrganizationId,
        val capabilities: Set<Capability>
    ) : IdentityUiAction {
        override fun toString(): String =
            "ApproveDeviceAuthorization(userCode=<redacted>, organizationId=$organizationId, capabilities=$capabilities)"
    }
    data class DenyDeviceAuthorization(val userCode: String) : IdentityUiAction {
        override fun toString(): String = "DenyDeviceAuthorization(userCode=<redacted>)"
    }
    data object ClearFeedback : IdentityUiAction
}

fun interface IdentityUiDispatcher {
    fun dispatch(action: IdentityUiAction)
}

/** Applies the local, synchronous edits. Network-backed actions remain the host's responsibility. */
fun reduceIdentityUiState(state: IdentityUiState, action: IdentityUiAction): IdentityUiState = when (action) {
    is IdentityUiAction.ChangeRegistrationName -> state.copy(
        registration = state.registration.copy(passkeyName = action.value.take(200))
    )

    is IdentityUiAction.ChangePasskeyName -> state.copy(
        passkeys = state.passkeys.map { passkey ->
            if (passkey.id == action.credentialId) passkey.copy(renameDraft = action.value.take(200)) else passkey
        }
    )

    IdentityUiAction.DismissRecoveryCodes -> state.copy(recoveryCodes = RecoveryCodesUiState.Hidden)
    IdentityUiAction.DismissOneTimeSecret -> state.copy(oneTimeSecret = OneTimeIdentitySecretUiState.Hidden)
    is IdentityUiAction.ChangeAdministrativeRecoveryUser -> state.copy(
        administrativeRecovery = state.administrativeRecovery.copy(userQuery = action.value.take(320))
    )

    is IdentityUiAction.SelectOrganization -> if (
        state.organizationManagement.organizations.any { it.id == action.organizationId }
    ) {
        state.copy(
            organizationManagement = state.organizationManagement.copy(
                selectedOrganizationId = action.organizationId,
                memberships = emptyList(),
                invitations = emptyList(),
                serviceIdentities = emptyList(),
                invitationDraft = state.organizationManagement.invitationDraft.copy(email = ""),
                serviceIdentityDraft = ServiceIdentityDraftUiState()
            )
        )
    } else {
        state
    }

    is IdentityUiAction.ChangeInvitationEmail -> state.copy(
        organizationManagement = state.organizationManagement.copy(
            invitationDraft = state.organizationManagement.invitationDraft.copy(email = action.value.take(320))
        )
    )

    is IdentityUiAction.ChangeInvitationRole -> {
        val draft = state.organizationManagement.invitationDraft
        if (action.role !in draft.allowedRoles) state else state.copy(
            organizationManagement = state.organizationManagement.copy(
                invitationDraft = draft.copy(role = action.role)
            )
        )
    }

    is IdentityUiAction.ChangeServiceIdentityName -> state.copy(
        organizationManagement = state.organizationManagement.copy(
            serviceIdentityDraft = state.organizationManagement.serviceIdentityDraft.copy(
                name = action.value.take(200)
            )
        )
    )

    is IdentityUiAction.ChangeServiceIdentityDescription -> state.copy(
        organizationManagement = state.organizationManagement.copy(
            serviceIdentityDraft = state.organizationManagement.serviceIdentityDraft.copy(
                description = action.value.take(2_000)
            )
        )
    )

    is IdentityUiAction.ChangeDeviceUserCode -> state.copy(
        deviceAuthorization = state.deviceAuthorization.copy(
            userCode = normalizeDeviceUserCodeDraft(action.value)
        )
    )

    is IdentityUiAction.ToggleServiceIdentityCapability -> {
        val draft = state.organizationManagement.serviceIdentityDraft
        if (draft.capabilityOptions.none { it.capability == action.capability }) state else {
            val selected = draft.selectedCapabilities.toggle(action.capability, action.selected)
            state.copy(
                organizationManagement = state.organizationManagement.copy(
                    serviceIdentityDraft = draft.copy(selectedCapabilities = selected)
                )
            )
        }
    }

    is IdentityUiAction.SelectDeviceOrganization -> {
        val approval = state.deviceApproval
        if (approval == null || approval.organizations.none { it.id == action.organizationId }) state else {
            state.copy(
                deviceApproval = approval.copy(
                    selectedOrganizationId = action.organizationId,
                    selectedCapabilities = emptySet()
                )
            )
        }
    }

    is IdentityUiAction.ToggleDeviceCapability -> {
        val approval = state.deviceApproval
        val organizationId = approval?.selectedOrganizationId
        if (approval == null || organizationId == null ||
            action.capability !in approval.approvableCapabilitiesByOrganization.getValue(organizationId)
        ) state else {
            state.copy(
                deviceApproval = approval.copy(
                    selectedCapabilities = approval.selectedCapabilities.toggle(action.capability, action.selected)
                )
            )
        }
    }

    IdentityUiAction.ClearFeedback -> state.copy(feedback = null)
    else -> state
}

private fun Set<Capability>.toggle(capability: Capability, selected: Boolean): Set<Capability> =
    if (selected) this + capability else this - capability
