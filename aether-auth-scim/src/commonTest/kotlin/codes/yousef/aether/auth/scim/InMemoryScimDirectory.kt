package codes.yousef.aether.auth.scim

import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.ScimOperationId

internal class InMemoryScimDirectory : ScimDirectory {
    private val users = linkedMapOf<String, ScimUserRecord>()
    private val groups = linkedMapOf<String, ScimGroupRecord>()
    private val operations = linkedMapOf<ScimOperationId, ScimOperationReservation>()
    private val completed = linkedMapOf<ScimOperationId, ScimDirectoryCommit>()
    private val resourceReservations = linkedMapOf<Pair<ScimResourceKind, String>, ScimOperationId>()

    override suspend fun findUser(
        organizationId: OrganizationId,
        id: String
    ): ScimDirectoryResult<ScimUserRecord?> = success(users[id]?.takeIf {
        it.organizationId == organizationId && !it.deleted
    })

    override suspend fun listUsers(
        organizationId: OrganizationId
    ): ScimDirectoryResult<List<ScimUserRecord>> = success(
        users.values.filter { it.organizationId == organizationId && !it.deleted }
    )

    override suspend fun findGroup(
        organizationId: OrganizationId,
        id: String
    ): ScimDirectoryResult<ScimGroupRecord?> = success(groups[id]?.takeIf {
        it.organizationId == organizationId && !it.deleted
    })

    override suspend fun listGroups(
        organizationId: OrganizationId
    ): ScimDirectoryResult<List<ScimGroupRecord>> = success(
        groups.values.filter { it.organizationId == organizationId && !it.deleted }
    )

    override suspend fun findOperation(
        operationId: ScimOperationId
    ): ScimDirectoryResult<ScimOperationReservation?> = success(operations[operationId])

    override suspend fun reserveOperation(
        reservation: ScimOperationReservation
    ): ScimDirectoryResult<ScimOperationReservation> {
        val existing = operations[reservation.operationId]
        if (existing != null) {
            return if (existing.fingerprint == reservation.fingerprint) success(existing) else failure(
                ScimDirectoryErrorCode.IDEMPOTENCY_CONFLICT
            )
        }
        val resourceKey = reservation.kind to reservation.resourceId
        val holder = resourceReservations[resourceKey]
        if (holder != null && holder != reservation.operationId && completed[holder] == null) {
            return failure(ScimDirectoryErrorCode.VERSION_CONFLICT)
        }
        when (reservation.kind) {
            ScimResourceKind.USER -> {
                val desired = requireNotNull(reservation.desiredUser)
                if (!versionMatches(users[desired.id]?.version, reservation.expectedProjectionVersion)) {
                    return failure(ScimDirectoryErrorCode.VERSION_CONFLICT)
                }
                if (!desired.deleted && users.values.any {
                        !it.deleted && it.organizationId == desired.organizationId && it.id != desired.id &&
                            (it.userName.equals(desired.userName, ignoreCase = true) ||
                                (desired.externalId != null && it.externalId == desired.externalId))
                    }) {
                    return failure(ScimDirectoryErrorCode.UNIQUENESS_CONFLICT)
                }
                if (!desired.deleted && operations.values.any { pending ->
                        completed[pending.operationId] == null && pending.operationId != reservation.operationId &&
                            pending.desiredUser?.let {
                                !it.deleted && it.organizationId == desired.organizationId &&
                                    (it.userName.equals(desired.userName, ignoreCase = true) ||
                                        (desired.externalId != null && it.externalId == desired.externalId))
                            } == true
                    }) {
                    return failure(ScimDirectoryErrorCode.UNIQUENESS_CONFLICT)
                }
            }
            ScimResourceKind.GROUP -> {
                val desired = requireNotNull(reservation.desiredGroup)
                if (!versionMatches(groups[desired.id]?.version, reservation.expectedProjectionVersion)) {
                    return failure(ScimDirectoryErrorCode.VERSION_CONFLICT)
                }
                if (!desired.deleted && groups.values.any {
                        !it.deleted && it.organizationId == desired.organizationId && it.id != desired.id &&
                            (it.displayName.equals(desired.displayName, ignoreCase = true) ||
                                (desired.externalId != null && it.externalId == desired.externalId))
                    }) {
                    return failure(ScimDirectoryErrorCode.UNIQUENESS_CONFLICT)
                }
                if (!desired.deleted && operations.values.any { pending ->
                        completed[pending.operationId] == null && pending.operationId != reservation.operationId &&
                            pending.desiredGroup?.let {
                                !it.deleted && it.organizationId == desired.organizationId &&
                                    (it.displayName.equals(desired.displayName, ignoreCase = true) ||
                                        (desired.externalId != null && it.externalId == desired.externalId))
                            } == true
                    }) {
                    return failure(ScimDirectoryErrorCode.UNIQUENESS_CONFLICT)
                }
            }
        }
        operations[reservation.operationId] = reservation
        resourceReservations[resourceKey] = reservation.operationId
        return success(reservation)
    }

    override suspend fun completeOperation(
        operationId: ScimOperationId
    ): ScimDirectoryResult<ScimDirectoryCommit> {
        completed[operationId]?.let { return success(it.copy(alreadyCompleted = true)) }
        val reservation = operations[operationId] ?: return failure(ScimDirectoryErrorCode.NOT_FOUND)
        val commit = when (reservation.kind) {
            ScimResourceKind.USER -> {
                val desired = requireNotNull(reservation.desiredUser)
                val current = users[desired.id]
                if (!versionMatches(current?.version, reservation.expectedProjectionVersion)) {
                    return failure(ScimDirectoryErrorCode.VERSION_CONFLICT)
                }
                if (!desired.deleted && users.values.any {
                        !it.deleted && it.organizationId == desired.organizationId && it.id != desired.id &&
                            (it.userName.equals(desired.userName, ignoreCase = true) ||
                                (desired.externalId != null && it.externalId == desired.externalId))
                    }) {
                    return failure(ScimDirectoryErrorCode.UNIQUENESS_CONFLICT)
                }
                users[desired.id] = desired
                ScimDirectoryCommit(user = desired)
            }
            ScimResourceKind.GROUP -> {
                val desired = requireNotNull(reservation.desiredGroup)
                val current = groups[desired.id]
                if (!versionMatches(current?.version, reservation.expectedProjectionVersion)) {
                    return failure(ScimDirectoryErrorCode.VERSION_CONFLICT)
                }
                if (!desired.deleted && groups.values.any {
                        !it.deleted && it.organizationId == desired.organizationId && it.id != desired.id &&
                            (it.displayName.equals(desired.displayName, ignoreCase = true) ||
                                (desired.externalId != null && it.externalId == desired.externalId))
                    }) {
                    return failure(ScimDirectoryErrorCode.UNIQUENESS_CONFLICT)
                }
                groups[desired.id] = desired
                ScimDirectoryCommit(group = desired)
            }
        }
        completed[operationId] = commit
        return success(commit)
    }

    private fun versionMatches(current: Long?, expected: Long?): Boolean = when (expected) {
        null -> current == null
        else -> current == expected
    }

    private fun <T> success(value: T): ScimDirectoryResult<T> = ScimDirectoryResult.Success(value)
    private fun failure(code: ScimDirectoryErrorCode): ScimDirectoryResult.Failure =
        ScimDirectoryResult.Failure(ScimDirectoryError(code))
}
