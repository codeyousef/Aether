package codes.yousef.aether.auth

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Safe, protocol-independent input for turning a verified external identity into an identity
 * session. Protocol adapters must call this only after all assertion and linking checks succeed.
 */
data class FederatedIdentitySessionRequest(
    val userId: UserId,
    val providerLease: FederationProviderLease,
    val externalIdentityId: ExternalIdentityId,
    val authenticationMethod: SessionAuthenticationMethod,
    val authenticatedAt: Instant,
    val device: DeviceMetadata = DeviceMetadata(),
    val predecessorSessionId: SessionId? = null,
    val expectedPredecessorVersion: Long? = null,
    val auditRequest: AuditRequestMetadata? = null
) {
    init {
        require(authenticationMethod == providerLease.kind.sessionAuthenticationMethod) {
            "Federated session method must match its provider lease"
        }
        require((predecessorSessionId == null) == (expectedPredecessorVersion == null)) {
            "Federated predecessor selector and version must either both be present or both be absent"
        }
        require(expectedPredecessorVersion == null || expectedPredecessorVersion >= 0) {
            "Federated predecessor version must not be negative"
        }
    }
}

fun interface FederatedIdentitySessionCreator {
    suspend fun create(request: FederatedIdentitySessionRequest): IdentityOperationResult<IssuedIdentitySession>
}

/** Result of reserving a fresh opaque federation callback selector. */
sealed interface FederationCallbackStateWriteResult {
    data object Stored : FederationCallbackStateWriteResult
    data object Conflict : FederationCallbackStateWriteResult
    data object Unavailable : FederationCallbackStateWriteResult
}

/** Result of atomically deleting and returning callback state. */
sealed interface FederationCallbackStateConsumeResult<out T> {
    data class Consumed<T>(val state: T) : FederationCallbackStateConsumeResult<T>
    data object Missing : FederationCallbackStateConsumeResult<Nothing>
    data object Unavailable : FederationCallbackStateConsumeResult<Nothing>
}

/**
 * Shared consume-once correlation boundary for OIDC, SAML, and future federation adapters.
 * Implementations must never return the same value from [consume] twice.
 */
interface FederationCallbackStateStore<T> {
    /** Reserve [selector] without replacing an existing record; return Conflict on collision. */
    suspend fun store(selector: String, state: T): FederationCallbackStateWriteResult
    /** Atomically delete and return the record, or Missing when no record can be consumed. */
    suspend fun consume(selector: String): FederationCallbackStateConsumeResult<T>
}

/**
 * Loads the stable user and active tenant membership, then issues a provenance-tagged
 * SESSION-assurance session. A predecessor is rotated through one atomic store command; otherwise
 * the new session and its creation audit event are persisted atomically. The returned cookie and
 * CSRF material are show-once values; storage receives digests only.
 */
class FederatedIdentitySessionService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    identityConfig: IdentityConfig,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime),
    private val issuer: IdentitySessionIssuer = IdentitySessionIssuer(runtime, identityConfig, ids)
) : FederatedIdentitySessionCreator {
    override suspend fun create(
        request: FederatedIdentitySessionRequest
    ): IdentityOperationResult<IssuedIdentitySession> {
        when (val validated = try {
            store.validateFederationProviderLease(request.providerLease)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
        }) {
            is StoreResult.Success -> if (validated.value != request.providerLease) {
                return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            }
            is StoreResult.Failure -> return IdentityOperationResult.Failure(
                validated.error.toFederatedIdentityErrorCode()
            )
        }
        val found = try {
            store.findUser(request.userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
        }
        val user = when (found) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return IdentityOperationResult.Failure(
                found.error.toFederatedIdentityErrorCode()
            )
        }
        if (user == null || user.state != UserState.ACTIVE) {
            return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        }

        val organization = try {
            store.findOrganization(request.providerLease.organizationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
        }
        val activeOrganization = when (organization) {
            is StoreResult.Success -> organization.value
            is StoreResult.Failure -> return IdentityOperationResult.Failure(
                organization.error.toFederatedIdentityErrorCode()
            )
        }
        if (activeOrganization?.state != OrganizationState.ACTIVE) {
            return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        }

        val membership = try {
            store.findMembershipForUser(user.id, request.providerLease.organizationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
        }
        val activeMembership = when (membership) {
            is StoreResult.Success -> membership.value
            is StoreResult.Failure -> return IdentityOperationResult.Failure(
                membership.error.toFederatedIdentityErrorCode()
            )
        }
        if (activeMembership?.state != MembershipState.ACTIVE) {
            return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        }

        val predecessor = request.predecessorSessionId?.let { sessionId ->
            val foundSession = try {
                store.findSession(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
            }
            val session = when (foundSession) {
                is StoreResult.Success -> foundSession.value
                is StoreResult.Failure -> return IdentityOperationResult.Failure(
                    foundSession.error.toFederatedIdentityErrorCode()
                )
            }
            if (session == null || session.userId != user.id || session.state != SessionState.ACTIVE ||
                session.version != request.expectedPredecessorVersion ||
                session.userSessionEpoch != user.sessionEpoch
            ) {
                return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            }
            session
        }

        val issued = try {
            issuer.issueFederated(
                user = user,
                authenticationMethod = request.authenticationMethod,
                providerLease = request.providerLease,
                externalIdentityId = request.externalIdentityId,
                authenticatedAt = request.authenticatedAt,
                device = request.device,
                rotatedFrom = predecessor
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        } catch (_: Throwable) {
            return IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
        }
        val rotating = predecessor != null
        val audit = AuditEvent(
            id = ids.newAuditEventId(),
            actor = AuditActor(AuditActorType.USER, userId = user.id),
            organizationId = request.providerLease.organizationId,
            action = if (rotating) AuditAction.SESSION_ROTATED else AuditAction.SESSION_CREATED,
            target = AuditTarget(AuditTargetType.SESSION, issued.session.id.value),
            outcome = AuditOutcome.SUCCEEDED,
            request = request.auditRequest,
            occurredAt = request.authenticatedAt
        )
        return try {
            if (predecessor == null) {
                when (val created = store.createSession(CreateSessionCommand(issued.session, audit))) {
                    is StoreResult.Success -> IdentityOperationResult.Success(issued)
                    is StoreResult.Failure -> IdentityOperationResult.Failure(created.error.toIdentityErrorCode())
                }
            } else {
                when (val rotated = store.rotateSession(
                    RotateSessionCommand(
                        sessionId = predecessor.id,
                        expectedVersion = requireNotNull(request.expectedPredecessorVersion),
                        replacement = issued.session,
                        rotatedAt = request.authenticatedAt,
                        auditEvent = audit
                    )
                )) {
                    is StoreResult.Success -> IdentityOperationResult.Success(issued)
                    is StoreResult.Failure -> IdentityOperationResult.Failure(rotated.error.toIdentityErrorCode())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE)
        }
    }
}

private fun IdentityStoreError.toFederatedIdentityErrorCode(): IdentityErrorCode = when (code) {
    IdentityStoreErrorCode.UNAVAILABLE,
    IdentityStoreErrorCode.INTERNAL -> IdentityErrorCode.SERVICE_UNAVAILABLE
    else -> IdentityErrorCode.INVALID_CREDENTIALS
}

/**
 * Canonical provider lifecycle service. Stable external links and replay receipts are retained;
 * disabling advances the provider session epoch instead of updating an unbounded session set.
 */
class IdentityFederationProviderManager(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime)
) {
    suspend fun disableProvider(
        organizationId: OrganizationId,
        kind: FederationProviderKind,
        providerId: String,
        storageKey: String,
        reasonCode: String = "federation_provider_disabled",
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<FederationProviderControl> {
        if (reasonCode.isBlank() || reasonCode.length > 200 || !validProviderIdentity(kind, providerId, storageKey)) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        repeat(MAXIMUM_CAS_ATTEMPTS) {
            val current = when (val found = findProvider(organizationId, providerId)) {
                is ProviderLookup.Found -> found.control
                ProviderLookup.Missing -> null
                is ProviderLookup.Failed -> return IdentityOperationResult.Failure(found.code)
            }
            if (current != null && !current.matches(organizationId, kind, providerId, storageKey)) {
                return IdentityOperationResult.Failure(IdentityErrorCode.CONFLICT)
            }
            if (current?.state == FederationProviderState.DISABLED) {
                return IdentityOperationResult.Success(current)
            }
            val now = runtime.clock.now()
            val replacement = current?.copy(
                state = FederationProviderState.DISABLED,
                sessionEpoch = current.sessionEpoch + 1,
                version = current.version + 1,
                updatedAt = now,
                disabledAt = now,
                disabledReasonCode = reasonCode
            ) ?: FederationProviderControl(
                organizationId = organizationId,
                kind = kind,
                providerId = providerId,
                storageKey = storageKey,
                state = FederationProviderState.DISABLED,
                sessionEpoch = 1,
                createdAt = now,
                updatedAt = now,
                disabledAt = now,
                disabledReasonCode = reasonCode
            )
            when (val changed = compareAndSet(current?.version, replacement, reasonCode, request)) {
                is StoreResult.Success -> return IdentityOperationResult.Success(changed.value.control)
                is StoreResult.Failure -> if (changed.error.code != IdentityStoreErrorCode.VERSION_CONFLICT) {
                    return IdentityOperationResult.Failure(changed.error.toProviderManagerError())
                }
            }
        }
        return IdentityOperationResult.Failure(IdentityErrorCode.CONFLICT)
    }

    suspend fun enableProvider(
        organizationId: OrganizationId,
        kind: FederationProviderKind,
        providerId: String,
        storageKey: String,
        reasonCode: String = "federation_provider_enabled",
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<FederationProviderControl> {
        if (reasonCode.isBlank() || reasonCode.length > 200 || !validProviderIdentity(kind, providerId, storageKey)) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        repeat(MAXIMUM_CAS_ATTEMPTS) {
            val current = when (val found = findProvider(organizationId, providerId)) {
                is ProviderLookup.Found -> found.control
                ProviderLookup.Missing -> return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
                is ProviderLookup.Failed -> return IdentityOperationResult.Failure(found.code)
            }
            if (!current.matches(organizationId, kind, providerId, storageKey)) {
                return IdentityOperationResult.Failure(IdentityErrorCode.CONFLICT)
            }
            if (current.state == FederationProviderState.ENABLED) {
                return IdentityOperationResult.Success(current)
            }
            val now = runtime.clock.now()
            val replacement = current.copy(
                state = FederationProviderState.ENABLED,
                version = current.version + 1,
                updatedAt = now,
                disabledAt = null,
                disabledReasonCode = null
            )
            when (val changed = compareAndSet(current.version, replacement, reasonCode, request)) {
                is StoreResult.Success -> return IdentityOperationResult.Success(changed.value.control)
                is StoreResult.Failure -> if (changed.error.code != IdentityStoreErrorCode.VERSION_CONFLICT) {
                    return IdentityOperationResult.Failure(changed.error.toProviderManagerError())
                }
            }
        }
        return IdentityOperationResult.Failure(IdentityErrorCode.CONFLICT)
    }

    private suspend fun findProvider(
        organizationId: OrganizationId,
        providerId: String
    ): ProviderLookup = try {
        when (val found = store.findFederationProviderControl(organizationId, providerId)) {
            is StoreResult.Success -> found.value?.let(ProviderLookup::Found) ?: ProviderLookup.Missing
            is StoreResult.Failure -> ProviderLookup.Failed(found.error.toProviderManagerError())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        ProviderLookup.Failed(IdentityErrorCode.SERVICE_UNAVAILABLE)
    }

    private suspend fun compareAndSet(
        expectedVersion: Long?,
        replacement: FederationProviderControl,
        reasonCode: String,
        request: AuditRequestMetadata?
    ): StoreResult<FederationProviderStateCommit> {
        val action = when (replacement.state) {
            FederationProviderState.ENABLED -> AuditAction.FEDERATION_PROVIDER_ENABLED
            FederationProviderState.DISABLED -> AuditAction.FEDERATION_PROVIDER_DISABLED
        }
        val audit = AuditEvent(
            id = ids.newAuditEventId(),
            actor = AuditActor(AuditActorType.SYSTEM),
            organizationId = replacement.organizationId,
            action = action,
            target = AuditTarget(AuditTargetType.FEDERATION_PROVIDER, replacement.storageKey),
            outcome = AuditOutcome.SUCCEEDED,
            reasonCode = reasonCode,
            request = request,
            occurredAt = replacement.updatedAt
        )
        return try {
            store.compareAndSetFederationProviderState(
                CompareAndSetFederationProviderStateCommand(expectedVersion, replacement, audit)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            StoreResult.Failure(IdentityStoreError(IdentityStoreErrorCode.UNAVAILABLE, retryable = true))
        }
    }

    private fun validProviderIdentity(
        kind: FederationProviderKind,
        providerId: String,
        storageKey: String
    ): Boolean = runCatching {
        requireFederationProviderIdentity(kind, providerId, storageKey)
    }.isSuccess

    private fun FederationProviderControl.matches(
        organizationId: OrganizationId,
        kind: FederationProviderKind,
        providerId: String,
        storageKey: String
    ): Boolean = this.organizationId == organizationId && this.kind == kind &&
        this.providerId == providerId && this.storageKey == storageKey

    private sealed interface ProviderLookup {
        data class Found(val control: FederationProviderControl) : ProviderLookup
        data object Missing : ProviderLookup
        data class Failed(val code: IdentityErrorCode) : ProviderLookup
    }

    private fun IdentityStoreError.toProviderManagerError(): IdentityErrorCode = when (code) {
        IdentityStoreErrorCode.VERSION_CONFLICT,
        IdentityStoreErrorCode.ALREADY_EXISTS,
        IdentityStoreErrorCode.UNIQUE_CONSTRAINT -> IdentityErrorCode.CONFLICT
        IdentityStoreErrorCode.NOT_FOUND -> IdentityErrorCode.NOT_FOUND
        IdentityStoreErrorCode.UNAVAILABLE,
        IdentityStoreErrorCode.INTERNAL -> IdentityErrorCode.SERVICE_UNAVAILABLE
        else -> IdentityErrorCode.REQUEST_INVALID
    }

    private companion object {
        const val MAXIMUM_CAS_ATTEMPTS = 8
    }
}
