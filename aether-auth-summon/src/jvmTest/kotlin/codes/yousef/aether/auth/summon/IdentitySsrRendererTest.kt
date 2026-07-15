package codes.yousef.aether.auth.summon

import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.CredentialId
import codes.yousef.aether.auth.InvitationId
import codes.yousef.aether.auth.InvitationState
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.ServiceCredentialId
import codes.yousef.aether.auth.ServiceCredentialState
import codes.yousef.aether.auth.ServiceIdentityId
import codes.yousef.aether.auth.ServiceIdentityState
import codes.yousef.aether.auth.SessionId
import codes.yousef.aether.auth.UserId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdentitySsrRendererTest {
    private val noOpDispatcher = IdentityUiDispatcher { }

    @Test
    fun rendersHydratableResponsiveAndKeyboardAccessibleShell() {
        val html = IdentitySsrRenderer().render(managementState(), noOpDispatcher)

        assertContains(html, "<!DOCTYPE html>")
        assertContains(html, "<html lang=\"en\" dir=\"ltr\">")
        assertContains(html, "data-ssr=\"true\"")
        assertContains(html, "id=\"summon-hydration-data\"")
        assertContains(html, "Identity and security")
        assertContains(html, "data-media-queries=\"(min-width: 768px)")
        assertContains(html, "data-identity-action=\"discoverable-sign-in\"")
        assertContains(html, "data-identity-action=\"rename-passkey\"")
        assertContains(html, "data-identity-action=\"revoke-session\"")
        assertContains(html, "data-identity-action=\"step-up\"")
        assertContains(html, "tabindex=\"0\"")
        assertContains(html, "aria-label=\"Sign in with a discoverable passkey\"")
        assertContains(html, "aria-describedby=\"aether-registration-help\"")
        assertContains(html, "data-onclick-id=")
        assertContains(html, "box-sizing: border-box")
        assertContains(html, "min-width: 0")
    }

    @Test
    fun errorAndOneTimeCodesHaveLiveRegionsAndManagedFocus() {
        val errorHtml = IdentitySsrRenderer().render(
            IdentityUiState(feedback = IdentityUiFeedback("Try again", IdentityUiFeedbackSeverity.ERROR)),
            noOpDispatcher
        )
        val recoveryHtml = IdentitySsrRenderer().render(
            managementState().copy(
                recoveryCodes = RecoveryCodesUiState.VisibleOnce((1..10).map { "code-$it-safe" })
            ),
            noOpDispatcher
        )
        val serviceCredentialHtml = IdentitySsrRenderer().render(
            managementState().copy(
                oneTimeSecret = OneTimeIdentitySecretUiState.VisibleOnce(
                    OneTimeIdentitySecretKind.SERVICE_CREDENTIAL,
                    "Credential svc_123456",
                    "svc_123456.abcdefghijklmnopqrstuvwxyz012345"
                )
            ),
            noOpDispatcher
        )

        assertContains(errorHtml, "role=\"alert\"")
        assertContains(errorHtml, "aria-live=\"assertive\"")
        assertContains(errorHtml, "autofocus=\"\"")
        assertContains(recoveryHtml, "role=\"region\"")
        assertContains(recoveryHtml, "aria-label=\"New recovery codes\"")
        assertContains(recoveryHtml, "Save these codes now. They will not be shown again.")
        assertContains(recoveryHtml, "code-10-safe")
        assertContains(recoveryHtml, "autofocus=\"\"")
        assertContains(serviceCredentialHtml, "aria-label=\"New one-time identity secret\"")
        assertContains(serviceCredentialHtml, "svc_123456.abcdefghijklmnopqrstuvwxyz012345")
        assertContains(serviceCredentialHtml, "data-identity-action=\"dismiss-one-time-secret\"")
        assertContains(serviceCredentialHtml, "autofocus=\"\"")
    }

    @Test
    fun ssrEscapesUntrustedLabelsAndDoesNotSerializeAuthorityState() {
        val html = IdentitySsrRenderer().render(
            managementState().copy(signedInDisplayName = "</script><script>steal()</script>"),
            noOpDispatcher
        )

        assertFalse(html.contains("</script><script>steal()"))
        assertTrue(html.contains("&lt;/script&gt;&lt;script&gt;steal()&lt;/script&gt;"))
        assertFalse(html.contains("IdentityRuntime"))
        assertFalse(html.contains("SecretReference"))
        assertFalse(html.contains("tokenDigest"))
        assertFalse(html.contains("<script id=\"summon-state\""))
    }

    @Test
    fun documentLocaleMetadataIsValidated() {
        assertFailsWith<IllegalArgumentException> {
            IdentitySsrRenderer().render(IdentityUiState(), noOpDispatcher, language = "en\" onload=\"bad")
        }
        assertFailsWith<IllegalArgumentException> {
            IdentitySsrRenderer().render(IdentityUiState(), noOpDispatcher, direction = "auto")
        }
    }

    @Test
    fun rendersExplicitTenantAndDeviceScopeControlsForKeyboardAndScreenReaders() {
        val html = IdentitySsrRenderer().render(managementState(), noOpDispatcher)

        assertContains(html, "aria-label=\"Organization selection\"")
        assertContains(html, "role=\"radiogroup\"")
        assertContains(html, "aria-checked=\"true\"")
        assertContains(html, "data-identity-action=\"select-organization\"")
        assertContains(html, "data-identity-action=\"change-membership-role\"")
        assertContains(html, "data-identity-action=\"invite-member\"")
        assertContains(html, "aria-label=\"Invitee email\"")
        assertContains(html, "data-identity-action=\"create-service-identity\"")
        assertContains(html, "aria-label=\"Service identity name\"")
        assertContains(html, "data-identity-action=\"rotate-service-credential\"")
        assertContains(html, "aria-label=\"Device authorization approval\"")
        assertContains(html, "aria-label=\"Organization for device access\"")
        assertContains(html, "aria-label=\"Scopes approved for this device\"")
        assertContains(html, "data-identity-action=\"approve-device\"")
        assertContains(html, "data-identity-action=\"deny-device\"")
        assertContains(html, "Device code ABCD-EFGH")
        assertContains(html, "autofocus=\"\"")

        // Browser state intentionally has the human code and public credential prefixes only.
        assertFalse(html.contains("device_code", ignoreCase = true))
        assertFalse(html.contains("access_token", ignoreCase = true))
        assertFalse(html.contains("refresh_token", ignoreCase = true))
        assertFalse(html.contains("secretDigest", ignoreCase = true))
        assertFalse(html.contains("IdentityRuntime"))
    }

    @Test
    fun rendersSignedInManualDeviceCodeEntryWithoutAUrlDerivedCode() {
        val html = IdentitySsrRenderer().render(
            managementState().copy(deviceApproval = null),
            noOpDispatcher
        )

        assertContains(html, "aria-label=\"Device authorization\"")
        assertContains(html, "aria-label=\"Device authorization code\"")
        assertContains(html, "data-identity-action=\"resolve-device\"")
        assertContains(html, "Device codes are never accepted in a URL.")
        assertFalse(html.contains("user_code="))
    }

    @Test
    fun readOnlyOrganizationDoesNotRenderMutationControls() {
        val management = managementState().organizationManagement
        val html = IdentitySsrRenderer().render(
            managementState().copy(
                organizationManagement = management.copy(
                    canInviteMembers = false,
                    canManageServiceIdentities = false,
                    invitations = management.invitations.map { it.copy(canRevoke = false) },
                    serviceIdentities = management.serviceIdentities.map { it.copy(canManage = false) }
                )
            ),
            noOpDispatcher
        )

        assertContains(html, "Invitations")
        assertContains(html, "Service identities")
        assertFalse(html.contains("data-identity-action=\"invite-member\""))
        assertFalse(html.contains("data-identity-action=\"create-service-identity\""))
        assertFalse(html.contains("data-identity-action=\"create-service-credential\""))
        assertFalse(html.contains("data-identity-action=\"revoke-service-identity\""))
    }

    @Test
    fun everyRenderedFullWidthSurfaceIsBorderBoxAtPhoneWidth() {
        val html = IdentitySsrRenderer().render(managementState(), noOpDispatcher)
        val fullWidthStyles = Regex("""style="([^"]*width:\s*100%[^"]*)"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(fullWidthStyles.size >= 10, "Expected the complete management surface to contain full-width regions")
        assertTrue(
            fullWidthStyles.all { "box-sizing:border-box" in it.replace(" ", "") },
            "A 100%-wide surface without border-box sizing can overflow a 390px viewport: $fullWidthStyles"
        )
        assertContains(html, "data-media-queries=\"(min-width: 768px)")
    }

    private fun managementState(): IdentityUiState {
        val organization = OrganizationUiModel(
            id = OrganizationId("018f-org-aether"),
            name = "Aether Engineering",
            slug = "aether-engineering",
            role = OrganizationRole.OWNER
        )
        val read = Capability.CONTENT_READ
        val publish = Capability.CONTENT_PUBLISH
        val capabilityOptions = listOf(
            CapabilityOptionUiModel(read, "Read packages", "Read package metadata from this organization."),
            CapabilityOptionUiModel(publish, "Publish packages", "Publish packages to this organization.")
        )
        return IdentityUiState(
        signedInDisplayName = "Aether User",
        registration = RegistrationUiState("Laptop passkey"),
        passkeys = listOf(
            PasskeyUiModel(
                id = CredentialId("018f-credential-ui"),
                name = "Laptop",
                createdAt = "2026-07-14T08:00:00Z",
                lastUsedAt = "2026-07-14T09:00:00Z",
                backedUp = true
            )
        ),
        sessions = listOf(
            SessionUiModel(
                id = SessionId("018f-session-current"),
                deviceLabel = "Firefox on Linux",
                lastUsedAt = "2026-07-14T09:00:00Z",
                expiresAt = "2026-08-13T09:00:00Z",
                current = true,
                recentPasskey = true
            ),
            SessionUiModel(
                id = SessionId("018f-session-phone"),
                deviceLabel = "Safari on phone",
                lastUsedAt = "2026-07-13T09:00:00Z",
                expiresAt = "2026-08-12T09:00:00Z"
            )
        ),
        administrativeRecovery = AdministrativeRecoveryUiState(enabled = true),
        stepUp = StepUpUiState(required = true, reason = "Confirm this destructive action"),
        organizationManagement = OrganizationManagementUiState(
            organizations = listOf(organization),
            selectedOrganizationId = organization.id,
            memberships = listOf(
                MembershipUiModel(
                    id = MembershipId("018f-membership-owner"),
                    organizationId = organization.id,
                    userId = UserId("018f-user-owner"),
                    displayName = "Aether User",
                    email = "owner@example.com",
                    role = OrganizationRole.OWNER,
                    canChangeRole = true,
                    canRemove = true
                )
            ),
            invitationDraft = InvitationDraftUiState("new-member@example.com", OrganizationRole.VIEWER),
            invitations = listOf(
                InvitationUiModel(
                    id = InvitationId("018f-invitation-viewer"),
                    organizationId = organization.id,
                    email = "pending@example.com",
                    role = OrganizationRole.VIEWER,
                    state = InvitationState.PENDING,
                    expiresAt = "2026-07-21T09:00:00Z"
                )
            ),
            serviceIdentityDraft = ServiceIdentityDraftUiState(
                name = "Release automation",
                description = "Publishes signed releases",
                capabilityOptions = capabilityOptions,
                selectedCapabilities = setOf(publish)
            ),
            serviceIdentities = listOf(
                ServiceIdentityUiModel(
                    id = ServiceIdentityId("018f-service-release"),
                    organizationId = organization.id,
                    name = "Release automation",
                    capabilities = setOf(read, publish),
                    state = ServiceIdentityState.ACTIVE,
                    credentials = listOf(
                        ServiceCredentialUiModel(
                            id = ServiceCredentialId("018f-service-credential"),
                            publicPrefix = "svc_123456",
                            capabilities = setOf(publish),
                            state = ServiceCredentialState.ACTIVE,
                            expiresAt = "2026-08-13T09:00:00Z"
                        )
                    ),
                    canManage = true
                )
            ),
            canInviteMembers = true,
            canManageServiceIdentities = true
        ),
        deviceApproval = DeviceApprovalUiState(
            userCode = "ABCD-EFGH",
            clientName = "Aether CLI",
            expiresAt = "2026-07-14T09:10:00Z",
            organizations = listOf(organization),
            approvableCapabilitiesByOrganization = mapOf(organization.id to setOf(read, publish)),
            selectedOrganizationId = organization.id,
            capabilityOptions = capabilityOptions,
            selectedCapabilities = setOf(read)
        )
    )
    }
}
