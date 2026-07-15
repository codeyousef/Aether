package codes.yousef.aether.auth

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ServiceCredentialView(
    val id: ServiceCredentialId,
    val serviceIdentityId: ServiceIdentityId,
    val publicPrefix: String,
    val capabilities: Set<Capability>,
    val state: ServiceCredentialState,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant? = null,
    val rotatedAt: Instant? = null,
    val revokedAt: Instant? = null
)

class IssuedServiceCredential internal constructor(
    val credential: ServiceCredentialView,
    private val token: String
) {
    fun revealToken(): String = token
    override fun toString(): String = "IssuedServiceCredential(credential=$credential, token=<redacted>)"
}

@Serializable
data class CreatedServiceIdentity(
    val identity: ServiceIdentity,
    val credential: ServiceCredentialView
)

class IssuedServiceIdentity internal constructor(
    val value: CreatedServiceIdentity,
    private val token: String
) {
    fun revealToken(): String = token
    override fun toString(): String = "IssuedServiceIdentity(value=$value, token=<redacted>)"
}

/** Organization-bound service principals backed only by scoped, expiring opaque credentials. */
class IdentityServiceIdentityService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val config: IdentityConfig,
    allowedCapabilities: Set<Capability>,
    private val capabilityResolver: CapabilityResolver = CapabilityResolver.NONE,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime)
) {
    private val allowedCapabilities: Set<Capability> = allowedCapabilities.toSet()

    init {
        require(this.allowedCapabilities.isNotEmpty()) {
            "Service credential capability allowlist must not be empty"
        }
        require(this.allowedCapabilities.none { it.isForbiddenForServiceCredential() }) {
            "Service credential allowlists may contain application capabilities only"
        }
    }

    suspend fun create(
        actor: IdentityContext,
        organizationId: OrganizationId,
        name: String,
        description: String? = null,
        capabilities: Set<Capability>,
        lifetime: IdentityDuration = config.lifetimes.serviceCredential,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<IssuedServiceIdentity> {
        val now = runtime.clock.now()
        val validation = validateManagement(actor, organizationId, capabilities, lifetime, now)
        if (validation != null) return IdentityOperationResult.Failure(validation)
        if (name.isBlank() || name.length > 200 || (description?.length ?: 0) > 2_000) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        val identity = ServiceIdentity(
            id = ids.newServiceIdentityId(),
            organizationId = organizationId,
            name = name,
            description = description,
            capabilities = capabilities,
            createdAt = now,
            updatedAt = now
        )
        val material = issueCredential(identity, capabilities, lifetime, now)
        return try {
            val audit = audit(
                actor,
                AuditAction.SERVICE_IDENTITY_CREATED,
                AuditTarget(AuditTargetType.SERVICE_IDENTITY, identity.id.value),
                organizationId,
                now,
                request
            )
            when (val result = store.createServiceIdentity(
                CreateServiceIdentityCommand(identity, material.credential, audit)
            )) {
                is StoreResult.Success -> IdentityOperationResult.Success(
                    IssuedServiceIdentity(
                        CreatedServiceIdentity(result.value.identity, result.value.initialCredential.toView()),
                        material.token
                    )
                )
                is StoreResult.Failure -> operationFailure(result.error)
            }
        } finally {
            material.clear()
        }
    }

    suspend fun list(
        actor: IdentityContext,
        organizationId: OrganizationId
    ): IdentityOperationResult<List<ServiceIdentity>> {
        if (!authorized(actor, organizationId, Capability.SERVICE_IDENTITY_READ)) return notFound()
        return when (val result = store.listServiceIdentitiesForOrganization(organizationId)) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value)
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun listCredentials(
        actor: IdentityContext,
        organizationId: OrganizationId,
        serviceIdentityId: ServiceIdentityId
    ): IdentityOperationResult<List<ServiceCredentialView>> {
        if (!authorized(actor, organizationId, Capability.SERVICE_IDENTITY_READ)) return notFound()
        val identity = findOwnedIdentity(serviceIdentityId, organizationId) ?: return notFound()
        return when (val result = store.listServiceCredentialsForIdentity(identity.id)) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value.map(ServiceCredential::toView))
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun createCredential(
        actor: IdentityContext,
        organizationId: OrganizationId,
        serviceIdentityId: ServiceIdentityId,
        capabilities: Set<Capability>,
        lifetime: IdentityDuration = config.lifetimes.serviceCredential,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<IssuedServiceCredential> {
        val now = runtime.clock.now()
        val validation = validateManagement(actor, organizationId, capabilities, lifetime, now)
        if (validation != null) return IdentityOperationResult.Failure(validation)
        val identity = findOwnedIdentity(serviceIdentityId, organizationId) ?: return notFound()
        if (identity.state != ServiceIdentityState.ACTIVE || !identity.capabilities.containsAll(capabilities)) return notFound()
        val material = issueCredential(identity, capabilities, lifetime, now)
        return try {
            val audit = audit(
                actor,
                AuditAction.SERVICE_CREDENTIAL_CREATED,
                AuditTarget(AuditTargetType.SERVICE_CREDENTIAL, material.credential.id.value),
                organizationId,
                now,
                request
            )
            when (val result = store.createServiceCredential(CreateServiceCredentialCommand(material.credential, audit))) {
                is StoreResult.Success -> IdentityOperationResult.Success(
                    IssuedServiceCredential(result.value.toView(), material.token)
                )
                is StoreResult.Failure -> operationFailure(result.error)
            }
        } finally {
            material.clear()
        }
    }

    suspend fun rotateCredential(
        actor: IdentityContext,
        organizationId: OrganizationId,
        credentialId: ServiceCredentialId,
        lifetime: IdentityDuration = config.lifetimes.serviceCredential,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<IssuedServiceCredential> {
        val now = runtime.clock.now()
        if (!authorized(actor, organizationId, Capability.SERVICE_IDENTITY_MANAGE)) return notFound()
        if (!isRecentPasskey(actor, now)) return IdentityOperationResult.Failure(IdentityErrorCode.STEP_UP_REQUIRED)
        if (lifetime.seconds > config.lifetimes.serviceCredentialMaximum.seconds) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        val existing = findCredential(credentialId) ?: return notFound()
        val identity = findOwnedIdentity(existing.serviceIdentityId, organizationId) ?: return notFound()
        if (existing.state != ServiceCredentialState.ACTIVE || identity.state != ServiceIdentityState.ACTIVE) return notFound()
        if (!canDelegate(actor, existing.capabilities)) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        val material = issueCredential(identity, existing.capabilities, lifetime, now)
        return try {
            val audit = audit(
                actor,
                AuditAction.SERVICE_CREDENTIAL_ROTATED,
                AuditTarget(AuditTargetType.SERVICE_CREDENTIAL, existing.id.value),
                organizationId,
                now,
                request
            )
            when (val result = store.rotateServiceCredential(
                RotateServiceCredentialCommand(existing.id, existing.version, material.credential, now, audit)
            )) {
                is StoreResult.Success -> IdentityOperationResult.Success(
                    IssuedServiceCredential(result.value.replacement.toView(), material.token)
                )
                is StoreResult.Failure -> operationFailure(result.error)
            }
        } finally {
            material.clear()
        }
    }

    suspend fun revokeCredential(
        actor: IdentityContext,
        organizationId: OrganizationId,
        credentialId: ServiceCredentialId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<ServiceCredentialView> {
        val now = runtime.clock.now()
        if (!authorized(actor, organizationId, Capability.SERVICE_IDENTITY_MANAGE)) return notFound()
        if (!isRecentPasskey(actor, now)) return IdentityOperationResult.Failure(IdentityErrorCode.STEP_UP_REQUIRED)
        val existing = findCredential(credentialId) ?: return notFound()
        findOwnedIdentity(existing.serviceIdentityId, organizationId) ?: return notFound()
        val audit = audit(
            actor,
            AuditAction.SERVICE_CREDENTIAL_REVOKED,
            AuditTarget(AuditTargetType.SERVICE_CREDENTIAL, existing.id.value),
            organizationId,
            now,
            request
        )
        return when (val result = store.revokeServiceCredential(
            RevokeServiceCredentialCommand(existing.id, existing.version, now, audit)
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value.toView())
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun revokeIdentity(
        actor: IdentityContext,
        organizationId: OrganizationId,
        serviceIdentityId: ServiceIdentityId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<ServiceIdentity> {
        val now = runtime.clock.now()
        if (!authorized(actor, organizationId, Capability.SERVICE_IDENTITY_MANAGE)) return notFound()
        if (!isRecentPasskey(actor, now)) return IdentityOperationResult.Failure(IdentityErrorCode.STEP_UP_REQUIRED)
        val identity = findOwnedIdentity(serviceIdentityId, organizationId) ?: return notFound()
        if (identity.state == ServiceIdentityState.REVOKED) return notFound()
        val replacement = identity.copy(
            state = ServiceIdentityState.REVOKED,
            version = identity.version + 1,
            updatedAt = now,
            revokedAt = now
        )
        val audit = audit(
            actor,
            AuditAction.SERVICE_IDENTITY_REVOKED,
            AuditTarget(AuditTargetType.SERVICE_IDENTITY, identity.id.value),
            organizationId,
            now,
            request
        )
        return when (val result = store.mutateServiceIdentity(
            MutateServiceIdentityCommand(identity.id, identity.version, replacement, now, audit)
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value)
            is StoreResult.Failure -> operationFailure(result.error)
        }
    }

    suspend fun authenticate(token: String): IdentityOperationResult<IdentityContext> {
        val parsed = parseToken(token) ?: return invalidCredentials()
        try {
            val credential = when (val found = store.findServiceCredentialByPrefix(parsed.prefix)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return operationFailure(found.error)
            } ?: return invalidCredentials()
            if (!verifyDigest(parsed, credential.secretDigest)) return invalidCredentials()
            val now = runtime.clock.now()
            val stateAllowed = when (credential.state) {
                ServiceCredentialState.ACTIVE -> true
                ServiceCredentialState.ROTATED -> credential.rotatedAt?.let {
                    now < it + config.lifetimes.serviceCredentialOverlap.seconds.seconds
                } == true
                ServiceCredentialState.REVOKED,
                ServiceCredentialState.EXPIRED -> false
            }
            if (!stateAllowed || credential.expiresAt?.let { now >= it } != false) return invalidCredentials()
            val identity = when (val found = store.findServiceIdentity(credential.serviceIdentityId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return operationFailure(found.error)
            } ?: return invalidCredentials()
            val organization = when (val found = store.findOrganization(identity.organizationId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return operationFailure(found.error)
            } ?: return invalidCredentials()
            if (identity.state != ServiceIdentityState.ACTIVE || organization.state != OrganizationState.ACTIVE ||
                !identity.capabilities.containsAll(credential.capabilities) ||
                !allowedCapabilities.containsAll(credential.capabilities) ||
                credential.capabilities.any { it.isForbiddenForServiceCredential() }
            ) return invalidCredentials()
            val principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.SERVICE,
                serviceIdentityId = identity.id,
                displayName = identity.name,
                assurance = AuthenticationAssurance.SERVICE_CREDENTIAL,
                authenticatedAt = now,
                directCapabilities = credential.capabilities
            )
            return IdentityOperationResult.Success(IdentityContext(principal = principal, organization = organization))
        } finally {
            parsed.secret.fill(0)
        }
    }

    private suspend fun issueCredential(
        identity: ServiceIdentity,
        capabilities: Set<Capability>,
        lifetime: IdentityDuration,
        now: Instant
    ): IssuedMaterial {
        val id = ids.newServiceCredentialId()
        val secret = runtime.secureRandom.nextBytes(SERVICE_CREDENTIAL_SECRET_BYTES)
        require(secret.size == SERVICE_CREDENTIAL_SECRET_BYTES) {
            "Secure random provider returned invalid service credential material"
        }
        return try {
            val digest = credentialDigest(id.value, secret, config.keys.serviceCredentialPepper)
            IssuedMaterial(
                credential = ServiceCredential(
                    id = id,
                    serviceIdentityId = identity.id,
                    publicPrefix = id.value,
                    secretDigest = digest,
                    capabilities = capabilities,
                    createdAt = now,
                    expiresAt = now + lifetime.seconds.seconds
                ),
                token = "${id.value}.${Base64Url.encode(secret)}",
                secret = secret.copyOf()
            )
        } finally {
            secret.fill(0)
        }
    }

    private suspend fun credentialDigest(
        prefix: String,
        secret: ByteArray,
        reference: SecretReference
    ): SecretDigest {
        val input = "$SERVICE_CREDENTIAL_DIGEST_CONTEXT$prefix\u0000".encodeToByteArray() + secret
        return try {
            val digest = runtime.crypto.hmacSha256(runtime.secrets.resolve(reference), input)
            try {
                require(digest.size == 32) { "HMAC-SHA-256 provider returned an invalid digest" }
                SecretDigest(DigestAlgorithm.HMAC_SHA256, Base64Url.encode(digest), reference.version)
            } finally {
                digest.fill(0)
            }
        } finally {
            input.fill(0)
        }
    }

    private suspend fun verifyDigest(parsed: ParsedToken, stored: SecretDigest): Boolean {
        if (stored.algorithm != DigestAlgorithm.HMAC_SHA256) return false
        val reference = config.keys.serviceCredentialPepper(stored.keyVersion) ?: return false
        val actual = credentialDigest(parsed.prefix, parsed.secret, reference)
        val expectedBytes = runCatching { Base64Url.decode(stored.encoded, maximumBytes = 32) }.getOrNull()
            ?: return false
        val actualBytes = runCatching { Base64Url.decode(actual.encoded, maximumBytes = 32) }.getOrNull()
            ?: return false
        return try {
            expectedBytes.size == 32 && actualBytes.size == 32 &&
                runtime.crypto.constantTimeEquals(expectedBytes, actualBytes)
        } finally {
            expectedBytes.fill(0)
            actualBytes.fill(0)
        }
    }

    private fun parseToken(value: String): ParsedToken? {
        if (value.length !in 20..512 || value.count { it == '.' } != 1) return null
        val prefix = value.substringBefore('.')
        if (runCatching { requireValidIdentityId(prefix, "service credential prefix") }.isFailure) return null
        val secret = runCatching {
            Base64Url.decode(value.substringAfter('.'), maximumBytes = SERVICE_CREDENTIAL_SECRET_BYTES)
        }.getOrNull() ?: return null
        if (secret.size != SERVICE_CREDENTIAL_SECRET_BYTES) {
            secret.fill(0)
            return null
        }
        return ParsedToken(prefix, secret)
    }

    private suspend fun findOwnedIdentity(id: ServiceIdentityId, organizationId: OrganizationId): ServiceIdentity? =
        when (val result = store.findServiceIdentity(id)) {
            is StoreResult.Success -> result.value?.takeIf { it.organizationId == organizationId }
            is StoreResult.Failure -> null
        }

    private suspend fun findCredential(id: ServiceCredentialId): ServiceCredential? {
        // Prefixes are credential IDs for the first release, keeping lookup bounded without exposing a second selector.
        return when (val result = store.findServiceCredentialByPrefix(id.value)) {
            is StoreResult.Success -> result.value?.takeIf { it.id == id }
            is StoreResult.Failure -> null
        }
    }

    private fun validateManagement(
        actor: IdentityContext,
        organizationId: OrganizationId,
        capabilities: Set<Capability>,
        lifetime: IdentityDuration,
        now: Instant
    ): IdentityErrorCode? = when {
        !authorized(actor, organizationId, Capability.SERVICE_IDENTITY_MANAGE) -> IdentityErrorCode.NOT_FOUND
        !isRecentPasskey(actor, now) -> IdentityErrorCode.STEP_UP_REQUIRED
        !canDelegate(actor, capabilities) ||
            lifetime.seconds > config.lifetimes.serviceCredentialMaximum.seconds -> IdentityErrorCode.REQUEST_INVALID
        else -> null
    }

    private fun canDelegate(actor: IdentityContext, capabilities: Set<Capability>): Boolean =
        capabilities.isNotEmpty() &&
            capabilities.none { it.isForbiddenForServiceCredential() } &&
            allowedCapabilities.containsAll(capabilities) &&
            actor.capabilities(capabilityResolver).containsAll(capabilities)

    private fun authorized(context: IdentityContext, organizationId: OrganizationId, capability: Capability): Boolean =
        context.principal?.kind == IdentityPrincipalKind.USER &&
            context.session?.state == SessionState.ACTIVE &&
            context.session.assurance != AuthenticationAssurance.RECOVERY &&
            context.organization?.id == organizationId && context.organization.state == OrganizationState.ACTIVE &&
            context.membership?.state == MembershipState.ACTIVE && context.hasCapability(capability, capabilityResolver)

    private fun isRecentPasskey(context: IdentityContext, now: Instant): Boolean {
        val principal = context.principal ?: return false
        return principal.assurance.satisfies(AuthenticationAssurance.PASSKEY) &&
            principal.authenticatedAt <= now &&
            now - principal.authenticatedAt <= config.lifetimes.recentPasskey.seconds.seconds
    }

    private fun audit(
        context: IdentityContext,
        action: AuditAction,
        target: AuditTarget,
        organizationId: OrganizationId,
        at: Instant,
        request: AuditRequestMetadata?
    ): AuditEvent = AuditEvent(
        id = ids.newAuditEventId(),
        actor = AuditActor(AuditActorType.USER, userId = requireNotNull(context.principal?.userId)),
        action = action,
        target = target,
        organizationId = organizationId,
        outcome = AuditOutcome.SUCCEEDED,
        request = request,
        occurredAt = at
    )

    private fun <T> invalidCredentials(): IdentityOperationResult<T> =
        IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)

    private fun <T> notFound(): IdentityOperationResult<T> = IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)

    private fun <T> operationFailure(error: IdentityStoreError): IdentityOperationResult<T> =
        IdentityOperationResult.Failure(when (error.code) {
            IdentityStoreErrorCode.NOT_FOUND -> IdentityErrorCode.NOT_FOUND
            IdentityStoreErrorCode.VERSION_CONFLICT,
            IdentityStoreErrorCode.ALREADY_EXISTS,
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT -> IdentityErrorCode.CONFLICT
            IdentityStoreErrorCode.UNAVAILABLE,
            IdentityStoreErrorCode.INTERNAL -> IdentityErrorCode.SERVICE_UNAVAILABLE
            else -> IdentityErrorCode.INVALID_CREDENTIALS
        })

    private data class ParsedToken(val prefix: String, val secret: ByteArray)

    private class IssuedMaterial(
        val credential: ServiceCredential,
        val token: String,
        private val secret: ByteArray
    ) {
        fun clear() = secret.fill(0)
        override fun toString(): String = "IssuedMaterial(credential=${credential.id}, token=<redacted>)"
    }

    companion object {
        const val SERVICE_CREDENTIAL_SECRET_BYTES: Int = 32
        private const val SERVICE_CREDENTIAL_DIGEST_CONTEXT = "aether-service-credential-v1\u0000"
    }
}

private fun Capability.isForbiddenForServiceCredential(): Boolean =
    this == Capability.ACCOUNT_RECOVERY_ADMIN || this in Capability.IDENTITY_MANAGEMENT

private fun ServiceCredential.toView(): ServiceCredentialView = ServiceCredentialView(
    id = id,
    serviceIdentityId = serviceIdentityId,
    publicPrefix = publicPrefix,
    capabilities = capabilities,
    state = state,
    createdAt = createdAt,
    expiresAt = expiresAt,
    lastUsedAt = lastUsedAt,
    rotatedAt = rotatedAt,
    revokedAt = revokedAt
)
