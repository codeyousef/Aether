@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package codes.yousef.aether.example

import codes.yousef.aether.auth.ApproveDeviceGrantRequest
import codes.yousef.aether.auth.AdministrativeRecoveryIssueRequest
import codes.yousef.aether.auth.AdministrativeRecoveryTicketView
import codes.yousef.aether.auth.AuthenticationAssurance
import codes.yousef.aether.auth.BootstrapIdentityRequest
import codes.yousef.aether.auth.BootstrapIdentityResponse
import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.CreateInvitationRequest
import codes.yousef.aether.auth.CreateServiceCredentialRequest
import codes.yousef.aether.auth.CreateServiceIdentityRequest
import codes.yousef.aether.auth.CredentialId
import codes.yousef.aether.auth.CredentialState
import codes.yousef.aether.auth.DenyDeviceGrantRequest
import codes.yousef.aether.auth.DeviceGrantView
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.aether.auth.IdentityErrorCode
import codes.yousef.aether.auth.IdentityErrorEnvelope
import codes.yousef.aether.auth.IdentityMeResponse
import codes.yousef.aether.auth.IdentitySessionCreatedResponse
import codes.yousef.aether.auth.IdentitySessionView
import codes.yousef.aether.auth.InspectDeviceGrantRequest
import codes.yousef.aether.auth.InvitationView
import codes.yousef.aether.auth.InvitationId
import codes.yousef.aether.auth.IssuedInvitationResponse
import codes.yousef.aether.auth.IssuedServiceCredentialResponse
import codes.yousef.aether.auth.IssuedServiceIdentityResponse
import codes.yousef.aether.auth.Membership
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.OrganizationAccessView
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.PasskeyAuthenticationFinishRequest
import codes.yousef.aether.auth.PasskeyRegistrationFinishRequest
import codes.yousef.aether.auth.PasskeyRegistrationResponse
import codes.yousef.aether.auth.PasskeyView
import codes.yousef.aether.auth.RecoveryCodeUseRequest
import codes.yousef.aether.auth.RecoveryCodesResponse
import codes.yousef.aether.auth.RenamePasskeyRequest
import codes.yousef.aether.auth.ReplaceRecoveryCodesRequest
import codes.yousef.aether.auth.RotateServiceCredentialRequest
import codes.yousef.aether.auth.ServiceCredentialView
import codes.yousef.aether.auth.ServiceCredentialId
import codes.yousef.aether.auth.ServiceIdentity
import codes.yousef.aether.auth.ServiceIdentityId
import codes.yousef.aether.auth.SessionState
import codes.yousef.aether.auth.SessionId
import codes.yousef.aether.auth.UpdateMembershipRoleRequest
import codes.yousef.aether.auth.UserId
import codes.yousef.aether.auth.summon.AdministrativeRecoveryTicketUiModel
import codes.yousef.aether.auth.summon.AdministrativeRecoveryUiState
import codes.yousef.aether.auth.summon.CapabilityOptionUiModel
import codes.yousef.aether.auth.summon.DeviceApprovalUiState
import codes.yousef.aether.auth.summon.IdentityUiAction
import codes.yousef.aether.auth.summon.IdentityUiActionKind
import codes.yousef.aether.auth.summon.IdentityUiDispatcher
import codes.yousef.aether.auth.summon.IdentityUiFeedback
import codes.yousef.aether.auth.summon.IdentityUiFeedbackSeverity
import codes.yousef.aether.auth.summon.IdentityUiState
import codes.yousef.aether.auth.summon.NavigatorCredentialsPasskeyClient
import codes.yousef.aether.auth.summon.OneTimeIdentitySecretKind
import codes.yousef.aether.auth.summon.OneTimeIdentitySecretUiState
import codes.yousef.aether.auth.summon.OrganizationManagementUiState
import codes.yousef.aether.auth.summon.OrganizationUiModel
import codes.yousef.aether.auth.summon.PasskeyAuthenticationPurpose
import codes.yousef.aether.auth.summon.PasskeyCeremonyClient
import codes.yousef.aether.auth.summon.PasskeyCeremonyGateway
import codes.yousef.aether.auth.summon.PasskeyUiModel
import codes.yousef.aether.auth.summon.RecoveryCodesUiState
import codes.yousef.aether.auth.summon.RegistrationUiState
import codes.yousef.aether.auth.summon.InvitationDraftUiState
import codes.yousef.aether.auth.summon.InvitationUiModel
import codes.yousef.aether.auth.summon.MembershipUiModel
import codes.yousef.aether.auth.summon.ServiceCredentialUiModel
import codes.yousef.aether.auth.summon.ServiceIdentityDraftUiState
import codes.yousef.aether.auth.summon.ServiceIdentityUiModel
import codes.yousef.aether.auth.summon.SessionUiModel
import codes.yousef.aether.auth.summon.StepUpUiState
import codes.yousef.aether.auth.summon.hydrateIdentityUi
import codes.yousef.aether.auth.summon.reduceIdentityUiState
import codes.yousef.aether.auth.webauthn.AuthenticationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.RegistrationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.WebAuthnAuthenticationStartResponse
import codes.yousef.aether.auth.webauthn.WebAuthnRegistrationStartResponse
import codes.yousef.summon.runtime.PlatformRenderer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Browser entry point for both JVM-rendered Summon shells. */
fun main() {
    val contract = IdentityExampleContract()
    val gateway = BrowserIdentityGateway(contract)
    if (browserElementExists(BOOTSTRAP_ROOT_ID)) {
        runBootstrapUi(contract, gateway)
    } else if (browserElementExists(RECOVERY_ROOT_ID)) {
        runRecoveryUi(contract, gateway)
    } else if (browserElementExists(IDENTITY_ROOT_ID)) {
        runIdentityUi(gateway)
    }
}

private fun runRecoveryUi(contract: IdentityExampleContract, gateway: BrowserIdentityGateway) {
    val scope = MainScope()
    var state = RecoveryIdentityUiState()
    lateinit var dispatcher: RecoveryIdentityUiDispatcher

    fun render() = PlatformRenderer().hydrateComposableRoot(SUMMON_HYDRATION_ROOT_ID) {
        RecoveryIdentityUi(state, dispatcher)
    }

    dispatcher = RecoveryIdentityUiDispatcher { action ->
        state = reduceRecoveryIdentityUiState(state, action)
        if (action != RecoveryIdentityUiAction.Submit) {
            render()
            return@RecoveryIdentityUiDispatcher
        }
        val request = state.toRequest() ?: run {
            render()
            return@RecoveryIdentityUiDispatcher
        }
        state = state.copy(busy = true, feedback = null, failed = false)
        render()
        scope.launch {
            try {
                gateway.recover(request)
                state = state.copy(
                    code = RecoveryCodeInput.Empty,
                    busy = false,
                    feedback = "Recovery accepted. Continuing to restricted passkey enrollment.",
                    failed = false
                )
                render()
                browserRedirect(contract.identityUi)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                state = state.copy(
                    busy = false,
                    feedback = "The recovery code is invalid, used, or expired.",
                    failed = true
                )
                render()
            }
        }
    }
    render()
}

private fun runBootstrapUi(contract: IdentityExampleContract, gateway: BrowserIdentityGateway) {
    val scope = MainScope()
    var state = BootstrapIdentityUiState()
    lateinit var dispatcher: BootstrapIdentityUiDispatcher

    fun render() = PlatformRenderer().hydrateComposableRoot(SUMMON_HYDRATION_ROOT_ID) {
        BootstrapIdentityUi(state, dispatcher)
    }

    dispatcher = BootstrapIdentityUiDispatcher { action ->
        state = reduceBootstrapIdentityUiState(state, action)
        if (action != BootstrapIdentityUiAction.Submit) {
            render()
            return@BootstrapIdentityUiDispatcher
        }
        val request = state.toRequest() ?: run {
            render()
            return@BootstrapIdentityUiDispatcher
        }
        state = state.copy(busy = true, feedback = null, failed = false)
        render()
        scope.launch {
            try {
                gateway.bootstrap(request)
                state = state.copy(
                    secret = BootstrapSecretInput.Empty,
                    busy = false,
                    feedback = "Owner created. Continuing to passkey enrollment.",
                    failed = false
                )
                render()
                browserRedirect(contract.identityUi)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                state = state.copy(
                    busy = false,
                    feedback = "Bootstrap failed. Verify the one-time secret and input values.",
                    failed = true
                )
                render()
            }
        }
    }
    render()
}

private fun runIdentityUi(gateway: BrowserIdentityGateway) {
    val client = IdentityExampleClient(
        passkeys = PasskeyCeremonyClient(gateway, NavigatorCredentialsPasskeyClient()),
        api = gateway
    )
    val scope = MainScope()
    var state = restrictedEnrollmentUiState()

    lateinit var dispatcher: IdentityUiDispatcher
    fun render() = hydrateIdentityUi(SUMMON_HYDRATION_ROOT_ID, state, dispatcher)
    fun runNetwork(
        kind: IdentityUiActionKind,
        signsOut: Boolean = false,
        operation: suspend () -> Unit
    ) {
        if (state.busyAction != null) return
        state = state.copy(busyAction = kind, feedback = null)
        render()
        scope.launch {
            state = try {
                operation()
                if (signsOut) {
                    IdentityUiState(
                        feedback = IdentityUiFeedback("Signed out. Use a passkey to sign in again.")
                    )
                } else {
                    val loaded = gateway.loadIdentityUiState()
                    if (loaded == null) {
                        clearBrowserCsrfToken()
                        IdentityUiState(
                            feedback = IdentityUiFeedback(
                                "The operation ended this session. Use a passkey to sign in again."
                            )
                        )
                    } else {
                        val message = when {
                            loaded.recoveryCodes is RecoveryCodesUiState.VisibleOnce ->
                                "Recovery codes replaced. Save these ten codes now."
                            loaded.oneTimeSecret is OneTimeIdentitySecretUiState.VisibleOnce ->
                                "A one-time value was issued. Save it before dismissing it."
                            else -> "Identity operation completed."
                        }
                        loaded.copy(
                            busyAction = null,
                            feedback = IdentityUiFeedback(message)
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val code = (failure as? BrowserIdentityApiException)?.code
                if (code == IdentityErrorCode.AUTHENTICATION_REQUIRED ||
                    code == IdentityErrorCode.SESSION_EXPIRED ||
                    code == IdentityErrorCode.SESSION_REVOKED
                ) {
                    clearBrowserCsrfToken()
                    IdentityUiState(
                        feedback = IdentityUiFeedback(
                            code.publicMessage,
                            IdentityUiFeedbackSeverity.ERROR
                        )
                    )
                } else {
                    state.copy(
                        busyAction = null,
                        recoveryCodes = gateway.currentRecoveryCodes(),
                        oneTimeSecret = gateway.currentOneTimeSecret(),
                        stepUp = if (code == IdentityErrorCode.STEP_UP_REQUIRED) {
                            state.stepUp.copy(required = true, reason = code.publicMessage)
                        } else {
                            state.stepUp
                        },
                        feedback = IdentityUiFeedback(
                            code?.publicMessage ?: "The identity operation could not be completed.",
                            IdentityUiFeedbackSeverity.ERROR
                        )
                    )
                }
            }
            render()
        }
    }

    dispatcher = IdentityUiDispatcher { action ->
        state = reduceIdentityUiState(state, action)
        when (action) {
            IdentityUiAction.RegisterPasskey -> runNetwork(IdentityUiActionKind.REGISTER_PASSKEY) {
                client.registerPasskey(state.registration.passkeyName)
                if (browserEnrollmentPending()) {
                    client.discoverableSignIn()
                    clearBrowserEnrollmentPending()
                }
            }
            IdentityUiAction.DiscoverableSignIn -> runNetwork(IdentityUiActionKind.SIGN_IN) {
                client.discoverableSignIn()
            }
            IdentityUiAction.StepUpWithPasskey -> runNetwork(IdentityUiActionKind.STEP_UP) {
                client.stepUp()
            }
            IdentityUiAction.GenerateRecoveryCodes -> runNetwork(IdentityUiActionKind.GENERATE_RECOVERY_CODES) {
                gateway.replaceRecoveryCodes()
            }
            IdentityUiAction.DismissRecoveryCodes -> {
                gateway.dismissRecoveryCodes()
                render()
            }
            IdentityUiAction.DismissOneTimeSecret -> {
                gateway.dismissOneTimeSecret()
                render()
            }
            is IdentityUiAction.RenamePasskey -> runNetwork(IdentityUiActionKind.RENAME_PASSKEY) {
                val name = state.passkeys.first { it.id == action.credentialId }.renameDraft
                gateway.renamePasskey(action.credentialId, name)
            }
            is IdentityUiAction.RevokePasskey -> runNetwork(IdentityUiActionKind.REVOKE_PASSKEY) {
                gateway.revokePasskey(action.credentialId)
            }
            is IdentityUiAction.RevokeSession -> {
                val current = state.sessions.firstOrNull { it.id == action.sessionId }?.current == true
                runNetwork(IdentityUiActionKind.REVOKE_SESSION, signsOut = current) {
                    if (current) gateway.logout() else gateway.revokeSession(action.sessionId)
                }
            }
            IdentityUiAction.RevokeOtherSessions -> runNetwork(IdentityUiActionKind.REVOKE_OTHER_SESSIONS) {
                gateway.revokeOtherSessions()
            }
            IdentityUiAction.RevokeAllSessions -> runNetwork(
                IdentityUiActionKind.REVOKE_ALL_SESSIONS,
                signsOut = true
            ) {
                gateway.revokeAllSessions()
            }
            is IdentityUiAction.SelectOrganization -> runNetwork(IdentityUiActionKind.SELECT_ORGANIZATION) {
                gateway.selectOrganization(action.organizationId)
            }
            is IdentityUiAction.ChangeMembershipRole -> runNetwork(IdentityUiActionKind.UPDATE_MEMBERSHIP) {
                gateway.changeMembershipRole(
                    action.organizationId,
                    action.membershipId,
                    action.role
                )
            }
            is IdentityUiAction.RemoveMembership -> runNetwork(IdentityUiActionKind.REMOVE_MEMBERSHIP) {
                gateway.removeMembership(action.organizationId, action.membershipId)
            }
            is IdentityUiAction.InviteMember -> runNetwork(IdentityUiActionKind.INVITE_MEMBER) {
                val draft = state.organizationManagement.invitationDraft
                gateway.inviteMember(action.organizationId, draft.email, draft.role)
            }
            is IdentityUiAction.RevokeInvitation -> runNetwork(IdentityUiActionKind.REVOKE_INVITATION) {
                gateway.revokeInvitation(action.organizationId, action.invitationId)
            }
            is IdentityUiAction.CreateServiceIdentity -> runNetwork(IdentityUiActionKind.CREATE_SERVICE_IDENTITY) {
                val draft = state.organizationManagement.serviceIdentityDraft
                gateway.createServiceIdentity(
                    action.organizationId,
                    draft.name,
                    draft.description,
                    draft.selectedCapabilities
                )
            }
            is IdentityUiAction.CreateServiceCredential -> runNetwork(IdentityUiActionKind.CREATE_SERVICE_CREDENTIAL) {
                val identity = state.organizationManagement.serviceIdentities.first { it.id == action.serviceIdentityId }
                gateway.createServiceCredential(action.organizationId, identity.id, identity.capabilities)
            }
            is IdentityUiAction.RotateServiceCredential -> runNetwork(IdentityUiActionKind.ROTATE_SERVICE_CREDENTIAL) {
                val identity = state.organizationManagement.serviceIdentities.first {
                    candidate -> candidate.credentials.any { it.id == action.credentialId }
                }
                gateway.rotateServiceCredential(action.organizationId, identity.id, action.credentialId)
            }
            is IdentityUiAction.RevokeServiceCredential -> runNetwork(IdentityUiActionKind.REVOKE_SERVICE_CREDENTIAL) {
                val identity = state.organizationManagement.serviceIdentities.first {
                    candidate -> candidate.credentials.any { it.id == action.credentialId }
                }
                gateway.revokeServiceCredential(action.organizationId, identity.id, action.credentialId)
            }
            is IdentityUiAction.RevokeServiceIdentity -> runNetwork(IdentityUiActionKind.REVOKE_SERVICE_IDENTITY) {
                gateway.revokeServiceIdentity(action.organizationId, action.serviceIdentityId)
            }
            IdentityUiAction.IssueAdministrativeRecovery -> runNetwork(
                IdentityUiActionKind.ADMINISTRATIVE_RECOVERY
            ) {
                gateway.issueAdministrativeRecovery(state.administrativeRecovery.userQuery)
            }
            is IdentityUiAction.CancelAdministrativeRecovery -> runNetwork(
                IdentityUiActionKind.ADMINISTRATIVE_RECOVERY
            ) {
                gateway.cancelAdministrativeRecovery(action.ticketId)
            }
            IdentityUiAction.ResolveDeviceAuthorization -> runNetwork(IdentityUiActionKind.RESOLVE_DEVICE) {
                client.resolveDevice(state.deviceAuthorization.userCode)
            }
            is IdentityUiAction.ApproveDeviceAuthorization -> runNetwork(IdentityUiActionKind.APPROVE_DEVICE) {
                client.approveDevice(action.userCode, action.organizationId, action.capabilities)
            }
            is IdentityUiAction.DenyDeviceAuthorization -> runNetwork(IdentityUiActionKind.DENY_DEVICE) {
                gateway.denyDevice(action.userCode)
            }
            else -> render()
        }
    }
    render()
    scope.launch {
        var stateChanged = false
        try {
            gateway.loadIdentityUiState()?.let { loaded ->
                state = loaded
                stateChanged = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val code = (failure as? BrowserIdentityApiException)?.code
            state = state.copy(
                feedback = IdentityUiFeedback(
                    code?.publicMessage ?: "Identity data could not be loaded.",
                    IdentityUiFeedbackSeverity.ERROR
                )
            )
            stateChanged = true
        }
        // A normal unauthenticated probe returns null. Re-rendering an identical tree here would
        // replace focused elements while a visitor is already typing or tabbing through sign-in.
        if (stateChanged) render()
    }
}

private class BrowserIdentityGateway(
    private val contract: IdentityExampleContract,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }
) : PasskeyCeremonyGateway, IdentityExampleApi {
    private var pendingRegistrationName: String? = null
    private var pendingRecoveryCodes: RecoveryCodesUiState = RecoveryCodesUiState.Hidden
    private var pendingOneTimeSecret: OneTimeIdentitySecretUiState = OneTimeIdentitySecretUiState.Hidden
    private var pendingAdministrativeRecovery: AdministrativeRecoveryTicketView? = null
    private var recoveryGeneration: Long? = null
    private var selectedOrganizationId: OrganizationId? = null
    private var publicClientConfig: IdentityExampleContract? = null
    private var pendingDeviceUserCode: String? = null
    private var pendingDeviceGrant: DeviceGrantView? = null

    suspend fun bootstrap(request: BootstrapIdentityRequest): BootstrapIdentityResponse {
        val response = post<BootstrapIdentityRequest, BootstrapIdentityResponse>(contract.bootstrap, request)
        storeBrowserCsrfToken(response.csrfToken)
        markBrowserEnrollmentPending("bootstrap")
        return response
    }

    suspend fun recover(request: RecoveryCodeUseRequest): IdentitySessionCreatedResponse {
        val response = post<RecoveryCodeUseRequest, IdentitySessionCreatedResponse>(
            contract.recoveryCodeUse,
            request
        )
        storeBrowserCsrfToken(response.csrfToken)
        markBrowserEnrollmentPending("recovery")
        return response
    }

    suspend fun replaceRecoveryCodes() {
        val response = post<ReplaceRecoveryCodesRequest, RecoveryCodesResponse>(
            contract.recoveryCodesReplace,
            ReplaceRecoveryCodesRequest(expectedGeneration = recoveryGeneration)
        )
        recoveryGeneration = response.generation
        pendingRecoveryCodes = RecoveryCodesUiState.VisibleOnce(response.codes)
    }

    fun dismissRecoveryCodes() {
        pendingRecoveryCodes = RecoveryCodesUiState.Hidden
    }

    fun currentRecoveryCodes(): RecoveryCodesUiState = pendingRecoveryCodes

    fun dismissOneTimeSecret() {
        pendingOneTimeSecret = OneTimeIdentitySecretUiState.Hidden
    }

    fun currentOneTimeSecret(): OneTimeIdentitySecretUiState = pendingOneTimeSecret

    suspend fun loadIdentityUiState(): IdentityUiState? {
        val clientConfig = loadPublicClientConfig()
        val me = try {
            get<IdentityMeResponse>(contract.me)
        } catch (failure: BrowserIdentityApiException) {
            if (failure.code == IdentityErrorCode.AUTHENTICATION_REQUIRED ||
                failure.code == IdentityErrorCode.SESSION_EXPIRED ||
                failure.code == IdentityErrorCode.SESSION_REVOKED
            ) return null
            throw failure
        }
        val passkeys = get<List<PasskeyView>>(contract.passkeys).map(PasskeyView::toUiModel)
        val sessionViews = get<List<IdentitySessionView>>(contract.sessions)
            .filter { it.state == SessionState.ACTIVE }
        val sessions = sessionViews.map(IdentitySessionView::toUiModel)
        val currentSession = sessionViews.firstOrNull { it.current }
        val organizations = listOrganizations().mapNotNull(OrganizationAccessView::toUiModelOrNull)
        val selected = organizations.firstOrNull { it.id == selectedOrganizationId } ?: organizations.firstOrNull()
        selectedOrganizationId = selected?.id
        val organizationManagement = if (selected == null) {
            OrganizationManagementUiState(organizations = organizations)
        } else {
            loadOrganizationManagement(
                organizations,
                selected,
                me.userId,
                clientConfig.serviceCredentialCapabilities
            )
        }
        val deviceApproval = loadDeviceApproval(organizations)
        return IdentityUiState(
            signedInDisplayName = me.displayName,
            passkeys = passkeys,
            sessions = sessions,
            recoveryCodes = pendingRecoveryCodes,
            oneTimeSecret = pendingOneTimeSecret,
            administrativeRecovery = administrativeRecoveryUiState(clientConfig.administrativeRecoveryEnabled),
            stepUp = currentSession.toStepUpUiState(),
            organizationManagement = organizationManagement,
            deviceApproval = deviceApproval
        )
    }

    override suspend fun startRegistration(passkeyName: String): WebAuthnRegistrationStartResponse {
        pendingRegistrationName = passkeyName
        return try {
            postWithoutBody(contract.registrationStart)
        } catch (failure: Throwable) {
            pendingRegistrationName = null
            throw failure
        }
    }

    override suspend fun finishRegistration(
        ceremonyId: ChallengeId,
        credential: RegistrationPublicKeyCredentialDto
    ) {
        val name = pendingRegistrationName ?: throw BrowserIdentityApiException()
        pendingRegistrationName = null
        val response = post<PasskeyRegistrationFinishRequest, PasskeyRegistrationResponse>(
            contract.registrationFinish,
            PasskeyRegistrationFinishRequest(
                ceremonyId = ceremonyId,
                credentialName = name,
                credential = credential
            )
        )
        response.replacementRecoveryCodes?.let { codes ->
            pendingRecoveryCodes = RecoveryCodesUiState.VisibleOnce(codes)
            recoveryGeneration = when (browserEnrollmentKind()) {
                "bootstrap" -> 0L
                "recovery" -> (recoveryGeneration ?: 0L) + 1L
                else -> recoveryGeneration
            }
            clearBrowserCsrfToken()
        }
    }

    override suspend fun startAuthentication(
        purpose: PasskeyAuthenticationPurpose
    ): WebAuthnAuthenticationStartResponse = postWithoutBody(
        when (purpose) {
            PasskeyAuthenticationPurpose.DISCOVERABLE_SIGN_IN -> contract.authenticationStart
            PasskeyAuthenticationPurpose.STEP_UP -> contract.stepUpStart
        }
    )

    override suspend fun finishAuthentication(
        ceremonyId: ChallengeId,
        purpose: PasskeyAuthenticationPurpose,
        credential: AuthenticationPublicKeyCredentialDto
    ) {
        val path = when (purpose) {
            PasskeyAuthenticationPurpose.DISCOVERABLE_SIGN_IN -> contract.authenticationFinish
            PasskeyAuthenticationPurpose.STEP_UP -> contract.stepUpFinish
        }
        val response = post<PasskeyAuthenticationFinishRequest, IdentitySessionCreatedResponse>(
            path,
            PasskeyAuthenticationFinishRequest(ceremonyId = ceremonyId, credential = credential)
        )
        storeBrowserCsrfToken(response.csrfToken)
    }

    suspend fun renamePasskey(credentialId: CredentialId, name: String) {
        patchWithoutResponse(contract.passkey(credentialId), RenamePasskeyRequest(name))
    }

    suspend fun revokePasskey(credentialId: CredentialId) {
        delete(contract.passkey(credentialId))
    }

    suspend fun logout() {
        postWithoutResponse(contract.logout)
        clearBrowserCsrfToken()
    }

    suspend fun revokeSession(sessionId: SessionId) {
        delete(contract.session(sessionId))
    }

    suspend fun revokeOtherSessions() {
        postWithoutResponse(contract.revokeOtherSessions)
    }

    suspend fun revokeAllSessions() {
        postWithoutResponse(contract.revokeAllSessions)
        clearBrowserCsrfToken()
    }

    fun selectOrganization(organizationId: OrganizationId) {
        selectedOrganizationId = organizationId
    }

    suspend fun changeMembershipRole(
        organizationId: OrganizationId,
        membershipId: MembershipId,
        role: OrganizationRole
    ) {
        patchWithoutResponse(
            contract.membership(organizationId, membershipId),
            UpdateMembershipRoleRequest(role)
        )
    }

    suspend fun removeMembership(organizationId: OrganizationId, membershipId: MembershipId) {
        delete(contract.membership(organizationId, membershipId))
    }

    suspend fun inviteMember(organizationId: OrganizationId, email: String, role: OrganizationRole) {
        val response = post<CreateInvitationRequest, IssuedInvitationResponse>(
            contract.invitations(organizationId),
            CreateInvitationRequest(EmailAddress(email.trim()), role)
        )
        pendingOneTimeSecret = OneTimeIdentitySecretUiState.VisibleOnce(
            kind = OneTimeIdentitySecretKind.INVITATION_TOKEN,
            label = "Invitation for ${response.invitation.email.value}",
            secret = response.token
        )
    }

    suspend fun revokeInvitation(organizationId: OrganizationId, invitationId: InvitationId) {
        delete(contract.invitation(organizationId, invitationId))
    }

    suspend fun createServiceIdentity(
        organizationId: OrganizationId,
        name: String,
        description: String,
        capabilities: Set<Capability>
    ) {
        val response = post<CreateServiceIdentityRequest, IssuedServiceIdentityResponse>(
            contract.serviceIdentities(organizationId),
            CreateServiceIdentityRequest(
                name = name,
                description = description.takeIf(String::isNotBlank),
                capabilities = capabilities
            )
        )
        pendingOneTimeSecret = OneTimeIdentitySecretUiState.VisibleOnce(
            kind = OneTimeIdentitySecretKind.SERVICE_CREDENTIAL,
            label = "Credential for ${response.identity.name}",
            secret = response.token
        )
    }

    suspend fun createServiceCredential(
        organizationId: OrganizationId,
        serviceIdentityId: ServiceIdentityId,
        capabilities: Set<Capability>
    ) {
        val response = post<CreateServiceCredentialRequest, IssuedServiceCredentialResponse>(
            contract.serviceCredentials(organizationId, serviceIdentityId),
            CreateServiceCredentialRequest(capabilities)
        )
        pendingOneTimeSecret = OneTimeIdentitySecretUiState.VisibleOnce(
            kind = OneTimeIdentitySecretKind.SERVICE_CREDENTIAL,
            label = "Credential ${response.credential.publicPrefix}",
            secret = response.token
        )
    }

    suspend fun rotateServiceCredential(
        organizationId: OrganizationId,
        serviceIdentityId: ServiceIdentityId,
        credentialId: ServiceCredentialId
    ) {
        val response = post<RotateServiceCredentialRequest, IssuedServiceCredentialResponse>(
            contract.rotateServiceCredential(organizationId, serviceIdentityId, credentialId),
            RotateServiceCredentialRequest()
        )
        pendingOneTimeSecret = OneTimeIdentitySecretUiState.VisibleOnce(
            kind = OneTimeIdentitySecretKind.SERVICE_CREDENTIAL,
            label = "Rotated credential ${response.credential.publicPrefix}",
            secret = response.token
        )
    }

    suspend fun revokeServiceCredential(
        organizationId: OrganizationId,
        serviceIdentityId: ServiceIdentityId,
        credentialId: ServiceCredentialId
    ) {
        delete(contract.serviceCredential(organizationId, serviceIdentityId, credentialId))
    }

    suspend fun revokeServiceIdentity(organizationId: OrganizationId, serviceIdentityId: ServiceIdentityId) {
        delete(contract.serviceIdentity(organizationId, serviceIdentityId))
    }

    suspend fun issueAdministrativeRecovery(userQuery: String) {
        val userId = UserId.parseOrNull(userQuery.trim())
            ?: throw BrowserIdentityApiException(IdentityErrorCode.REQUEST_INVALID)
        pendingAdministrativeRecovery = post<AdministrativeRecoveryIssueRequest, AdministrativeRecoveryTicketView>(
            contract.administrativeRecoveryTickets,
            AdministrativeRecoveryIssueRequest(userId)
        )
    }

    suspend fun cancelAdministrativeRecovery(ticketId: ChallengeId) {
        delete(contract.administrativeRecoveryTicket(ticketId))
        pendingAdministrativeRecovery = null
    }

    override suspend fun listOrganizations(): List<OrganizationAccessView> = get(contract.organizations)

    override suspend fun inspectDevice(request: InspectDeviceGrantRequest): DeviceGrantView {
        val grant = post<InspectDeviceGrantRequest, DeviceGrantView>(contract.deviceVerification, request)
        pendingDeviceUserCode = request.userCode
        pendingDeviceGrant = grant
        return grant
    }

    override suspend fun approveDevice(request: ApproveDeviceGrantRequest) {
        postWithoutResponse(contract.deviceApproval, request)
        clearPendingDeviceGrant()
    }

    suspend fun denyDevice(userCode: String) {
        postWithoutResponse(contract.deviceDenial, DenyDeviceGrantRequest(userCode))
        clearPendingDeviceGrant()
    }

    private suspend fun loadOrganizationManagement(
        organizations: List<OrganizationUiModel>,
        selected: OrganizationUiModel,
        currentUserId: UserId,
        serviceCredentialCapabilities: Set<Capability>
    ): OrganizationManagementUiState {
        val canInvite = selected.role.grants(Capability.MEMBERSHIP_INVITE)
        val canManageServices = selected.role.grants(Capability.SERVICE_IDENTITY_MANAGE)
        val memberships = get<List<Membership>>(contract.memberships(selected.id)).map { membership ->
            membership.toUiModel(selected.role, currentUserId)
        }
        val invitations = get<List<InvitationView>>(contract.invitations(selected.id)).map { invitation ->
            invitation.toUiModel(selected.role, canInvite)
        }
        val serviceIdentities = if (selected.role.grants(Capability.SERVICE_IDENTITY_READ)) {
            get<List<ServiceIdentity>>(contract.serviceIdentities(selected.id)).map { identity ->
                val credentials = get<List<ServiceCredentialView>>(
                    contract.serviceCredentials(selected.id, identity.id)
                )
                identity.toUiModel(credentials, canManageServices)
            }
        } else {
            emptyList()
        }
        val invitationRoles = if (selected.role == OrganizationRole.OWNER) {
            OrganizationRole.entries.toSet()
        } else {
            OrganizationRole.entries.filterNot { it == OrganizationRole.OWNER }.toSet()
        }
        return OrganizationManagementUiState(
            organizations = organizations,
            selectedOrganizationId = selected.id,
            memberships = memberships,
            invitationDraft = InvitationDraftUiState(allowedRoles = invitationRoles),
            invitations = invitations,
            serviceIdentityDraft = ServiceIdentityDraftUiState(
                capabilityOptions = if (canManageServices) {
                    serviceCredentialCapabilities.toCapabilityOptions()
                } else {
                    emptyList()
                }
            ),
            serviceIdentities = serviceIdentities,
            canInviteMembers = canInvite,
            canManageServiceIdentities = canManageServices
        )
    }

    private fun administrativeRecoveryUiState(enabled: Boolean): AdministrativeRecoveryUiState {
        val ticket = pendingAdministrativeRecovery
        return AdministrativeRecoveryUiState(
            enabled = enabled,
            outstandingTicket = ticket?.let {
                AdministrativeRecoveryTicketUiModel(it.id, it.userId, it.expiresAt.toString())
            },
            deliveryStatus = ticket?.let { "The configured notification sink accepted the enrollment link." }
        )
    }

    private suspend fun loadPublicClientConfig(): IdentityExampleContract {
        publicClientConfig?.let { return it }
        return try {
            get<IdentityExampleContract>(contract.clientConfig).also { publicClientConfig = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            contract
        }
    }

    private fun loadDeviceApproval(
        organizations: List<OrganizationUiModel>
    ): DeviceApprovalUiState? {
        val userCode = pendingDeviceUserCode ?: return null
        val grant = pendingDeviceGrant ?: return null
        val requested = grant.requestedCapabilities
        val allowedByOrganization = organizations.mapNotNull { organization ->
            val allowed = requested.intersect(exampleCapabilities(organization.role))
            if (allowed.isEmpty()) null else organization to allowed
        }
        if (allowedByOrganization.isEmpty()) return null
        val eligibleOrganizations = allowedByOrganization.map { it.first }
        return DeviceApprovalUiState(
            userCode = userCode,
            clientName = grant.clientName,
            expiresAt = grant.expiresAt.toString(),
            organizations = eligibleOrganizations,
            approvableCapabilitiesByOrganization = allowedByOrganization.associate { it.first.id to it.second },
            selectedOrganizationId = eligibleOrganizations.singleOrNull()?.id,
            capabilityOptions = requested.sortedBy { it.wireName }.map { capability ->
                CapabilityOptionUiModel(capability, capability.wireName)
            }
        )
    }

    private fun clearPendingDeviceGrant() {
        pendingDeviceUserCode = null
        pendingDeviceGrant = null
    }

    private suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(request(path, "GET", null))

    private suspend inline fun <reified Response> postWithoutBody(path: String): Response =
        json.decodeFromString(request(path, "POST", null))

    private suspend inline fun <reified Request, reified Response> post(path: String, body: Request): Response =
        json.decodeFromString(request(path, "POST", json.encodeToString(body)))

    private suspend inline fun <reified Request> postWithoutResponse(path: String, body: Request) {
        request(path, "POST", json.encodeToString(body))
    }

    private suspend fun postWithoutResponse(path: String) {
        request(path, "POST", null)
    }

    private suspend inline fun <reified Request> patchWithoutResponse(path: String, body: Request) {
        request(path, "PATCH", json.encodeToString(body))
    }

    private suspend fun delete(path: String) {
        request(path, "DELETE", null)
    }
}

private fun OrganizationAccessView.toUiModelOrNull(): OrganizationUiModel? {
    val parsedRole = OrganizationRole.entries.singleOrNull { it.wireName == role } ?: return null
    return OrganizationUiModel(id = id, name = name, slug = slug, role = parsedRole)
}

private fun PasskeyView.toUiModel(): PasskeyUiModel = PasskeyUiModel(
    id = id,
    name = name,
    createdAt = createdAt.toString(),
    lastUsedAt = lastUsedAt?.toString(),
    backedUp = backedUp,
    canRevoke = state == CredentialState.ACTIVE
)

private fun IdentitySessionView.toUiModel(): SessionUiModel {
    val deviceLabel = label?.takeIf(String::isNotBlank)
        ?: platform?.takeIf(String::isNotBlank)
        ?: "Unnamed device"
    return SessionUiModel(
        id = id,
        deviceLabel = deviceLabel,
        lastUsedAt = lastUsedAt.toString(),
        expiresAt = minOf(idleExpiresAt, absoluteExpiresAt).toString(),
        current = current,
        recentPasskey = assurance == AuthenticationAssurance.PASSKEY ||
            assurance == AuthenticationAssurance.STEP_UP
    )
}

private fun IdentitySessionView?.toStepUpUiState(): StepUpUiState = if (
    this != null && (assurance == AuthenticationAssurance.PASSKEY || assurance == AuthenticationAssurance.STEP_UP)
) {
    StepUpUiState(satisfiedAt = authenticatedAt.toString())
} else {
    StepUpUiState()
}

private fun Membership.toUiModel(actorRole: OrganizationRole, currentUserId: UserId): MembershipUiModel {
    val actorCanManage = actorRole.grants(Capability.MEMBERSHIP_UPDATE)
    val mayManageTarget = actorCanManage && (actorRole == OrganizationRole.OWNER || role != OrganizationRole.OWNER)
    val allowedRoles = when {
        actorRole == OrganizationRole.OWNER -> OrganizationRole.entries.toSet()
        mayManageTarget -> OrganizationRole.entries.filterNot { it == OrganizationRole.OWNER }.toSet()
        else -> setOf(role)
    }
    val stableLabel = if (userId == currentUserId) "You (${userId.value})" else "User ${userId.value}"
    return MembershipUiModel(
        id = id,
        organizationId = organizationId,
        userId = userId,
        displayName = stableLabel,
        role = role,
        state = state,
        allowedRoles = allowedRoles,
        canChangeRole = mayManageTarget && state == codes.yousef.aether.auth.MembershipState.ACTIVE,
        canRemove = actorRole.grants(Capability.MEMBERSHIP_REMOVE) &&
            (actorRole == OrganizationRole.OWNER || role != OrganizationRole.OWNER) &&
            state == codes.yousef.aether.auth.MembershipState.ACTIVE
    )
}

private fun InvitationView.toUiModel(
    actorRole: OrganizationRole,
    canInvite: Boolean
): InvitationUiModel = InvitationUiModel(
    id = id,
    organizationId = organizationId,
    email = email.value,
    role = role,
    state = state,
    expiresAt = expiresAt.toString(),
    canRevoke = canInvite && state == codes.yousef.aether.auth.InvitationState.PENDING &&
        (actorRole == OrganizationRole.OWNER || role != OrganizationRole.OWNER)
)

private fun ServiceIdentity.toUiModel(
    credentialViews: List<ServiceCredentialView>,
    canManageServices: Boolean
): ServiceIdentityUiModel = ServiceIdentityUiModel(
    id = id,
    organizationId = organizationId,
    name = name,
    description = description,
    capabilities = capabilities,
    state = state,
    credentials = credentialViews.map(ServiceCredentialView::toUiModel),
    canManage = canManageServices
)

private fun ServiceCredentialView.toUiModel(): ServiceCredentialUiModel = ServiceCredentialUiModel(
    id = id,
    publicPrefix = publicPrefix,
    capabilities = capabilities,
    state = state,
    expiresAt = expiresAt?.toString()
)

private fun Set<Capability>.toCapabilityOptions(): List<CapabilityOptionUiModel> = sortedBy { it.wireName }.map { capability ->
    CapabilityOptionUiModel(
        capability = capability,
        label = capability.wireName.split('.', '_').joinToString(" ") { word ->
            word.replaceFirstChar(Char::uppercase)
        }
    )
}

private fun exampleCapabilities(role: OrganizationRole): Set<Capability> = buildSet {
    addAll(role.capabilities)
    if (role == OrganizationRole.OWNER || role == OrganizationRole.ADMIN || role == OrganizationRole.PUBLISHER) {
        add(Capability("package.publish"))
    }
}

private fun restrictedEnrollmentUiState(): IdentityUiState {
    if (!browserEnrollmentPending()) return IdentityUiState()
    return IdentityUiState(
        registration = RegistrationUiState(signInEnabled = false),
        feedback = IdentityUiFeedback(
            "Restricted recovery session: enroll a passkey to continue. Other identity actions are unavailable."
        )
    )
}

private class BrowserIdentityApiException(
    val code: IdentityErrorCode? = null
) : IllegalStateException(code?.publicMessage ?: "Identity request failed")

private val browserIdentityErrorJson = Json { ignoreUnknownKeys = true }

private suspend fun request(path: String, method: String, body: String?): String =
    suspendCoroutine { continuation ->
        browserIdentityRequest(path, method, body) { status, response, failed ->
            if (!failed && status in 200..299 && response != null) {
                continuation.resume(response)
            } else {
                val code = response?.let { body ->
                    runCatching {
                        browserIdentityErrorJson.decodeFromString<IdentityErrorEnvelope>(body).error.code
                    }.getOrNull()
                }
                continuation.resumeWithException(BrowserIdentityApiException(code))
            }
        }
    }

/** Same-origin fetch keeps opaque session cookies out of wasm memory. */
@JsFun("""
(path, method, body, callback) => {
    try {
        const headers = {"Accept": "application/json"};
        if (body !== null) headers["Content-Type"] = "application/json";
        const csrf = globalThis.sessionStorage && globalThis.sessionStorage.getItem("aether.identity.csrf.v1");
        if (csrf) headers["X-CSRF-Token"] = csrf;
        globalThis.fetch(path, {
            method,
            headers,
            body: body === null ? undefined : body,
            credentials: "same-origin",
            redirect: "error"
        }).then(async response => {
            const text = await response.text();
            callback(response.status, text, false);
        }).catch(() => callback(0, null, true));
    } catch (_) {
        callback(0, null, true);
    }
}
""")
private external fun browserIdentityRequest(
    path: String,
    method: String,
    body: String?,
    callback: (Int, String?, Boolean) -> Unit
)

@JsFun("id => globalThis.document && globalThis.document.getElementById(id) !== null")
private external fun browserElementExists(id: String): Boolean

@JsFun("value => globalThis.sessionStorage.setItem('aether.identity.csrf.v1', value)")
private external fun storeBrowserCsrfToken(value: String)

@JsFun("() => globalThis.sessionStorage.removeItem('aether.identity.csrf.v1')")
private external fun clearBrowserCsrfToken()

@JsFun("kind => globalThis.sessionStorage.setItem('aether.identity.enrollment.v1', kind)")
private external fun markBrowserEnrollmentPending(kind: String)

@JsFun("() => globalThis.sessionStorage.getItem('aether.identity.enrollment.v1')")
private external fun browserEnrollmentKind(): String?

private fun browserEnrollmentPending(): Boolean = browserEnrollmentKind() != null

@JsFun("() => globalThis.sessionStorage.removeItem('aether.identity.enrollment.v1')")
private external fun clearBrowserEnrollmentPending()

@JsFun("path => globalThis.location.assign(path)")
private external fun browserRedirect(path: String)

private const val IDENTITY_ROOT_ID = "aether-identity"
private const val SUMMON_HYDRATION_ROOT_ID = "summon-app"
