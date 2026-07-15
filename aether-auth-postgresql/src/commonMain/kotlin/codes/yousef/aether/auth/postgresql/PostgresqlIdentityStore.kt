package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Storage-neutral contract implementation backed exclusively by fixed PostgreSQL RPC functions.
 * Call [initialize] during application startup; operations fail closed until the database marker
 * confirms the configured environment and namespace.
 */
class PostgresqlIdentityStore(
    private val config: PostgresqlIdentityConfig,
    private val transport: PostgresqlRpcTransport,
    private val json: Json = defaultPostgresqlJson()
) : IdentityStore {
    private val initializationMutex = Mutex()
    private var initialized = false

    suspend fun initialize(): StoreResult<Unit> {
        initializationMutex.lock()
        return try {
            if (initialized) return StoreResult.Success(Unit)
            when (
                val result = invokeRaw<EnvironmentAssertionResult>(
                    operation = PostgresqlRpcOperation.ASSERT_ENVIRONMENT,
                    payload = JsonObject(emptyMap())
                )
            ) {
                is StoreResult.Failure -> result
                is StoreResult.Success -> {
                    val value = result.value
                    if (!value.verified || value.environment != config.environment || value.namespace != config.namespace) {
                        StoreResult.Failure(PostgresqlFailureMapper.internal())
                    } else {
                        initialized = true
                        StoreResult.Success(Unit)
                    }
                }
            }
        } finally {
            initializationMutex.unlock()
        }
    }

    override suspend fun findUser(id: UserId): StoreResult<User?> =
        invoke(PostgresqlRpcOperation.FIND_USER, IdPayload(id.value))

    override suspend fun findUserByEmail(email: EmailAddress): StoreResult<User?> =
        invoke(PostgresqlRpcOperation.FIND_USER_BY_EMAIL, EmailPayload(email))

    override suspend fun findCredential(id: CredentialId): StoreResult<Credential?> =
        invoke(PostgresqlRpcOperation.FIND_CREDENTIAL, IdPayload(id.value))

    override suspend fun findCredentialByWebAuthnId(id: WebAuthnCredentialId): StoreResult<Credential?> =
        invoke(PostgresqlRpcOperation.FIND_CREDENTIAL_BY_WEB_AUTHN_ID, WebAuthnIdPayload(id))

    override suspend fun listCredentialsForUser(userId: UserId): StoreResult<List<Credential>> =
        invoke(PostgresqlRpcOperation.LIST_CREDENTIALS_FOR_USER, UserIdPayload(userId))

    override suspend fun findSession(id: SessionId): StoreResult<IdentitySession?> =
        invoke(PostgresqlRpcOperation.FIND_SESSION, IdPayload(id.value))

    override suspend fun listSessionsForUser(userId: UserId): StoreResult<List<IdentitySession>> =
        invoke(PostgresqlRpcOperation.LIST_SESSIONS_FOR_USER, UserIdPayload(userId))

    override suspend fun findOrganization(id: OrganizationId): StoreResult<Organization?> =
        invoke(PostgresqlRpcOperation.FIND_ORGANIZATION, IdPayload(id.value))

    override suspend fun findOrganizationBySlug(slug: String): StoreResult<Organization?> =
        invoke(PostgresqlRpcOperation.FIND_ORGANIZATION_BY_SLUG, SlugPayload(slug))

    override suspend fun listOrganizationsForUser(userId: UserId): StoreResult<List<Organization>> =
        invoke(PostgresqlRpcOperation.LIST_ORGANIZATIONS_FOR_USER, UserIdPayload(userId))

    override suspend fun findMembership(id: MembershipId): StoreResult<Membership?> =
        invoke(PostgresqlRpcOperation.FIND_MEMBERSHIP, IdPayload(id.value))

    override suspend fun findMembershipForUser(
        userId: UserId,
        organizationId: OrganizationId
    ): StoreResult<Membership?> = invoke(
        PostgresqlRpcOperation.FIND_MEMBERSHIP_FOR_USER,
        UserOrganizationPayload(userId, organizationId)
    )

    override suspend fun listMembershipsForOrganization(
        organizationId: OrganizationId
    ): StoreResult<List<Membership>> =
        invoke(PostgresqlRpcOperation.LIST_MEMBERSHIPS_FOR_ORGANIZATION, OrganizationIdPayload(organizationId))

    override suspend fun findInvitation(id: InvitationId): StoreResult<Invitation?> =
        invoke(PostgresqlRpcOperation.FIND_INVITATION, IdPayload(id.value))

    override suspend fun findInvitationByTokenDigest(digest: SecretDigest): StoreResult<Invitation?> =
        invoke(PostgresqlRpcOperation.FIND_INVITATION_BY_TOKEN_DIGEST, DigestPayload(digest))

    override suspend fun listInvitationsForOrganization(
        organizationId: OrganizationId
    ): StoreResult<List<Invitation>> =
        invoke(PostgresqlRpcOperation.LIST_INVITATIONS_FOR_ORGANIZATION, OrganizationIdPayload(organizationId))

    override suspend fun findServiceIdentity(id: ServiceIdentityId): StoreResult<ServiceIdentity?> =
        invoke(PostgresqlRpcOperation.FIND_SERVICE_IDENTITY, IdPayload(id.value))

    override suspend fun listServiceIdentitiesForOrganization(
        organizationId: OrganizationId
    ): StoreResult<List<ServiceIdentity>> =
        invoke(PostgresqlRpcOperation.LIST_SERVICE_IDENTITIES_FOR_ORGANIZATION, OrganizationIdPayload(organizationId))

    override suspend fun findServiceCredentialByPrefix(publicPrefix: String): StoreResult<ServiceCredential?> =
        invoke(PostgresqlRpcOperation.FIND_SERVICE_CREDENTIAL_BY_PREFIX, PublicPrefixPayload(publicPrefix))

    override suspend fun listServiceCredentialsForIdentity(
        serviceIdentityId: ServiceIdentityId
    ): StoreResult<List<ServiceCredential>> =
        invoke(
            PostgresqlRpcOperation.LIST_SERVICE_CREDENTIALS_FOR_IDENTITY,
            ServiceIdentityIdPayload(serviceIdentityId)
        )

    override suspend fun findExternalIdentity(
        provider: String,
        subject: ExternalSubject
    ): StoreResult<ExternalIdentity?> = invoke(
        PostgresqlRpcOperation.FIND_EXTERNAL_IDENTITY,
        ExternalIdentityLookupPayload(provider, subject)
    )

    override suspend fun findFederationProviderControl(
        organizationId: OrganizationId,
        providerId: String
    ): StoreResult<FederationProviderControl?> = invoke(
        PostgresqlRpcOperation.FIND_FEDERATION_PROVIDER_CONTROL,
        FederationProviderLookupPayload(organizationId, providerId)
    )

    override suspend fun findFederationProviderControlByStorageKey(
        storageKey: String
    ): StoreResult<FederationProviderControl?> = invoke(
        PostgresqlRpcOperation.FIND_FEDERATION_PROVIDER_CONTROL_BY_STORAGE_KEY,
        StorageKeyPayload(storageKey)
    )

    override suspend fun findScimGroup(
        provider: String,
        organizationId: OrganizationId,
        id: String
    ): StoreResult<ScimGroup?> = invoke(
        PostgresqlRpcOperation.FIND_SCIM_GROUP,
        ScimGroupLookupPayload(provider, organizationId, id)
    )

    override suspend fun findChallenge(id: ChallengeId): StoreResult<Challenge?> =
        invoke(PostgresqlRpcOperation.FIND_CHALLENGE, IdPayload(id.value))

    override suspend fun findRecoveryCodeBySelector(publicSelector: String): StoreResult<RecoveryCode?> =
        invoke(PostgresqlRpcOperation.FIND_RECOVERY_CODE_BY_SELECTOR, PublicSelectorPayload(publicSelector))

    override suspend fun listRecoveryCodesForUser(userId: UserId): StoreResult<List<RecoveryCode>> =
        invoke(PostgresqlRpcOperation.LIST_RECOVERY_CODES_FOR_USER, UserIdPayload(userId))

    override suspend fun findDeviceGrant(id: DeviceGrantId): StoreResult<DeviceGrant?> =
        invoke(PostgresqlRpcOperation.FIND_DEVICE_GRANT, IdPayload(id.value))

    override suspend fun findDeviceGrantByDeviceCodeDigest(digest: SecretDigest): StoreResult<DeviceGrant?> =
        invoke(PostgresqlRpcOperation.FIND_DEVICE_GRANT_BY_DEVICE_CODE_DIGEST, DigestPayload(digest))

    override suspend fun findDeviceGrantByUserCodeDigest(digest: SecretDigest): StoreResult<DeviceGrant?> =
        invoke(PostgresqlRpcOperation.FIND_DEVICE_GRANT_BY_USER_CODE_DIGEST, DigestPayload(digest))

    override suspend fun findDeviceTokenFamily(id: DeviceTokenFamilyId): StoreResult<DeviceTokenFamily?> =
        invoke(PostgresqlRpcOperation.FIND_DEVICE_TOKEN_FAMILY, IdPayload(id.value))

    override suspend fun findDeviceAccessTokenBySelector(
        publicSelector: String
    ): StoreResult<DeviceAccessToken?> =
        invoke(PostgresqlRpcOperation.FIND_DEVICE_ACCESS_TOKEN_BY_SELECTOR, PublicSelectorPayload(publicSelector))

    override suspend fun findDeviceRefreshTokenBySelector(
        publicSelector: String
    ): StoreResult<DeviceRefreshToken?> =
        invoke(PostgresqlRpcOperation.FIND_DEVICE_REFRESH_TOKEN_BY_SELECTOR, PublicSelectorPayload(publicSelector))

    override suspend fun listAuditEventsForOrganization(
        request: OrganizationAuditEventPageRequest
    ): StoreResult<OrganizationAuditEventPage> =
        invoke(PostgresqlRpcOperation.LIST_AUDIT_EVENTS_FOR_ORGANIZATION, request)

    override suspend fun purgeAuditEvents(
        command: PurgeAuditEventsCommand
    ): StoreResult<PurgeAuditEventsCommit> =
        invoke(PostgresqlRpcOperation.PURGE_AUDIT_EVENTS, command)

    override suspend fun createChallenge(command: CreateChallengeCommand): StoreResult<Challenge> =
        invoke(PostgresqlRpcOperation.CREATE_CHALLENGE, command)

    override suspend fun consumeChallenge(command: ConsumeChallengeCommand): StoreResult<Challenge> =
        invoke(PostgresqlRpcOperation.CONSUME_CHALLENGE, command)

    override suspend fun appendAuditEvent(event: AuditEvent): StoreResult<AuditEvent> =
        invoke(PostgresqlRpcOperation.APPEND_AUDIT_EVENT, AuditEventPayload(event))

    override suspend fun bootstrapIdentity(
        command: BootstrapIdentityCommand
    ): StoreResult<BootstrapIdentityCommit> =
        invoke(PostgresqlRpcOperation.BOOTSTRAP_IDENTITY, command)

    override suspend fun completeCredentialRegistration(
        command: CompleteCredentialRegistrationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialRegistrationCommit>> =
        invoke(PostgresqlRpcOperation.COMPLETE_CREDENTIAL_REGISTRATION, command)

    override suspend fun completeCredentialAuthentication(
        command: CompleteCredentialAuthenticationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialAuthenticationCommit>> =
        invoke(PostgresqlRpcOperation.COMPLETE_CREDENTIAL_AUTHENTICATION, command)

    override suspend fun quarantineCredentialAuthentication(
        command: QuarantineCredentialAuthenticationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialQuarantineCommit>> =
        invoke(PostgresqlRpcOperation.QUARANTINE_CREDENTIAL_AUTHENTICATION, command)

    override suspend fun mutateCredential(command: MutateCredentialCommand): StoreResult<Credential> =
        invoke(PostgresqlRpcOperation.MUTATE_CREDENTIAL, command)

    override suspend fun createSession(command: CreateSessionCommand): StoreResult<IdentitySession> =
        invoke(PostgresqlRpcOperation.CREATE_SESSION, command)

    override suspend fun touchIdentitySession(command: TouchIdentitySessionCommand): StoreResult<IdentitySession> =
        invoke(PostgresqlRpcOperation.TOUCH_IDENTITY_SESSION, command)

    override suspend fun rotateSession(command: RotateSessionCommand): StoreResult<SessionRotationCommit> =
        invoke(PostgresqlRpcOperation.ROTATE_SESSION, command)

    override suspend fun revokeSession(command: RevokeSessionCommand): StoreResult<IdentitySession> =
        invoke(PostgresqlRpcOperation.REVOKE_SESSION, command)

    override suspend fun revokeUserSessions(
        command: RevokeUserSessionsCommand
    ): StoreResult<RevokeUserSessionsCommit> =
        invoke(PostgresqlRpcOperation.REVOKE_USER_SESSIONS, command)

    override suspend fun acquireFederationProviderLease(
        command: AcquireFederationProviderLeaseCommand
    ): StoreResult<FederationProviderLease> =
        invoke(PostgresqlRpcOperation.ACQUIRE_FEDERATION_PROVIDER_LEASE, command)

    override suspend fun validateFederationProviderLease(
        lease: FederationProviderLease
    ): StoreResult<FederationProviderLease> =
        invoke(PostgresqlRpcOperation.VALIDATE_FEDERATION_PROVIDER_LEASE, lease)

    override suspend fun compareAndSetFederationProviderState(
        command: CompareAndSetFederationProviderStateCommand
    ): StoreResult<FederationProviderStateCommit> =
        invoke(PostgresqlRpcOperation.COMPARE_AND_SET_FEDERATION_PROVIDER_STATE, command)

    override suspend fun replaceRecoveryCodes(
        command: ReplaceRecoveryCodesCommand
    ): StoreResult<RecoveryCodeReplacementCommit> =
        invoke(PostgresqlRpcOperation.REPLACE_RECOVERY_CODES, command)

    override suspend fun consumeRecoveryCode(
        command: ConsumeRecoveryCodeCommand
    ): StoreResult<RecoveryCodeConsumptionCommit> =
        invoke(PostgresqlRpcOperation.CONSUME_RECOVERY_CODE, command)

    override suspend fun activateAdministrativeRecoveryTicket(
        command: ActivateAdministrativeRecoveryTicketCommand
    ): StoreResult<AdministrativeRecoveryTicketActivationCommit> =
        invoke(PostgresqlRpcOperation.ACTIVATE_ADMINISTRATIVE_RECOVERY_TICKET, command)

    override suspend fun redeemAdministrativeRecoveryTicket(
        command: RedeemAdministrativeRecoveryTicketCommand
    ): StoreResult<AdministrativeRecoveryTicketRedemptionCommit> =
        invoke(PostgresqlRpcOperation.REDEEM_ADMINISTRATIVE_RECOVERY_TICKET, command)

    override suspend fun completeRecoveryEnrollment(
        command: CompleteRecoveryEnrollmentCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<RecoveryEnrollmentCommit>> =
        invoke(PostgresqlRpcOperation.COMPLETE_RECOVERY_ENROLLMENT, command)

    override suspend fun createOrganization(
        command: CreateOrganizationCommand
    ): StoreResult<OrganizationCreationCommit> =
        invoke(PostgresqlRpcOperation.CREATE_ORGANIZATION, command)

    override suspend fun mutateOrganization(command: MutateOrganizationCommand): StoreResult<Organization> =
        invoke(PostgresqlRpcOperation.MUTATE_ORGANIZATION, command)

    override suspend fun createInvitation(command: CreateInvitationCommand): StoreResult<Invitation> =
        invoke(PostgresqlRpcOperation.CREATE_INVITATION, command)

    override suspend fun mutateInvitation(command: MutateInvitationCommand): StoreResult<Invitation> =
        invoke(PostgresqlRpcOperation.MUTATE_INVITATION, command)

    override suspend fun enrollInvitation(
        command: EnrollInvitationCommand
    ): StoreResult<InvitationEnrollmentCommit> =
        invoke(PostgresqlRpcOperation.ENROLL_INVITATION, command)

    override suspend fun createMembership(command: CreateMembershipCommand): StoreResult<Membership> =
        invoke(PostgresqlRpcOperation.CREATE_MEMBERSHIP, command)

    override suspend fun mutateMembership(command: MutateMembershipCommand): StoreResult<Membership> =
        invoke(PostgresqlRpcOperation.MUTATE_MEMBERSHIP, command)

    override suspend fun createServiceIdentity(
        command: CreateServiceIdentityCommand
    ): StoreResult<ServiceIdentityCreationCommit> =
        invoke(PostgresqlRpcOperation.CREATE_SERVICE_IDENTITY, command)

    override suspend fun mutateServiceIdentity(
        command: MutateServiceIdentityCommand
    ): StoreResult<ServiceIdentity> =
        invoke(PostgresqlRpcOperation.MUTATE_SERVICE_IDENTITY, command)

    override suspend fun createServiceCredential(
        command: CreateServiceCredentialCommand
    ): StoreResult<ServiceCredential> =
        invoke(PostgresqlRpcOperation.CREATE_SERVICE_CREDENTIAL, command)

    override suspend fun revokeServiceCredential(
        command: RevokeServiceCredentialCommand
    ): StoreResult<ServiceCredential> =
        invoke(PostgresqlRpcOperation.REVOKE_SERVICE_CREDENTIAL, command)

    override suspend fun compareAndSetDeviceGrant(
        command: CompareAndSetDeviceGrantCommand
    ): StoreResult<DeviceGrant> =
        invoke(PostgresqlRpcOperation.COMPARE_AND_SET_DEVICE_GRANT, command)

    override suspend fun exchangeDeviceGrant(
        command: ExchangeDeviceGrantCommand
    ): StoreResult<DeviceTokenIssuanceCommit> =
        invoke(PostgresqlRpcOperation.EXCHANGE_DEVICE_GRANT, command)

    override suspend fun rotateDeviceRefreshToken(
        command: RotateDeviceRefreshTokenCommand
    ): StoreResult<DeviceTokenRotationCommit> =
        invoke(PostgresqlRpcOperation.ROTATE_DEVICE_REFRESH_TOKEN, command)

    override suspend fun revokeDeviceTokenFamily(
        command: RevokeDeviceTokenFamilyCommand
    ): StoreResult<DeviceTokenFamilyRevocationCommit> =
        invoke(PostgresqlRpcOperation.REVOKE_DEVICE_TOKEN_FAMILY, command)

    override suspend fun rotateServiceCredential(
        command: RotateServiceCredentialCommand
    ): StoreResult<ServiceCredentialRotationCommit> =
        invoke(PostgresqlRpcOperation.ROTATE_SERVICE_CREDENTIAL, command)

    override suspend fun linkExternalIdentity(
        command: LinkExternalIdentityCommand
    ): StoreResult<ExternalIdentityLinkCommit> =
        invoke(PostgresqlRpcOperation.LINK_EXTERNAL_IDENTITY, command)

    override suspend fun recordExternalIdentityReplay(
        command: RecordExternalIdentityReplayCommand
    ): StoreResult<ExternalIdentityReplayReceipt> =
        invoke(PostgresqlRpcOperation.RECORD_EXTERNAL_IDENTITY_REPLAY, command)

    override suspend fun applyScimMutation(command: ApplyScimMutationCommand): StoreResult<ScimMutationCommit> =
        invoke(PostgresqlRpcOperation.APPLY_SCIM_MUTATION, command)

    override suspend fun applyScimBatch(command: ApplyScimBatchCommand): StoreResult<ScimBatchCommit> =
        invoke(PostgresqlRpcOperation.APPLY_SCIM_BATCH, command)

    private suspend inline fun <reified Request, reified Response> invoke(
        operation: PostgresqlRpcOperation,
        payload: Request
    ): StoreResult<Response> {
        initializationMutex.lock()
        val ready = try {
            initialized
        } finally {
            initializationMutex.unlock()
        }
        if (!ready) return StoreResult.Failure(PostgresqlFailureMapper.unavailable())
        return invokeRaw(operation, payload)
    }

    private suspend inline fun <reified Response> invokeRaw(
        operation: PostgresqlRpcOperation,
        payload: JsonObject
    ): StoreResult<Response> = invokeRawElement(operation, payload)

    private suspend inline fun <reified Request, reified Response> invokeRaw(
        operation: PostgresqlRpcOperation,
        payload: Request
    ): StoreResult<Response> = try {
        invokeRawElement(operation, json.encodeToJsonElement(payload))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SerializationException) {
        StoreResult.Failure(PostgresqlFailureMapper.internal())
    } catch (_: IllegalArgumentException) {
        StoreResult.Failure(PostgresqlFailureMapper.internal())
    }

    private suspend inline fun <reified Response> invokeRawElement(
        operation: PostgresqlRpcOperation,
        payload: kotlinx.serialization.json.JsonElement
    ): StoreResult<Response> = try {
        val response = transport.execute(
            PostgresqlRpcRequestEnvelope(
                operation = operation.wireName,
                environment = config.environment,
                namespace = config.namespace,
                payload = payload
            )
        )
        when (response.outcome) {
            PostgresqlRpcOutcome.FAILURE -> StoreResult.Failure(requireNotNull(response.error))
            PostgresqlRpcOutcome.SUCCESS -> StoreResult.Success(
                json.decodeFromJsonElement(response.result)
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: PostgresqlStoreException) {
        StoreResult.Failure(failure.safeError)
    } catch (_: SerializationException) {
        StoreResult.Failure(PostgresqlFailureMapper.internal())
    } catch (_: IllegalArgumentException) {
        StoreResult.Failure(PostgresqlFailureMapper.internal())
    } catch (_: Throwable) {
        StoreResult.Failure(PostgresqlFailureMapper.internal())
    }
}

@Serializable
private data class EnvironmentAssertionResult(
    val verified: Boolean,
    val environment: IdentityEnvironment,
    val namespace: String
)

@Serializable
private data class IdPayload(val id: String)

@Serializable
private data class UserIdPayload(val userId: UserId)

@Serializable
private data class OrganizationIdPayload(val organizationId: OrganizationId)

@Serializable
private data class ServiceIdentityIdPayload(val serviceIdentityId: ServiceIdentityId)

@Serializable
private data class SlugPayload(val slug: String)

@Serializable
private data class WebAuthnIdPayload(val webAuthnId: WebAuthnCredentialId)

@Serializable
private data class EmailPayload(val email: EmailAddress)

@Serializable
private data class UserOrganizationPayload(
    val userId: UserId,
    val organizationId: OrganizationId
)

@Serializable
private data class FederationProviderLookupPayload(
    val organizationId: OrganizationId,
    val providerId: String
)

@Serializable
private data class StorageKeyPayload(val storageKey: String)

@Serializable
private data class PublicPrefixPayload(val publicPrefix: String)

@Serializable
private data class PublicSelectorPayload(val publicSelector: String)

@Serializable
private data class DigestPayload(val digest: SecretDigest)

@Serializable
private data class AuditEventPayload(val event: AuditEvent)

@Serializable
private data class ExternalIdentityLookupPayload(
    val provider: String,
    val subject: ExternalSubject
)

@Serializable
private data class ScimGroupLookupPayload(
    val provider: String,
    val organizationId: OrganizationId,
    val id: String
)
