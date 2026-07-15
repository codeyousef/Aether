package codes.yousef.aether.auth.summon

import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.CredentialId
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IdentityUiStateTest {
    @Test
    fun layoutUsesMobileFirstBreakpoint() {
        assertEquals(IdentityLayoutClass.PHONE, identityLayoutClass(320))
        assertEquals(IdentityLayoutClass.PHONE, identityLayoutClass(767))
        assertEquals(IdentityLayoutClass.DESKTOP, identityLayoutClass(768))
        assertEquals(IdentityLayoutClass.DESKTOP, identityLayoutClass(1_440))

        val responsiveStyles = identityResponsiveColumnsModifier().styles
        assertEquals("100%", responsiveStyles["width"])
        assertEquals("0", responsiveStyles["min-width"])
        assertEquals("border-box", responsiveStyles["boxSizing"])
    }

    @Test
    fun reducerChangesOnlyBrowserSafeDraftState() {
        val credentialId = CredentialId("018f-ui-credential")
        val initial = IdentityUiState(
            registration = RegistrationUiState("Old key"),
            passkeys = listOf(
                PasskeyUiModel(
                    id = credentialId,
                    name = "Laptop",
                    createdAt = "2026-07-14T08:00:00Z"
                )
            ),
            recoveryCodes = visibleRecoveryCodes()
        )

        val registrationChanged = reduceIdentityUiState(
            initial,
            IdentityUiAction.ChangeRegistrationName("Security key")
        )
        val passkeyChanged = reduceIdentityUiState(
            registrationChanged,
            IdentityUiAction.ChangePasskeyName(credentialId, "Travel key")
        )
        val hidden = reduceIdentityUiState(passkeyChanged, IdentityUiAction.DismissRecoveryCodes)

        assertEquals("Security key", hidden.registration.passkeyName)
        assertEquals("Travel key", hidden.passkeys.single().renameDraft)
        assertIs<RecoveryCodesUiState.Hidden>(hidden.recoveryCodes)
    }

    @Test
    fun recoveryCodeStateRequiresTenValuesAndRedactsDiagnostics() {
        val state = visibleRecoveryCodes()

        assertEquals(10, state.codes.size)
        assertEquals("VisibleOnce(codes=[REDACTED])", state.toString())
        assertFalse(state.toString().contains("recovery-code-1"))
        assertTrue(state.codes.first().startsWith("recovery-code-"))
    }

    @Test
    fun oneTimeIdentitySecretIsRedactedAndDismissedLocally() {
        val raw = "svc_123456.abcdefghijklmnopqrstuvwxyz012345"
        val visible = OneTimeIdentitySecretUiState.VisibleOnce(
            OneTimeIdentitySecretKind.SERVICE_CREDENTIAL,
            "Release credential",
            raw
        )
        val initial = IdentityUiState(oneTimeSecret = visible)

        assertFalse(visible.toString().contains(raw))
        assertFalse(initial.toString().contains(raw))
        assertEquals(raw, visible.secret)
        assertIs<OneTimeIdentitySecretUiState.Hidden>(
            reduceIdentityUiState(initial, IdentityUiAction.DismissOneTimeSecret).oneTimeSecret
        )
    }

    @Test
    fun deviceUserCodeDraftIsFormattedBoundedAndRedacted() {
        val initial = IdentityUiState(signedInDisplayName = "Owner")
        val action = IdentityUiAction.ChangeDeviceUserCode("abcd-efgh-untrusted-tail")

        val changed = reduceIdentityUiState(initial, action)

        assertEquals("ABCD-EFGH", changed.deviceAuthorization.userCode)
        assertTrue(changed.deviceAuthorization.readyToResolve)
        assertFalse(action.toString().contains("abcd-efgh"))
        assertFalse(changed.deviceAuthorization.toString().contains("ABCD-EFGH"))
        assertFalse(changed.toString().contains("ABCD-EFGH"))
    }

    @Test
    fun organizationAndDeviceDraftReducersAcceptOnlyOfferedChoices() {
        val first = OrganizationUiModel(
            OrganizationId("org-first"),
            "First organization",
            "first-org",
            OrganizationRole.OWNER
        )
        val second = OrganizationUiModel(
            OrganizationId("org-second"),
            "Second organization",
            "second-org",
            OrganizationRole.ADMIN
        )
        val read = Capability.CONTENT_READ
        val publish = Capability.CONTENT_PUBLISH
        val initial = IdentityUiState(
            organizationManagement = OrganizationManagementUiState(
                organizations = listOf(first, second),
                selectedOrganizationId = first.id,
                memberships = listOf(
                    MembershipUiModel(
                        id = MembershipId("membership-first"),
                        organizationId = first.id,
                        userId = UserId("user-first"),
                        displayName = "Member",
                        role = OrganizationRole.VIEWER
                    )
                ),
                serviceIdentityDraft = ServiceIdentityDraftUiState(
                    capabilityOptions = listOf(CapabilityOptionUiModel(read, "Read content"))
                )
            ),
            deviceApproval = DeviceApprovalUiState(
                userCode = "ABCD-EFGH",
                clientName = "Aether CLI",
                expiresAt = "2026-07-14T10:00:00Z",
                organizations = listOf(first, second),
                approvableCapabilitiesByOrganization = mapOf(
                    first.id to setOf(read),
                    second.id to setOf(publish)
                ),
                capabilityOptions = listOf(
                    CapabilityOptionUiModel(read, "Read content"),
                    CapabilityOptionUiModel(publish, "Publish content")
                )
            )
        )
        assertFalse(initial.deviceApproval.toString().contains("ABCD-EFGH"))
        assertFalse(
            IdentityUiAction.ApproveDeviceAuthorization("ABCD-EFGH", first.id, setOf(read))
                .toString()
                .contains("ABCD-EFGH")
        )
        assertFalse(IdentityUiAction.DenyDeviceAuthorization("ABCD-EFGH").toString().contains("ABCD-EFGH"))

        val switched = reduceIdentityUiState(initial, IdentityUiAction.SelectOrganization(second.id))
        assertEquals(second.id, switched.organizationManagement.selectedOrganizationId)
        assertTrue(switched.organizationManagement.memberships.isEmpty())

        val deviceScoped = reduceIdentityUiState(
            reduceIdentityUiState(switched, IdentityUiAction.SelectDeviceOrganization(first.id)),
            IdentityUiAction.ToggleDeviceCapability(read, true)
        )
        assertEquals(first.id, deviceScoped.deviceApproval?.selectedOrganizationId)
        assertEquals(setOf(read), deviceScoped.deviceApproval?.selectedCapabilities)

        val changedDeviceOrganization = reduceIdentityUiState(
            deviceScoped,
            IdentityUiAction.SelectDeviceOrganization(second.id)
        )
        assertEquals(emptySet(), changedDeviceOrganization.deviceApproval?.selectedCapabilities)
        assertSame(
            changedDeviceOrganization,
            reduceIdentityUiState(
                changedDeviceOrganization,
                IdentityUiAction.ToggleDeviceCapability(read, true)
            )
        )

        val unknownOrganization = OrganizationId("org-not-offered")
        assertSame(
            deviceScoped,
            reduceIdentityUiState(deviceScoped, IdentityUiAction.SelectDeviceOrganization(unknownOrganization))
        )
        assertSame(
            deviceScoped,
            reduceIdentityUiState(
                deviceScoped,
                IdentityUiAction.ToggleDeviceCapability(Capability("application.not_offered"), true)
            )
        )
    }

    @Test
    fun deviceApprovalRequiresExplicitOfferedOrganizationAndRequestedScopes() {
        val organization = OrganizationUiModel(
            OrganizationId("org-device"),
            "Device organization",
            "device-org",
            OrganizationRole.PUBLISHER
        )
        val capability = Capability.CONTENT_READ

        assertFailsWith<IllegalArgumentException> {
            DeviceApprovalUiState(
                userCode = "ABCD-EFGH",
                clientName = "CLI",
                expiresAt = "2026-07-14T10:00:00Z",
                organizations = listOf(organization),
                approvableCapabilitiesByOrganization = mapOf(organization.id to setOf(capability)),
                selectedOrganizationId = OrganizationId("org-other"),
                capabilityOptions = listOf(CapabilityOptionUiModel(capability, "Read"))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceApprovalUiState(
                userCode = "ABCD-EFGH",
                clientName = "CLI",
                expiresAt = "2026-07-14T10:00:00Z",
                organizations = listOf(organization),
                approvableCapabilitiesByOrganization = mapOf(organization.id to setOf(capability)),
                selectedOrganizationId = organization.id,
                capabilityOptions = listOf(CapabilityOptionUiModel(capability, "Read")),
                selectedCapabilities = setOf(Capability.CONTENT_PUBLISH)
            )
        }
    }

    @Test
    fun organizationStateRejectsResourcesFromAnotherTenant() {
        val selected = OrganizationUiModel(
            OrganizationId("org-selected"),
            "Selected organization",
            "selected-org",
            OrganizationRole.OWNER
        )
        assertFailsWith<IllegalArgumentException> {
            OrganizationManagementUiState(
                organizations = listOf(selected),
                selectedOrganizationId = selected.id,
                memberships = listOf(
                    MembershipUiModel(
                        id = MembershipId("membership-other"),
                        organizationId = OrganizationId("org-other"),
                        userId = UserId("user-other"),
                        displayName = "Other member",
                        role = OrganizationRole.VIEWER
                    )
                )
            )
        }
    }

    private fun visibleRecoveryCodes(): RecoveryCodesUiState.VisibleOnce =
        RecoveryCodesUiState.VisibleOnce((1..10).map { "recovery-code-$it" })
}
