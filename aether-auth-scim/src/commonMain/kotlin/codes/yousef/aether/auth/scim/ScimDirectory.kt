package codes.yousef.aether.auth.scim

import codes.yousef.aether.auth.ApplyScimBatchCommand
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.ScimOperationId
import codes.yousef.aether.auth.UserId
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScimUserRecord(
    val id: String,
    val organizationId: OrganizationId,
    val identityUserId: UserId,
    val membershipId: MembershipId,
    val externalId: String? = null,
    val userName: String,
    val name: ScimName? = null,
    val displayName: String? = null,
    val nickName: String? = null,
    val profileUrl: String? = null,
    val title: String? = null,
    val userType: String? = null,
    val preferredLanguage: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val active: Boolean,
    val emails: List<ScimEmail> = emptyList(),
    val phoneNumbers: List<ScimMultiValue> = emptyList(),
    val ims: List<ScimMultiValue> = emptyList(),
    val photos: List<ScimMultiValue> = emptyList(),
    val addresses: List<ScimAddress> = emptyList(),
    val entitlements: List<ScimMultiValue> = emptyList(),
    val roles: List<ScimMultiValue> = emptyList(),
    val x509Certificates: List<ScimMultiValue> = emptyList(),
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
) {
    init {
        require(Regex("[A-Za-z0-9_-][A-Za-z0-9._:-]{0,254}").matches(id)) { "Invalid SCIM User ID" }
        require(userName.isNotBlank() && userName.length <= 320)
        require(externalId == null || (externalId.isNotBlank() && externalId.length <= 1_024))
        require(emails.size <= 20 && addresses.size <= 20)
        require(listOf(phoneNumbers, ims, photos, entitlements, roles, x509Certificates).all { it.size <= 100 })
        require(version >= 1)
        require(updatedAt >= createdAt)
        require(deletedAt == null || deletedAt >= createdAt)
    }

    val deleted: Boolean get() = deletedAt != null
}

@Serializable
data class ScimGroupRecord(
    val id: String,
    val organizationId: OrganizationId,
    val externalId: String? = null,
    val displayName: String,
    val memberUserResourceIds: Set<String> = emptySet(),
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
) {
    init {
        require(Regex("[A-Za-z0-9_-][A-Za-z0-9._:-]{0,254}").matches(id)) { "Invalid SCIM Group ID" }
        require(displayName.isNotBlank() && displayName.length <= 200)
        require(memberUserResourceIds.size <= 5_000)
        require(memberUserResourceIds.all { Regex("[A-Za-z0-9_-][A-Za-z0-9._:-]{0,254}").matches(it) })
        require(version >= 1)
        require(updatedAt >= createdAt)
        require(deletedAt == null || deletedAt >= createdAt)
    }

    val deleted: Boolean get() = deletedAt != null
}

@Serializable
enum class ScimResourceKind {
    @SerialName("user") USER,
    @SerialName("group") GROUP
}

/**
 * Durable operation journal entry. A directory must return the original reservation when the same
 * operation ID and fingerprint are retried, or an idempotency conflict when the fingerprint differs.
 * Persisting the exact identity batch makes a retry byte-for-byte identical even if the first
 * attempt reached IdentityStore but crashed before the SCIM projection was committed.
 */
@Serializable
data class ScimOperationReservation(
    val operationId: ScimOperationId,
    val fingerprint: String,
    val kind: ScimResourceKind,
    val resourceId: String,
    val expectedProjectionVersion: Long? = null,
    val desiredUser: ScimUserRecord? = null,
    val desiredGroup: ScimGroupRecord? = null,
    val identityBatch: ApplyScimBatchCommand,
    val reservedAt: Instant
) {
    init {
        require(fingerprint.isNotBlank() && fingerprint.length <= 128)
        require(Regex("[A-Za-z0-9_-][A-Za-z0-9._:-]{0,254}").matches(resourceId))
        require(expectedProjectionVersion == null || expectedProjectionVersion >= 1)
        require(identityBatch.operationId == operationId)
        when (kind) {
            ScimResourceKind.USER -> require(desiredUser != null && desiredGroup == null)
            ScimResourceKind.GROUP -> require(desiredGroup != null && desiredUser == null)
        }
        require((desiredUser?.id ?: desiredGroup?.id) == resourceId)
    }
}

@Serializable
data class ScimDirectoryCommit(
    val user: ScimUserRecord? = null,
    val group: ScimGroupRecord? = null,
    val alreadyCompleted: Boolean = false
) {
    init { require((user == null) != (group == null)) }
}

enum class ScimDirectoryErrorCode {
    NOT_FOUND,
    ALREADY_EXISTS,
    UNIQUENESS_CONFLICT,
    VERSION_CONFLICT,
    IDEMPOTENCY_CONFLICT,
    UNAVAILABLE,
    INTERNAL
}

data class ScimDirectoryError(
    val code: ScimDirectoryErrorCode,
    val retryable: Boolean = code == ScimDirectoryErrorCode.UNAVAILABLE
)

sealed interface ScimDirectoryResult<out T> {
    data class Success<T>(val value: T) : ScimDirectoryResult<T>
    data class Failure(val error: ScimDirectoryError) : ScimDirectoryResult<Nothing>
}

/**
 * Tenant-scoped SCIM projection and durable operation journal.
 *
 * Implementations enforce uniqueness of active `(organizationId, userName)`, User externalId,
 * Group displayName, and Group externalId values. [reserveOperation] atomically validates the
 * expected projection version and uniqueness keys and acquires a durable per-resource reservation
 * before any identity mutation can run. [completeOperation] applies the already-reserved desired
 * record/tombstone and marks the reservation complete. This ordering prevents a projection CAS or
 * uniqueness failure after IdentityStore has committed. Deleted records are retained as tombstones
 * for idempotency but omitted from reads.
 */
interface ScimDirectory {
    suspend fun findUser(organizationId: OrganizationId, id: String): ScimDirectoryResult<ScimUserRecord?>
    suspend fun listUsers(organizationId: OrganizationId): ScimDirectoryResult<List<ScimUserRecord>>
    suspend fun findGroup(organizationId: OrganizationId, id: String): ScimDirectoryResult<ScimGroupRecord?>
    suspend fun listGroups(organizationId: OrganizationId): ScimDirectoryResult<List<ScimGroupRecord>>

    suspend fun findOperation(operationId: ScimOperationId): ScimDirectoryResult<ScimOperationReservation?>
    suspend fun reserveOperation(reservation: ScimOperationReservation): ScimDirectoryResult<ScimOperationReservation>
    suspend fun completeOperation(operationId: ScimOperationId): ScimDirectoryResult<ScimDirectoryCommit>
}
