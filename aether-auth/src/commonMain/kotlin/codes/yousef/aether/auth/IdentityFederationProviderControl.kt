package codes.yousef.aether.auth

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val FEDERATION_PROVIDER_DIGEST_LENGTH = 43
private val FEDERATION_PROVIDER_ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,62}")

/** Protocol implemented by one tenant-scoped federation provider. */
@Serializable
enum class FederationProviderKind {
    @SerialName("oidc") OIDC,
    @SerialName("saml") SAML;

    val sessionAuthenticationMethod: SessionAuthenticationMethod
        get() = when (this) {
            OIDC -> SessionAuthenticationMethod.OIDC
            SAML -> SessionAuthenticationMethod.SAML
        }

    internal val storageKeyPrefix: String
        get() = when (this) {
            OIDC -> "oidc."
            SAML -> "saml."
        }
}

@Serializable
enum class FederationProviderState {
    @SerialName("enabled") ENABLED,
    @SerialName("disabled") DISABLED
}

/**
 * Canonical persisted control-plane state for one route-visible federation provider.
 *
 * [organizationId], [kind], [providerId], and [storageKey] are immutable after insertion.
 * Stores enforce uniqueness for both the `(organizationId, providerId)` route selector and the
 * globally stable [storageKey]. Disabling advances [sessionEpoch], invalidating every session
 * created by an earlier provider epoch without an unbounded session-row update.
 */
@Serializable
data class FederationProviderControl(
    val organizationId: OrganizationId,
    val kind: FederationProviderKind,
    val providerId: String,
    val storageKey: String,
    val state: FederationProviderState = FederationProviderState.ENABLED,
    val sessionEpoch: Long = 0,
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val disabledAt: Instant? = null,
    val disabledReasonCode: String? = null
) {
    init {
        requireFederationProviderIdentity(kind, providerId, storageKey)
        require(sessionEpoch >= 0) { "Federation provider session epoch must not be negative" }
        require(version >= 0) { "Federation provider version must not be negative" }
        require(updatedAt >= createdAt) { "Federation provider update must not precede creation" }
        when (state) {
            FederationProviderState.ENABLED -> require(disabledAt == null && disabledReasonCode == null) {
                "An enabled federation provider cannot retain disabled metadata"
            }
            FederationProviderState.DISABLED -> {
                val transitionTime = requireNotNull(disabledAt) {
                    "A disabled federation provider requires a transition time"
                }
                val reasonCode = requireNotNull(disabledReasonCode) {
                    "A disabled federation provider requires a reason code"
                }
                require(transitionTime == updatedAt && transitionTime >= createdAt) {
                    "A disabled federation provider must record its exact transition time"
                }
                require(reasonCode.isNotBlank() && reasonCode.length <= 200) {
                    "A disabled federation provider requires a bounded reason code"
                }
            }
        }
    }
}

/**
 * An enabled-state snapshot carried through one federation flow.
 *
 * Exact version matching invalidates callbacks that began before any disable/re-enable cycle;
 * [sessionEpoch] is separately persisted into user sessions to keep old sessions invalid after
 * the provider is re-enabled.
 */
@Serializable
data class FederationProviderLease(
    val organizationId: OrganizationId,
    val kind: FederationProviderKind,
    val providerId: String,
    val storageKey: String,
    val sessionEpoch: Long,
    val version: Long
) {
    init {
        requireFederationProviderIdentity(kind, providerId, storageKey)
        require(sessionEpoch >= 0) { "Federation provider lease epoch must not be negative" }
        require(version >= 0) { "Federation provider lease version must not be negative" }
    }
}

@Serializable
data class AcquireFederationProviderLeaseCommand(
    val organizationId: OrganizationId,
    val kind: FederationProviderKind,
    val providerId: String,
    val storageKey: String,
    val acquiredAt: Instant
) {
    init {
        requireFederationProviderIdentity(kind, providerId, storageKey)
    }
}

/** One compare-and-set lifecycle transition coupled to its audit event. */
@Serializable
data class CompareAndSetFederationProviderStateCommand(
    val expectedVersion: Long?,
    val replacement: FederationProviderControl,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion == null || expectedVersion >= 0) {
            "Expected federation provider version must not be negative"
        }
        if (expectedVersion == null) {
            require(replacement.state == FederationProviderState.DISABLED &&
                replacement.version == 0L &&
                replacement.sessionEpoch == 1L &&
                replacement.createdAt == replacement.updatedAt
            ) { "An absent provider may only be atomically created in its initial disabled state" }
        } else {
            require(replacement.version == expectedVersion + 1) {
                "Federation provider replacement must advance version exactly once"
            }
        }
        val expectedAction = when (replacement.state) {
            FederationProviderState.ENABLED -> AuditAction.FEDERATION_PROVIDER_ENABLED
            FederationProviderState.DISABLED -> AuditAction.FEDERATION_PROVIDER_DISABLED
        }
        require(auditEvent.organizationId == replacement.organizationId &&
            auditEvent.action == expectedAction &&
            auditEvent.target?.type == AuditTargetType.FEDERATION_PROVIDER &&
            auditEvent.target.id == replacement.storageKey &&
            auditEvent.outcome == AuditOutcome.SUCCEEDED &&
            auditEvent.occurredAt == replacement.updatedAt
        ) { "Federation provider transition requires a matching provider-targeted audit event" }
        if (replacement.state == FederationProviderState.DISABLED) {
            require(auditEvent.reasonCode == replacement.disabledReasonCode) {
                "Federation provider disable reason must match its audit event"
            }
        }
    }
}

@Serializable
data class FederationProviderStateCommit(
    val control: FederationProviderControl,
    val auditEvent: AuditEvent
)

internal fun FederationProviderControl.toLease(): FederationProviderLease {
    require(state == FederationProviderState.ENABLED) { "A disabled federation provider cannot issue a lease" }
    return FederationProviderLease(
        organizationId = organizationId,
        kind = kind,
        providerId = providerId,
        storageKey = storageKey,
        sessionEpoch = sessionEpoch,
        version = version
    )
}

internal fun requireFederationProviderIdentity(
    kind: FederationProviderKind,
    providerId: String,
    storageKey: String
) {
    require(FEDERATION_PROVIDER_ID_PATTERN.matches(providerId)) {
        "Federation provider ID must be a canonical route selector"
    }
    requireFederationProviderStorageKey(kind, storageKey)
}

internal fun requireFederationProviderStorageKey(kind: FederationProviderKind, storageKey: String) {
    val prefix = kind.storageKeyPrefix
    require(storageKey.length == prefix.length + FEDERATION_PROVIDER_DIGEST_LENGTH &&
        storageKey.startsWith(prefix)
    ) { "Federation provider storage key must match its provider kind" }
    val encodedDigest = storageKey.substring(prefix.length)
    val digest = runCatching { Base64Url.decode(encodedDigest, maximumBytes = 32) }.getOrNull()
    try {
        require(digest?.size == 32 && Base64Url.encode(digest) == encodedDigest) {
            "Federation provider storage key must contain a canonical SHA-256 digest"
        }
    } finally {
        digest?.fill(0)
    }
}

internal fun SessionAuthenticationMethod.federationProviderKindOrNull(): FederationProviderKind? = when (this) {
    SessionAuthenticationMethod.OIDC -> FederationProviderKind.OIDC
    SessionAuthenticationMethod.SAML -> FederationProviderKind.SAML
    else -> null
}
