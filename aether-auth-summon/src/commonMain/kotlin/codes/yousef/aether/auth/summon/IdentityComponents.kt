package codes.yousef.aether.auth.summon

import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.InvitationState
import codes.yousef.aether.auth.MembershipState
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.ServiceCredentialState
import codes.yousef.aether.auth.ServiceIdentityState
import codes.yousef.summon.annotation.Composable
import codes.yousef.summon.components.display.Label
import codes.yousef.summon.components.display.Text
import codes.yousef.summon.components.html.Code
import codes.yousef.summon.components.html.H1
import codes.yousef.summon.components.html.H2
import codes.yousef.summon.components.html.H3
import codes.yousef.summon.components.html.Li
import codes.yousef.summon.components.html.Main
import codes.yousef.summon.components.html.P
import codes.yousef.summon.components.html.Section
import codes.yousef.summon.components.html.Ul
import codes.yousef.summon.components.input.Button
import codes.yousef.summon.components.input.ButtonVariant
import codes.yousef.summon.components.input.TextField
import codes.yousef.summon.components.layout.Column
import codes.yousef.summon.modifier.AlignItems
import codes.yousef.summon.modifier.BorderStyle
import codes.yousef.summon.modifier.Display
import codes.yousef.summon.modifier.FlexDirection
import codes.yousef.summon.modifier.MediaQuery
import codes.yousef.summon.modifier.Modifier
import codes.yousef.summon.modifier.alignItems
import codes.yousef.summon.modifier.ariaAttribute
import codes.yousef.summon.modifier.ariaChecked
import codes.yousef.summon.modifier.ariaDescribedBy
import codes.yousef.summon.modifier.ariaInvalid
import codes.yousef.summon.modifier.ariaLabel
import codes.yousef.summon.modifier.ariaLabelledBy
import codes.yousef.summon.modifier.autoFocus
import codes.yousef.summon.modifier.backgroundColor
import codes.yousef.summon.modifier.border
import codes.yousef.summon.modifier.borderRadius
import codes.yousef.summon.modifier.borderColor
import codes.yousef.summon.modifier.borderBottomWidth
import codes.yousef.summon.modifier.borderTopWidth
import codes.yousef.summon.modifier.borderStyle
import codes.yousef.summon.modifier.borderWidth
import codes.yousef.summon.modifier.color
import codes.yousef.summon.modifier.dataAttribute
import codes.yousef.summon.modifier.display
import codes.yousef.summon.modifier.fillMaxWidth
import codes.yousef.summon.modifier.flex
import codes.yousef.summon.modifier.flexDirection
import codes.yousef.summon.modifier.fontSize
import codes.yousef.summon.modifier.fontWeight
import codes.yousef.summon.modifier.gap
import codes.yousef.summon.modifier.id
import codes.yousef.summon.modifier.margin
import codes.yousef.summon.modifier.maxWidth
import codes.yousef.summon.modifier.mediaQuery
import codes.yousef.summon.modifier.minWidth
import codes.yousef.summon.modifier.padding
import codes.yousef.summon.modifier.role
import codes.yousef.summon.modifier.style
import codes.yousef.summon.modifier.tabIndex

private object IdentityUiIds {
    const val ROOT = "aether-identity"
    const val REGISTRATION_NAME = "aether-registration-name"
    const val REGISTRATION_HELP = "aether-registration-help"
    const val FEEDBACK = "aether-identity-feedback"
    const val RECOVERY_CODES = "aether-recovery-codes"
    const val ONE_TIME_SECRET = "aether-one-time-identity-secret"
    const val ADMIN_RECOVERY_USER = "aether-admin-recovery-user"
    const val ORGANIZATION_SELECTION = "aether-organization-selection"
    const val INVITATION_EMAIL = "aether-invitation-email"
    const val INVITATION_ROLE = "aether-invitation-role"
    const val SERVICE_IDENTITY_NAME = "aether-service-identity-name"
    const val SERVICE_IDENTITY_DESCRIPTION = "aether-service-identity-description"
    const val SERVICE_IDENTITY_SCOPES = "aether-service-identity-scopes"
    const val DEVICE_USER_CODE = "aether-device-user-code"
    const val DEVICE_USER_CODE_HELP = "aether-device-user-code-help"
    const val DEVICE_APPROVAL = "aether-device-approval"
    const val DEVICE_ORGANIZATION = "aether-device-organization"
    const val DEVICE_SCOPES = "aether-device-scopes"
}

/** Complete, mobile-first identity surface shared by JVM SSR and wasmJs hydration. */
@Composable
fun IdentityUi(
    state: IdentityUiState,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Main(
        modifier = identityRootModifier(modifier)
            .id(IdentityUiIds.ROOT)
            .ariaLabel("Aether identity")
    ) {
        H1(modifier = headingModifier()) { Text("Identity and security") }
        P { Text("Use passkeys to sign in and protect this account. Passwords are not supported.") }

        IdentityFeedback(state.feedback)

        Column(modifier = identityResponsiveColumnsModifier()) {
            IdentityEntryPanel(state.registration, state.busyAction, dispatcher)
            if (state.signedInDisplayName != null) {
                IdentityManagementPanel(state, dispatcher)
            }
        }
    }
}

@Composable
fun IdentityEntryPanel(
    state: RegistrationUiState,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Column(modifier = panelModifier(Modifier().flex(1, 1, "0").then(modifier)).ariaLabel("Passkey access")) {
        H2(modifier = headingModifier()) { Text("Passkey access") }

        val registrationLabelId = "${IdentityUiIds.REGISTRATION_NAME}-label"
        Label(
            "Passkey name",
            modifier = Modifier().id(registrationLabelId),
            forElement = IdentityUiIds.REGISTRATION_NAME
        )
        TextField(
            value = state.passkeyName,
            onValueChange = { dispatcher.dispatch(IdentityUiAction.ChangeRegistrationName(it)) },
            label = "Passkey name",
            placeholder = "For example, Personal security key",
            isEnabled = state.enabled && busyAction == null,
            modifier = Modifier()
                .id(IdentityUiIds.REGISTRATION_NAME)
                .ariaLabel("Passkey name")
                .ariaLabelledBy(registrationLabelId)
                .ariaDescribedBy(IdentityUiIds.REGISTRATION_HELP)
                .responsiveControl()
        )
        P(modifier = Modifier().id(IdentityUiIds.REGISTRATION_HELP)) {
            Text("Choose a name that helps you recognize this passkey later.")
        }
        Button(
            onClick = { dispatcher.dispatch(IdentityUiAction.RegisterPasskey) },
            label = if (busyAction == IdentityUiActionKind.REGISTER_PASSKEY) "Creating passkey…" else "Create passkey",
            disabled = !state.enabled || state.passkeyName.isBlank() || busyAction != null,
            dataAttributes = mapOf("identity-action" to "register-passkey"),
            modifier = actionButtonModifier("Create a passkey named ${state.passkeyName.ifBlank { "new passkey" }}")
        )
        if (state.signInEnabled) {
            Button(
                onClick = { dispatcher.dispatch(IdentityUiAction.DiscoverableSignIn) },
                label = if (busyAction == IdentityUiActionKind.SIGN_IN) "Signing in…" else "Sign in with a passkey",
                variant = ButtonVariant.SECONDARY,
                disabled = busyAction != null,
                dataAttributes = mapOf("identity-action" to "discoverable-sign-in"),
                modifier = actionButtonModifier("Sign in with a discoverable passkey")
            )
        }
    }
}

@Composable
fun IdentityManagementPanel(
    state: IdentityUiState,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Column(modifier = Modifier().responsiveContainer().flex(2, 1, "0").gap("1rem").then(modifier)) {
        H2(modifier = headingModifier()) { Text("Security for ${state.signedInDisplayName}") }
        if (state.deviceApproval == null) {
            DeviceAuthorizationPanel(state.deviceAuthorization, state.busyAction, dispatcher)
        } else {
            DeviceApprovalPanel(state.deviceApproval, state.busyAction, dispatcher)
        }
        OneTimeIdentitySecretPanel(state.oneTimeSecret, dispatcher)
        if (state.organizationManagement.organizations.isNotEmpty()) {
            OrganizationManagementPanel(state.organizationManagement, state.busyAction, dispatcher)
        }
        PasskeyManagementPanel(state.passkeys, state.busyAction, dispatcher)
        SessionManagementPanel(state.sessions, state.busyAction, dispatcher)
        RecoveryCodesPanel(state.recoveryCodes, state.busyAction, dispatcher)
        AdministrativeRecoveryPanel(state.administrativeRecovery, state.busyAction, dispatcher)
        StepUpPanel(state.stepUp, state.busyAction, dispatcher)
    }
}

@Composable
fun PasskeyManagementPanel(
    passkeys: List<PasskeyUiModel>,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Section(modifier = panelModifier(modifier).ariaLabel("Passkey management")) {
        H3(modifier = headingModifier()) { Text("Your passkeys") }
        if (passkeys.isEmpty()) {
            P { Text("No passkeys are enrolled.") }
        } else {
            Ul(modifier = listModifier()) {
                passkeys.forEach { passkey ->
                    PasskeyRow(passkey, busyAction, dispatcher)
                }
            }
        }
    }
}

@Composable
fun PasskeyRow(
    passkey: PasskeyUiModel,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    val inputId = "passkey-name-${passkey.id.value}"
    val descriptionId = "passkey-description-${passkey.id.value}"
    Li(modifier = listItemModifier(modifier).dataAttribute("credential-id", passkey.id.value)) {
        val labelId = "$inputId-label"
        Label("Name for ${passkey.name}", modifier = Modifier().id(labelId), forElement = inputId)
        TextField(
            value = passkey.renameDraft,
            onValueChange = { dispatcher.dispatch(IdentityUiAction.ChangePasskeyName(passkey.id, it)) },
            label = "Passkey name",
            isEnabled = busyAction == null,
            modifier = Modifier()
                .id(inputId)
                .ariaLabel("Name for passkey ${passkey.name}")
                .ariaLabelledBy(labelId)
                .ariaDescribedBy(descriptionId)
                .responsiveControl()
        )
        P(modifier = Modifier().id(descriptionId)) {
            val backup = if (passkey.backedUp) "Synced passkey." else "Device-bound or backup status unavailable."
            val lastUsed = passkey.lastUsedAt?.let { " Last used $it." } ?: " Not used yet."
            Text("Created ${passkey.createdAt}. $backup$lastUsed")
        }
        Button(
            onClick = { dispatcher.dispatch(IdentityUiAction.RenamePasskey(passkey.id)) },
            label = "Save name",
            variant = ButtonVariant.SECONDARY,
            disabled = busyAction != null || passkey.renameDraft.isBlank() || passkey.renameDraft == passkey.name,
            dataAttributes = mapOf("identity-action" to "rename-passkey"),
            modifier = actionButtonModifier("Save the new name for ${passkey.name}")
        )
        Button(
            onClick = { dispatcher.dispatch(IdentityUiAction.RevokePasskey(passkey.id)) },
            label = "Revoke passkey",
            variant = ButtonVariant.DANGER,
            disabled = busyAction != null || !passkey.canRevoke,
            dataAttributes = mapOf("identity-action" to "revoke-passkey"),
            modifier = actionButtonModifier("Revoke passkey ${passkey.name}")
        )
    }
}

@Composable
fun SessionManagementPanel(
    sessions: List<SessionUiModel>,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Section(modifier = panelModifier(modifier).ariaLabel("Session and device management")) {
        H3(modifier = headingModifier()) { Text("Sessions and devices") }
        if (sessions.isEmpty()) {
            P { Text("No active sessions.") }
        } else {
            Ul(modifier = listModifier()) {
                sessions.forEach { session ->
                    Li(modifier = listItemModifier().dataAttribute("session-id", session.id.value)) {
                        Text(
                            if (session.current) "${session.deviceLabel} — this device" else session.deviceLabel,
                            modifier = Modifier().fontWeight(600)
                        )
                        P {
                            val recent = if (session.recentPasskey) " Recent passkey verification." else ""
                            Text("Last active ${session.lastUsedAt}; expires ${session.expiresAt}.$recent")
                        }
                        Button(
                            onClick = { dispatcher.dispatch(IdentityUiAction.RevokeSession(session.id)) },
                            label = if (session.current) "Sign out this device" else "Revoke device",
                            variant = ButtonVariant.DANGER,
                            disabled = busyAction != null,
                            dataAttributes = mapOf("identity-action" to "revoke-session"),
                            modifier = actionButtonModifier(
                                if (session.current) "Sign out this device" else "Revoke ${session.deviceLabel}"
                            )
                        )
                    }
                }
            }
            Button(
                onClick = { dispatcher.dispatch(IdentityUiAction.RevokeOtherSessions) },
                label = "Revoke other devices",
                variant = ButtonVariant.DANGER,
                disabled = busyAction != null || sessions.none { !it.current },
                dataAttributes = mapOf("identity-action" to "revoke-other-sessions"),
                modifier = actionButtonModifier("Revoke every session except this device")
            )
            Button(
                onClick = { dispatcher.dispatch(IdentityUiAction.RevokeAllSessions) },
                label = "Revoke all sessions",
                variant = ButtonVariant.DANGER,
                disabled = busyAction != null,
                dataAttributes = mapOf("identity-action" to "revoke-all-sessions"),
                modifier = actionButtonModifier("Revoke all sessions, including this device")
            )
        }
    }
}

@Composable
fun RecoveryCodesPanel(
    state: RecoveryCodesUiState,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Section(modifier = panelModifier(modifier).ariaLabel("Recovery codes")) {
        H3(modifier = headingModifier()) { Text("Recovery codes") }
        when (state) {
            RecoveryCodesUiState.Hidden -> {
                P { Text("Generate ten single-use codes for account recovery. Existing unused codes will stop working.") }
                Button(
                    onClick = { dispatcher.dispatch(IdentityUiAction.GenerateRecoveryCodes) },
                    label = if (busyAction == IdentityUiActionKind.GENERATE_RECOVERY_CODES) {
                        "Generating recovery codes…"
                    } else {
                        "Generate recovery codes"
                    },
                    disabled = busyAction != null,
                    dataAttributes = mapOf("identity-action" to "generate-recovery-codes"),
                    modifier = actionButtonModifier("Generate a new set of recovery codes")
                )
            }

            is RecoveryCodesUiState.VisibleOnce -> {
                Column(
                    modifier = Modifier()
                        .id(IdentityUiIds.RECOVERY_CODES)
                        .role("region")
                        .ariaLabel("New recovery codes")
                        .ariaAttribute("live", "assertive")
                        .tabIndex(-1)
                        .autoFocus()
                        .responsiveContainer()
                        .padding("1rem")
                        .backgroundColor("#fff8dc")
                        .border("2px", "solid", "#8a5a00")
                        .borderRadius("0.5rem")
                ) {
                    Text("Save these codes now. They will not be shown again.", modifier = Modifier().fontWeight(700))
                    Ul(modifier = listModifier()) {
                        state.codes.forEachIndexed { index, code ->
                            Li {
                                Code(modifier = Modifier().ariaLabel("Recovery code ${index + 1}")) {
                                    Text(code)
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { dispatcher.dispatch(IdentityUiAction.DismissRecoveryCodes) },
                        label = "I saved these codes",
                        dataAttributes = mapOf("identity-action" to "dismiss-recovery-codes"),
                        modifier = actionButtonModifier("Confirm that the recovery codes are saved")
                    )
                }
            }
        }
    }
}

@Composable
fun OneTimeIdentitySecretPanel(
    state: OneTimeIdentitySecretUiState,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    if (state !is OneTimeIdentitySecretUiState.VisibleOnce) return
    Section(
        modifier = panelModifier(modifier)
            .id(IdentityUiIds.ONE_TIME_SECRET)
            .role("region")
            .ariaLabel("New one-time identity secret")
            .ariaAttribute("live", "assertive")
            .tabIndex(-1)
            .autoFocus()
    ) {
        H3(modifier = headingModifier()) {
            Text(
                when (state.kind) {
                    OneTimeIdentitySecretKind.INVITATION_TOKEN -> "New invitation token"
                    OneTimeIdentitySecretKind.SERVICE_CREDENTIAL -> "New service credential"
                }
            )
        }
        P { Text("${state.label}. Copy this value now. It will not be shown again after dismissal or navigation.") }
        Code(modifier = Modifier().ariaLabel("One-time secret value")) { Text(state.secret) }
        Button(
            onClick = { dispatcher.dispatch(IdentityUiAction.DismissOneTimeSecret) },
            label = "I saved this value",
            dataAttributes = mapOf("identity-action" to "dismiss-one-time-secret"),
            modifier = actionButtonModifier("Confirm that the one-time identity secret is saved")
        )
    }
}

@Composable
fun AdministrativeRecoveryPanel(
    state: AdministrativeRecoveryUiState,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Section(modifier = panelModifier(modifier).ariaLabel("Administrative recovery")) {
        H3(modifier = headingModifier()) { Text("Administrative recovery") }
        if (!state.enabled) {
            P { Text("Administrative recovery is not configured.") }
            return@Section
        }

        val administrativeRecoveryLabelId = "${IdentityUiIds.ADMIN_RECOVERY_USER}-label"
        Label(
            "User ID",
            modifier = Modifier().id(administrativeRecoveryLabelId),
            forElement = IdentityUiIds.ADMIN_RECOVERY_USER
        )
        TextField(
            value = state.userQuery,
            onValueChange = { dispatcher.dispatch(IdentityUiAction.ChangeAdministrativeRecoveryUser(it)) },
            label = "User ID",
            placeholder = "018f47d2-8d4d-7abc-8def-1234567890ab",
            isEnabled = busyAction == null && state.outstandingTicket == null,
            modifier = Modifier()
                .id(IdentityUiIds.ADMIN_RECOVERY_USER)
                .ariaLabel("User ID for administrative recovery")
                .ariaLabelledBy(administrativeRecoveryLabelId)
                .ariaInvalid(state.userQuery.isBlank())
                .responsiveControl()
        )
        state.deliveryStatus?.let {
            Text(it, modifier = Modifier().role("status").ariaAttribute("live", "polite"))
        }

        val ticket = state.outstandingTicket
        if (ticket == null) {
            Button(
                onClick = { dispatcher.dispatch(IdentityUiAction.IssueAdministrativeRecovery) },
                label = if (busyAction == IdentityUiActionKind.ADMINISTRATIVE_RECOVERY) {
                    "Issuing enrollment link…"
                } else {
                    "Issue enrollment link"
                },
                disabled = busyAction != null || state.userQuery.isBlank(),
                dataAttributes = mapOf("identity-action" to "issue-administrative-recovery"),
                modifier = actionButtonModifier("Issue a single-use passkey enrollment link")
            )
        } else {
            P { Text("Enrollment link ${ticket.id.value} expires ${ticket.expiresAt}.") }
            Button(
                onClick = { dispatcher.dispatch(IdentityUiAction.CancelAdministrativeRecovery(ticket.id)) },
                label = "Cancel enrollment link",
                variant = ButtonVariant.DANGER,
                disabled = busyAction != null,
                dataAttributes = mapOf("identity-action" to "cancel-administrative-recovery"),
                modifier = actionButtonModifier("Cancel the outstanding enrollment link")
            )
        }
    }
}

@Composable
fun StepUpPanel(
    state: StepUpUiState,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Section(modifier = panelModifier(modifier).ariaLabel("Recent passkey verification")) {
        H3(modifier = headingModifier()) { Text("Recent passkey verification") }
        when {
            state.satisfiedAt != null -> P { Text("Verified with a passkey at ${state.satisfiedAt}.") }
            state.required -> P { Text(state.reason ?: "Verify with a passkey before continuing.") }
            else -> P { Text("Some sensitive actions require a passkey used within the last five minutes.") }
        }
        Button(
            onClick = { dispatcher.dispatch(IdentityUiAction.StepUpWithPasskey) },
            label = if (busyAction == IdentityUiActionKind.STEP_UP) "Verifying…" else "Verify with passkey",
            disabled = busyAction != null,
            dataAttributes = mapOf("identity-action" to "step-up"),
            modifier = actionButtonModifier("Verify this session with a passkey")
        )
    }
}

@Composable
fun OrganizationManagementPanel(
    state: OrganizationManagementUiState,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Section(modifier = panelModifier(modifier).ariaLabel("Organization identity management")) {
        H3(modifier = headingModifier()) { Text("Organizations") }
        P { Text("Choose an organization before managing its members, invitations, or service identities.") }
        OrganizationSelection(
            organizations = state.organizations,
            selectedOrganizationId = state.selectedOrganizationId,
            enabled = busyAction == null,
            dispatcher = dispatcher
        )

        val selected = state.organizations.firstOrNull { it.id == state.selectedOrganizationId }
        if (selected == null) {
            P(modifier = Modifier().role("status")) { Text("No organization selected.") }
            return@Section
        }

        P(modifier = Modifier().fontWeight(600)) {
            Text("Managing ${selected.name} as ${roleLabel(selected.role)}.")
        }
        MembershipManagement(
            memberships = state.memberships,
            busyAction = busyAction,
            dispatcher = dispatcher
        )
        InvitationManagement(
            organizationId = selected.id,
            draft = state.invitationDraft,
            invitations = state.invitations,
            canManage = state.canInviteMembers,
            busyAction = busyAction,
            dispatcher = dispatcher
        )
        ServiceIdentityManagement(
            organizationId = selected.id,
            draft = state.serviceIdentityDraft,
            serviceIdentities = state.serviceIdentities,
            canManage = state.canManageServiceIdentities,
            busyAction = busyAction,
            dispatcher = dispatcher
        )
    }
}

@Composable
fun OrganizationSelection(
    organizations: List<OrganizationUiModel>,
    selectedOrganizationId: OrganizationId?,
    enabled: Boolean,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Column(
        modifier = Modifier()
            .id(IdentityUiIds.ORGANIZATION_SELECTION)
            .role("radiogroup")
            .ariaLabel("Organization selection")
            .responsiveContainer()
            .gap("0.5rem")
            .then(modifier)
    ) {
        organizations.forEach { organization ->
            val selected = organization.id == selectedOrganizationId
            Button(
                onClick = { dispatcher.dispatch(IdentityUiAction.SelectOrganization(organization.id)) },
                label = "${organization.name} — ${roleLabel(organization.role)}",
                variant = ButtonVariant.SECONDARY,
                disabled = !enabled,
                dataAttributes = mapOf(
                    "identity-action" to "select-organization",
                    "organization-id" to organization.id.value
                ),
                modifier = choiceButtonModifier(
                    selected = selected,
                    accessibleName = "${if (selected) "Selected: " else "Select "}${organization.name} organization"
                )
            )
        }
    }
}

@Composable
fun MembershipManagement(
    memberships: List<MembershipUiModel>,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Column(modifier = subPanelModifier(modifier).ariaLabel("Membership management")) {
        H3(modifier = headingModifier()) { Text("Members") }
        if (memberships.isEmpty()) {
            P { Text("No memberships are available.") }
            return@Column
        }
        Ul(modifier = listModifier()) {
            memberships.forEach { membership ->
                Li(modifier = listItemModifier().dataAttribute("membership-id", membership.id.value)) {
                    Text(membership.displayName, modifier = Modifier().fontWeight(600))
                    P {
                        val address = membership.email?.let { " — $it" }.orEmpty()
                        Text("${roleLabel(membership.role)}$address; ${membership.state.name.lowercase()}.")
                    }
                    if (membership.canChangeRole && membership.state == MembershipState.ACTIVE) {
                        RoleChoices(
                            groupLabel = "Role for ${membership.displayName}",
                            selectedRole = membership.role,
                            roles = membership.allowedRoles,
                            enabled = busyAction == null,
                            actionName = "change-membership-role"
                        ) { role ->
                            dispatcher.dispatch(
                                IdentityUiAction.ChangeMembershipRole(membership.organizationId, membership.id, role)
                            )
                        }
                    }
                    if (membership.canRemove) {
                        Button(
                            onClick = {
                                dispatcher.dispatch(
                                    IdentityUiAction.RemoveMembership(membership.organizationId, membership.id)
                                )
                            },
                            label = "Remove member",
                            variant = ButtonVariant.DANGER,
                            disabled = busyAction != null || membership.state != MembershipState.ACTIVE,
                            dataAttributes = mapOf("identity-action" to "remove-membership"),
                            modifier = actionButtonModifier("Remove ${membership.displayName} from this organization")
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InvitationManagement(
    organizationId: OrganizationId,
    draft: InvitationDraftUiState,
    invitations: List<InvitationUiModel>,
    canManage: Boolean,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Column(modifier = subPanelModifier(modifier).ariaLabel("Invitation management")) {
        H3(modifier = headingModifier()) { Text("Invitations") }
        if (canManage) {
            val invitationEmailLabelId = "${IdentityUiIds.INVITATION_EMAIL}-label"
            Label(
                "Invitee email",
                modifier = Modifier().id(invitationEmailLabelId),
                forElement = IdentityUiIds.INVITATION_EMAIL
            )
            TextField(
                value = draft.email,
                onValueChange = { dispatcher.dispatch(IdentityUiAction.ChangeInvitationEmail(it)) },
                label = "Invitee email",
                placeholder = "member@example.com",
                isEnabled = busyAction == null,
                modifier = Modifier()
                    .id(IdentityUiIds.INVITATION_EMAIL)
                    .ariaLabel("Invitee email")
                    .ariaLabelledBy(invitationEmailLabelId)
                    .ariaInvalid(draft.email.isBlank())
                    .responsiveControl()
            )
            RoleChoices(
                groupLabel = "Invitation role",
                selectedRole = draft.role,
                roles = draft.allowedRoles,
                enabled = busyAction == null,
                actionName = "change-invitation-role",
                modifier = Modifier().id(IdentityUiIds.INVITATION_ROLE)
            ) { role -> dispatcher.dispatch(IdentityUiAction.ChangeInvitationRole(role)) }
            Button(
                onClick = { dispatcher.dispatch(IdentityUiAction.InviteMember(organizationId)) },
                label = if (busyAction == IdentityUiActionKind.INVITE_MEMBER) {
                    "Sending invitation…"
                } else {
                    "Send invitation"
                },
                disabled = busyAction != null || draft.email.isBlank(),
                dataAttributes = mapOf("identity-action" to "invite-member"),
                modifier = actionButtonModifier(
                    "Invite ${draft.email.ifBlank { "a member" }} as ${roleLabel(draft.role)}"
                )
            )
        }

        if (invitations.isNotEmpty()) {
            Ul(modifier = listModifier()) {
                invitations.forEach { invitation ->
                    Li(modifier = listItemModifier().dataAttribute("invitation-id", invitation.id.value)) {
                        Text(invitation.email, modifier = Modifier().fontWeight(600))
                        P {
                            Text(
                                "${roleLabel(invitation.role)} invitation; ${invitation.state.name.lowercase()}; " +
                                    "expires ${invitation.expiresAt}."
                            )
                        }
                        if (invitation.canRevoke && invitation.state == InvitationState.PENDING) {
                            Button(
                                onClick = {
                                    dispatcher.dispatch(
                                        IdentityUiAction.RevokeInvitation(invitation.organizationId, invitation.id)
                                    )
                                },
                                label = "Revoke invitation",
                                variant = ButtonVariant.DANGER,
                                disabled = busyAction != null,
                                dataAttributes = mapOf("identity-action" to "revoke-invitation"),
                                modifier = actionButtonModifier("Revoke invitation for ${invitation.email}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ServiceIdentityManagement(
    organizationId: OrganizationId,
    draft: ServiceIdentityDraftUiState,
    serviceIdentities: List<ServiceIdentityUiModel>,
    canManage: Boolean,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Column(modifier = subPanelModifier(modifier).ariaLabel("Service identity management")) {
        H3(modifier = headingModifier()) { Text("Service identities") }
        P { Text("Credentials are organization-bound, scoped, expiring, and shown only when issued.") }
        if (canManage) {
            val serviceIdentityNameLabelId = "${IdentityUiIds.SERVICE_IDENTITY_NAME}-label"
            Label(
                "Service identity name",
                modifier = Modifier().id(serviceIdentityNameLabelId),
                forElement = IdentityUiIds.SERVICE_IDENTITY_NAME
            )
            TextField(
                value = draft.name,
                onValueChange = { dispatcher.dispatch(IdentityUiAction.ChangeServiceIdentityName(it)) },
                label = "Service identity name",
                placeholder = "Release automation",
                isEnabled = busyAction == null,
                modifier = Modifier()
                    .id(IdentityUiIds.SERVICE_IDENTITY_NAME)
                    .ariaLabel("Service identity name")
                    .ariaLabelledBy(serviceIdentityNameLabelId)
                    .responsiveControl()
            )
            val serviceIdentityDescriptionLabelId = "${IdentityUiIds.SERVICE_IDENTITY_DESCRIPTION}-label"
            Label(
                "Service identity description",
                modifier = Modifier().id(serviceIdentityDescriptionLabelId),
                forElement = IdentityUiIds.SERVICE_IDENTITY_DESCRIPTION
            )
            TextField(
                value = draft.description,
                onValueChange = { dispatcher.dispatch(IdentityUiAction.ChangeServiceIdentityDescription(it)) },
                label = "Service identity description",
                placeholder = "What this automation is allowed to do",
                isEnabled = busyAction == null,
                modifier = Modifier()
                    .id(IdentityUiIds.SERVICE_IDENTITY_DESCRIPTION)
                    .ariaLabel("Service identity description")
                    .ariaLabelledBy(serviceIdentityDescriptionLabelId)
                    .responsiveControl()
            )
            CapabilityChoices(
                groupId = IdentityUiIds.SERVICE_IDENTITY_SCOPES,
                groupLabel = "Service identity capabilities",
                options = draft.capabilityOptions,
                selectedCapabilities = draft.selectedCapabilities,
                enabled = busyAction == null,
                actionName = "toggle-service-identity-capability"
            ) { capability, selected ->
                dispatcher.dispatch(IdentityUiAction.ToggleServiceIdentityCapability(capability, selected))
            }
            Button(
                onClick = { dispatcher.dispatch(IdentityUiAction.CreateServiceIdentity(organizationId)) },
                label = if (busyAction == IdentityUiActionKind.CREATE_SERVICE_IDENTITY) {
                    "Creating service identity…"
                } else {
                    "Create service identity"
                },
                disabled = busyAction != null || draft.name.isBlank() || draft.selectedCapabilities.isEmpty(),
                dataAttributes = mapOf("identity-action" to "create-service-identity"),
                modifier = actionButtonModifier(
                    "Create the scoped service identity ${draft.name.ifBlank { "service identity" }}"
                )
            )
        }

        if (serviceIdentities.isNotEmpty()) {
            Ul(modifier = listModifier()) {
                serviceIdentities.forEach { identity ->
                    Li(modifier = listItemModifier().dataAttribute("service-identity-id", identity.id.value)) {
                        Text(identity.name, modifier = Modifier().fontWeight(600))
                        identity.description?.let { P { Text(it) } }
                        P {
                            Text(
                                "${identity.state.name.lowercase()}; capabilities: " +
                                    capabilitySummary(identity.capabilities)
                            )
                        }
                        if (identity.canManage && identity.state == ServiceIdentityState.ACTIVE) {
                            Button(
                                onClick = {
                                    dispatcher.dispatch(
                                        IdentityUiAction.CreateServiceCredential(identity.organizationId, identity.id)
                                    )
                                },
                                label = "Create credential",
                                variant = ButtonVariant.SECONDARY,
                                disabled = busyAction != null,
                                dataAttributes = mapOf("identity-action" to "create-service-credential"),
                                modifier = actionButtonModifier("Create a credential for ${identity.name}")
                            )
                            Button(
                                onClick = {
                                    dispatcher.dispatch(
                                        IdentityUiAction.RevokeServiceIdentity(identity.organizationId, identity.id)
                                    )
                                },
                                label = "Revoke service identity",
                                variant = ButtonVariant.DANGER,
                                disabled = busyAction != null,
                                dataAttributes = mapOf("identity-action" to "revoke-service-identity"),
                                modifier = actionButtonModifier("Revoke service identity ${identity.name}")
                            )
                        }
                        ServiceCredentialList(identity, busyAction, dispatcher)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceCredentialList(
    identity: ServiceIdentityUiModel,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher
) {
    if (identity.credentials.isEmpty()) return
    Ul(modifier = listModifier().ariaLabel("Credentials for ${identity.name}")) {
        identity.credentials.forEach { credential ->
            Li(modifier = listItemModifier().dataAttribute("service-credential-id", credential.id.value)) {
                Text("Credential ${credential.publicPrefix}", modifier = Modifier().fontWeight(600))
                P {
                    val expiry = credential.expiresAt?.let { "; expires $it" }.orEmpty()
                    Text(
                        "${credential.state.name.lowercase()}$expiry; capabilities: " +
                            capabilitySummary(credential.capabilities)
                    )
                }
                if (identity.canManage && credential.state == ServiceCredentialState.ACTIVE) {
                    Button(
                        onClick = {
                            dispatcher.dispatch(
                                IdentityUiAction.RotateServiceCredential(identity.organizationId, credential.id)
                            )
                        },
                        label = "Rotate credential",
                        variant = ButtonVariant.SECONDARY,
                        disabled = busyAction != null,
                        dataAttributes = mapOf("identity-action" to "rotate-service-credential"),
                        modifier = actionButtonModifier("Rotate credential ${credential.publicPrefix}")
                    )
                    Button(
                        onClick = {
                            dispatcher.dispatch(
                                IdentityUiAction.RevokeServiceCredential(identity.organizationId, credential.id)
                            )
                        },
                        label = "Revoke credential",
                        variant = ButtonVariant.DANGER,
                        disabled = busyAction != null,
                        dataAttributes = mapOf("identity-action" to "revoke-service-credential"),
                        modifier = actionButtonModifier("Revoke credential ${credential.publicPrefix}")
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceAuthorizationPanel(
    state: DeviceAuthorizationUiState,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Section(modifier = panelModifier(modifier).ariaLabel("Device authorization")) {
        H3(modifier = headingModifier()) { Text("Connect a device") }
        P { Text("Enter the code shown by the CLI. Device codes are never accepted in a URL.") }
        val labelId = "${IdentityUiIds.DEVICE_USER_CODE}-label"
        Label(
            "Device authorization code",
            modifier = Modifier().id(labelId),
            forElement = IdentityUiIds.DEVICE_USER_CODE
        )
        TextField(
            value = state.userCode,
            onValueChange = { dispatcher.dispatch(IdentityUiAction.ChangeDeviceUserCode(it)) },
            label = "Device authorization code",
            placeholder = "ABCD-EFGH",
            isEnabled = state.enabled && busyAction == null,
            modifier = Modifier()
                .id(IdentityUiIds.DEVICE_USER_CODE)
                .ariaLabel("Device authorization code")
                .ariaLabelledBy(labelId)
                .ariaDescribedBy(IdentityUiIds.DEVICE_USER_CODE_HELP)
                .responsiveControl()
        )
        P(modifier = Modifier().id(IdentityUiIds.DEVICE_USER_CODE_HELP)) {
            Text("The code contains eight characters and is formatted automatically.")
        }
        Button(
            onClick = { dispatcher.dispatch(IdentityUiAction.ResolveDeviceAuthorization) },
            label = if (busyAction == IdentityUiActionKind.RESOLVE_DEVICE) "Checking code…" else "Continue",
            disabled = !state.enabled || busyAction != null || !state.readyToResolve,
            dataAttributes = mapOf("identity-action" to "resolve-device"),
            modifier = actionButtonModifier("Continue with this device authorization code")
        )
    }
}

@Composable
fun DeviceApprovalPanel(
    state: DeviceApprovalUiState,
    busyAction: IdentityUiActionKind?,
    dispatcher: IdentityUiDispatcher,
    modifier: Modifier = Modifier()
) {
    Section(
        modifier = panelModifier(modifier)
            .id(IdentityUiIds.DEVICE_APPROVAL)
            .role("region")
            .ariaLabel("Device authorization approval")
            .ariaAttribute("live", "polite")
            .tabIndex(-1)
            .autoFocus()
    ) {
        H3(modifier = headingModifier()) { Text("Approve a device") }
        P { Text("${state.clientName} is requesting access. Confirm that it shows this code:") }
        Code(modifier = Modifier().ariaLabel("Device code ${state.userCode}").fontSize("1.25rem")) {
            Text(state.userCode)
        }
        P { Text("This request expires ${state.expiresAt}.") }
        P { Text("Choose exactly one organization for this grant. This choice is not a global workspace selection.") }
        Column(
            modifier = Modifier()
                .id(IdentityUiIds.DEVICE_ORGANIZATION)
                .role("radiogroup")
                .ariaLabel("Organization for device access")
                .responsiveContainer()
                .gap("0.5rem")
        ) {
            state.organizations.forEach { organization ->
                val selected = organization.id == state.selectedOrganizationId
                Button(
                    onClick = {
                        dispatcher.dispatch(IdentityUiAction.SelectDeviceOrganization(organization.id))
                    },
                    label = organization.name,
                    variant = ButtonVariant.SECONDARY,
                    disabled = !state.enabled || busyAction != null,
                    dataAttributes = mapOf(
                        "identity-action" to "select-device-organization",
                        "organization-id" to organization.id.value
                    ),
                    modifier = choiceButtonModifier(
                        selected,
                        "${if (selected) "Selected: " else "Grant device access to "}${organization.name}"
                    )
                )
            }
        }
        val approvableCapabilities = state.selectedOrganizationId?.let {
            state.approvableCapabilitiesByOrganization.getValue(it)
        }.orEmpty()
        if (state.selectedOrganizationId == null) {
            P(modifier = Modifier().role("status")) {
                Text("Choose an organization before selecting device scopes.")
            }
        }
        CapabilityChoices(
            groupId = IdentityUiIds.DEVICE_SCOPES,
            groupLabel = "Scopes approved for this device",
            options = state.capabilityOptions.filter { it.capability in approvableCapabilities },
            selectedCapabilities = state.selectedCapabilities,
            enabled = state.enabled && busyAction == null,
            actionName = "toggle-device-scope"
        ) { capability, selected ->
            dispatcher.dispatch(IdentityUiAction.ToggleDeviceCapability(capability, selected))
        }
        Button(
            onClick = {
                dispatcher.dispatch(
                    IdentityUiAction.ApproveDeviceAuthorization(
                        userCode = state.userCode,
                        organizationId = requireNotNull(state.selectedOrganizationId),
                        capabilities = state.selectedCapabilities
                    )
                )
            },
            label = if (busyAction == IdentityUiActionKind.APPROVE_DEVICE) "Approving device…" else "Approve device",
            disabled = !state.enabled || busyAction != null || state.selectedOrganizationId == null ||
                state.selectedCapabilities.isEmpty(),
            dataAttributes = mapOf("identity-action" to "approve-device"),
            modifier = actionButtonModifier("Approve this device for the selected organization and scopes")
        )
        Button(
            onClick = { dispatcher.dispatch(IdentityUiAction.DenyDeviceAuthorization(state.userCode)) },
            label = if (busyAction == IdentityUiActionKind.DENY_DEVICE) "Denying device…" else "Deny device",
            variant = ButtonVariant.DANGER,
            disabled = !state.enabled || busyAction != null,
            dataAttributes = mapOf("identity-action" to "deny-device"),
            modifier = actionButtonModifier("Deny this device authorization request")
        )
    }
}

@Composable
private fun RoleChoices(
    groupLabel: String,
    selectedRole: OrganizationRole,
    roles: Set<OrganizationRole>,
    enabled: Boolean,
    actionName: String,
    modifier: Modifier = Modifier(),
    onSelected: (OrganizationRole) -> Unit
) {
    Column(
        modifier = Modifier()
            .role("radiogroup")
            .ariaLabel(groupLabel)
            .responsiveChoiceGroup()
            .then(modifier)
    ) {
        OrganizationRole.entries.filter { it in roles }.forEach { role ->
            Button(
                onClick = { onSelected(role) },
                label = roleLabel(role),
                variant = ButtonVariant.SECONDARY,
                disabled = !enabled,
                dataAttributes = mapOf("identity-action" to actionName, "role" to role.wireName),
                modifier = choiceButtonModifier(role == selectedRole, "$groupLabel: ${roleLabel(role)}")
            )
        }
    }
}

@Composable
private fun CapabilityChoices(
    groupId: String,
    groupLabel: String,
    options: List<CapabilityOptionUiModel>,
    selectedCapabilities: Set<Capability>,
    enabled: Boolean,
    actionName: String,
    modifier: Modifier = Modifier(),
    onSelectionChanged: (Capability, Boolean) -> Unit
) {
    Column(
        modifier = Modifier()
            .id(groupId)
            .role("group")
            .ariaLabel(groupLabel)
            .responsiveContainer()
            .gap("0.5rem")
            .then(modifier)
    ) {
        options.forEach { option ->
            val descriptionId = "$groupId-${option.capability.wireName.replace('.', '-')}-description"
            val selected = option.capability in selectedCapabilities
            Button(
                onClick = { onSelectionChanged(option.capability, !selected) },
                label = option.label,
                variant = ButtonVariant.SECONDARY,
                disabled = !enabled,
                dataAttributes = mapOf(
                    "identity-action" to actionName,
                    "capability" to option.capability.wireName
                ),
                modifier = actionButtonModifier(option.label)
                    .role("checkbox")
                    .ariaChecked(selected)
                    .ariaDescribedBy(descriptionId)
                    .dataAttribute("selected", selected.toString())
            )
            option.description?.let {
                P(modifier = Modifier().id(descriptionId).margin("0 0 0.25rem 1.75rem")) { Text(it) }
            }
        }
    }
}

private fun roleLabel(role: OrganizationRole): String = when (role) {
    OrganizationRole.OWNER -> "Owner"
    OrganizationRole.ADMIN -> "Admin"
    OrganizationRole.PUBLISHER -> "Publisher"
    OrganizationRole.VIEWER -> "Viewer"
}

private fun capabilitySummary(capabilities: Set<Capability>): String =
    capabilities.map(Capability::wireName).sorted().joinToString().ifBlank { "none" }

@Composable
private fun IdentityFeedback(feedback: IdentityUiFeedback?, modifier: Modifier = Modifier()) {
    if (feedback == null) return
    val isError = feedback.severity == IdentityUiFeedbackSeverity.ERROR
    var feedbackModifier = modifier
        .id(IdentityUiIds.FEEDBACK)
        .role(if (isError) "alert" else "status")
        .ariaAttribute("live", if (isError) "assertive" else "polite")
        .ariaAttribute("atomic", "true")
        .tabIndex(-1)
        .padding("0.75rem")
        .borderRadius("0.4rem")
        .backgroundColor(if (isError) "#fff0f0" else "#eef8ff")
        .color(if (isError) "#8b0000" else "#073b5c")
    if (isError) feedbackModifier = feedbackModifier.autoFocus()
    Text(feedback.message, modifier = feedbackModifier)
}

/** Mobile-first column which changes to two columns at the shared desktop breakpoint. */
fun identityResponsiveColumnsModifier(modifier: Modifier = Modifier()): Modifier = modifier
    .responsiveContainer()
    .display(Display.Flex)
    .flexDirection(FlexDirection.Column)
    .alignItems(AlignItems.Stretch)
    .gap("1rem")
    .mediaQuery(MediaQuery.MinWidth(DESKTOP_BREAKPOINT_PX)) {
        display(Display.Flex)
            .flexDirection(FlexDirection.Row)
            .alignItems(AlignItems.FlexStart)
            .gap("1.5rem")
    }

private fun identityRootModifier(modifier: Modifier): Modifier = Modifier()
    .responsiveContainer()
    .maxWidth("72rem")
    .margin("0 auto")
    .padding("1rem")
    .style("overflowWrap", "anywhere")
    .mediaQuery(MediaQuery.MinWidth(DESKTOP_BREAKPOINT_PX)) { padding("2rem") }
    .then(modifier)

private fun panelModifier(modifier: Modifier): Modifier = Modifier()
    .responsiveContainer()
    .padding("1rem")
    .border("1px", "solid", "#d7dde5")
    .borderRadius("0.75rem")
    .backgroundColor("#ffffff")
    .then(modifier)

private fun headingModifier(): Modifier = Modifier().margin("0 0 0.75rem 0")

private fun listModifier(): Modifier = Modifier().padding("0").margin("0").dataAttribute("identity-list", "true")

private fun listItemModifier(modifier: Modifier = Modifier()): Modifier = Modifier()
    .responsiveContainer()
    .padding("0.75rem 0")
    .borderWidth(0)
    .borderBottomWidth(1)
    .borderStyle(BorderStyle.Solid)
    .borderColor("#e5e9ef")
    .then(modifier)

private fun actionButtonModifier(accessibleName: String): Modifier = Modifier()
    .ariaLabel(accessibleName)
    .tabIndex(0)
    .fontSize("1rem")
    // Summon buttons have a fixed 0.25rem inline margin, so reserve that space at narrow widths.
    .maxWidth("calc(100% - 0.5rem)")
    .minWidth("0")
    .style("boxSizing", "border-box")
    .style("whiteSpace", "normal")

private fun choiceButtonModifier(selected: Boolean, accessibleName: String): Modifier =
    actionButtonModifier(accessibleName)
        .role("radio")
        .ariaChecked(selected)
        .dataAttribute("selected", selected.toString())

private fun subPanelModifier(modifier: Modifier): Modifier = Modifier()
    .responsiveContainer()
    .padding("1rem 0 0")
    .borderWidth(0)
    .borderTopWidth(1)
    .borderStyle(BorderStyle.Solid)
    .borderColor("#e5e9ef")
    .then(modifier)

private fun Modifier.responsiveContainer(): Modifier = this
    .fillMaxWidth()
    .minWidth("0")
    .style("boxSizing", "border-box")

private fun Modifier.responsiveControl(): Modifier = this
    .responsiveContainer()
    .maxWidth("100%")

private fun Modifier.responsiveChoiceGroup(): Modifier = this
    .responsiveContainer()
    .display(Display.Flex)
    .flexDirection(FlexDirection.Column)
    .gap("0.5rem")
    .mediaQuery(MediaQuery.MinWidth(DESKTOP_BREAKPOINT_PX)) {
        flexDirection(FlexDirection.Row)
            .style("flexWrap", "wrap")
    }
