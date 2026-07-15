package codes.yousef.aether.auth.scim

import codes.yousef.aether.auth.ApplyScimBatchCommand
import codes.yousef.aether.auth.ApplyScimMutationCommand
import codes.yousef.aether.auth.AuditAction
import codes.yousef.aether.auth.AuditActor
import codes.yousef.aether.auth.AuditActorType
import codes.yousef.aether.auth.AuditEvent
import codes.yousef.aether.auth.AuditOutcome
import codes.yousef.aether.auth.AuditRequestMetadata
import codes.yousef.aether.auth.AuditTarget
import codes.yousef.aether.auth.AuditTargetType
import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.aether.auth.ExternalSubject
import codes.yousef.aether.auth.IdentityIdFactory
import codes.yousef.aether.auth.IdentityRuntime
import codes.yousef.aether.auth.IdentityStore
import codes.yousef.aether.auth.IdentityStoreError
import codes.yousef.aether.auth.IdentityStoreErrorCode
import codes.yousef.aether.auth.Membership
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.MembershipState
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.OrganizationState
import codes.yousef.aether.auth.ScimMutation
import codes.yousef.aether.auth.ScimMutationType
import codes.yousef.aether.auth.ScimOperationId
import codes.yousef.aether.auth.ScimGroup
import codes.yousef.aether.auth.ScimGroupState
import codes.yousef.aether.auth.ScimTenantRevocation
import codes.yousef.aether.auth.StoreResult
import codes.yousef.aether.auth.User
import codes.yousef.aether.auth.UserState
import kotlin.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ScimConfig(
    val organizationId: OrganizationId,
    val providerName: String,
    val scimBaseUrl: String,
    val groupRoleMappings: Map<String, OrganizationRole> = emptyMap(),
    val maximumPageSize: Int = 1_000,
    val jsonLimits: ScimJsonLimits = ScimJsonLimits(),
    val requireVersionPreconditions: Boolean = true,
    val enabled: Boolean = true
) {
    init {
        require(Regex("[a-z0-9][a-z0-9._-]{0,119}").matches(providerName)) { "Invalid SCIM provider name" }
        require(scimBaseUrl == scimBaseUrl.trim() && '?' !in scimBaseUrl && '#' !in scimBaseUrl)
        val separator = scimBaseUrl.indexOf("://")
        require(separator > 0)
        val scheme = scimBaseUrl.substring(0, separator)
        val authority = scimBaseUrl.substring(separator + 3).substringBefore('/')
        require(authority.isNotBlank() && '@' !in authority && authority.none(Char::isWhitespace))
        val loopback = authority == "localhost" || authority.startsWith("localhost:") ||
            authority == "127.0.0.1" || authority.startsWith("127.0.0.1:") ||
            authority == "[::1]" || authority.startsWith("[::1]:")
        require(scimBaseUrl.endsWith("/scim/v2") && (scheme == "https" || (scheme == "http" && loopback))) {
            "SCIM base URL must be HTTPS or loopback HTTP and end in /scim/v2"
        }
        require(maximumPageSize in 1..5_000)
        require(groupRoleMappings.keys.all { it.isNotBlank() && it.length <= 1_024 })
        require(OrganizationRole.OWNER !in groupRoleMappings.values) {
            "SCIM group mappings cannot grant organization ownership"
        }
    }

    internal val tenantProviderKey: String = "$providerName:${organizationId.value}"

    internal fun roleFor(group: ScimGroupRecord): OrganizationRole? =
        (group.externalId?.let(groupRoleMappings::get) ?: groupRoleMappings[group.id])
            ?.takeUnless { it == OrganizationRole.OWNER }
}

/**
 * Storage-neutral RFC 7643/7644 Users and Groups engine for the fixed `/scim/v2` surface.
 * Authentication and routing integration remain host concerns; all protocol bodies and errors are
 * generated here identically on JVM, wasmJs, and wasmWasi.
 */
class ScimEngine(
    private val identityStore: IdentityStore,
    private val directory: ScimDirectory,
    private val runtime: IdentityRuntime,
    private val config: ScimConfig
) {
    internal val configuredOrganizationId: OrganizationId = config.organizationId
    private val ids = IdentityIdFactory(runtime)
    private val boundedJson = BoundedScimJson(config.jsonLimits)
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        // SCIM requires `schemas` on every resource/message; its default must always be emitted.
        encodeDefaults = true
        isLenient = false
    }

    suspend fun handle(request: ScimRequest): ScimResponse = try {
        handleValidated(request)
    } catch (failure: ScimJsonException) {
        val type = if (failure.safeCode in setOf("invalid_value", "invalid_document")) {
            ScimErrorType.INVALID_VALUE
        } else {
            ScimErrorType.INVALID_SYNTAX
        }
        error(400, type, "The SCIM JSON document is invalid.", request)
    } catch (failure: ScimPatchException) {
        error(400, failure.type, "The SCIM PATCH operation is invalid.", request)
    } catch (_: ScimFilterException) {
        error(400, ScimErrorType.INVALID_FILTER, "The SCIM filter is invalid.", request)
    } catch (_: ScimQueryException) {
        error(400, ScimErrorType.INVALID_VALUE, "The SCIM query is invalid.", request)
    } catch (failure: DirectoryReadFailure) {
        directoryError(failure.directoryError, request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        error(400, ScimErrorType.INVALID_VALUE, "The SCIM request contains an invalid value.", request)
    } catch (_: Exception) {
        error(503, null, "The SCIM service is unavailable.", request)
    }

    private suspend fun handleValidated(request: ScimRequest): ScimResponse {
        if (request.bodyBytes().size > config.jsonLimits.maximumBytes) {
            return error(413, ScimErrorType.TOO_MANY, "The SCIM request is too large.", request)
        }
        if (!config.enabled) return notFound(request)
        when (val organization = identityStore.findOrganization(config.organizationId)) {
            is StoreResult.Failure -> return storeError(organization.error, request)
            is StoreResult.Success -> if (organization.value?.state != OrganizationState.ACTIVE) return notFound(request)
        }
        val mutating = request.method != ScimHttpMethod.GET
        val fingerprint = if (mutating) operationFingerprint(request) else null
        if (mutating) {
            val operationId = request.operationId
                ?: return error(400, ScimErrorType.INVALID_VALUE, "A stable operation ID is required.", request)
            when (val existing = directory.findOperation(operationId)) {
                is ScimDirectoryResult.Failure -> return directoryError(existing.error, request)
                is ScimDirectoryResult.Success -> existing.value?.let { reservation ->
                    if (!reservationBelongsToTenant(reservation)) return notFound(request)
                    if (reservation.fingerprint != fingerprint) {
                        return error(409, ScimErrorType.UNIQUENESS, "The operation ID is already in use.", request)
                    }
                    return finishReserved(reservation, request)
                }
            }
        }

        return when (request.path) {
            "/scim/v2/Users" -> usersCollection(request, fingerprint)
            "/scim/v2/Groups" -> groupsCollection(request, fingerprint)
            else -> when {
                request.path.startsWith("/scim/v2/Users/") -> {
                    val id = resourceId(request.path, "/scim/v2/Users/")
                        ?: return error(404, null, "The SCIM resource was not found.", request)
                    userResource(request, id, fingerprint)
                }
                request.path.startsWith("/scim/v2/Groups/") -> {
                    val id = resourceId(request.path, "/scim/v2/Groups/")
                        ?: return error(404, null, "The SCIM resource was not found.", request)
                    groupResource(request, id, fingerprint)
                }
                else -> error(404, null, "The SCIM resource was not found.", request)
            }
        }
    }

    private suspend fun usersCollection(request: ScimRequest, fingerprint: String?): ScimResponse = when (request.method) {
        ScimHttpMethod.GET -> listUsers(request)
        ScimHttpMethod.POST -> if (request.query.isEmpty()) {
            createUser(request, requireNotNull(fingerprint))
        } else {
            error(400, ScimErrorType.INVALID_VALUE, "The SCIM query is invalid.", request)
        }
        else -> methodNotAllowed(request, "GET, POST")
    }

    private suspend fun groupsCollection(request: ScimRequest, fingerprint: String?): ScimResponse = when (request.method) {
        ScimHttpMethod.GET -> listGroups(request)
        ScimHttpMethod.POST -> if (request.query.isEmpty()) {
            createGroup(request, requireNotNull(fingerprint))
        } else {
            error(400, ScimErrorType.INVALID_VALUE, "The SCIM query is invalid.", request)
        }
        else -> methodNotAllowed(request, "GET, POST")
    }

    private suspend fun userResource(request: ScimRequest, id: String, fingerprint: String?): ScimResponse =
        if (request.query.isNotEmpty()) {
            error(400, ScimErrorType.INVALID_VALUE, "The SCIM query is invalid.", request)
        } else when (request.method) {
            ScimHttpMethod.GET -> getUser(request, id)
            ScimHttpMethod.PUT -> replaceUser(request, id, requireNotNull(fingerprint))
            ScimHttpMethod.PATCH -> patchUser(request, id, requireNotNull(fingerprint))
            ScimHttpMethod.DELETE -> deleteUser(request, id, requireNotNull(fingerprint))
            ScimHttpMethod.POST -> methodNotAllowed(request, "GET, PUT, PATCH, DELETE")
        }

    private suspend fun groupResource(request: ScimRequest, id: String, fingerprint: String?): ScimResponse =
        if (request.query.isNotEmpty()) {
            error(400, ScimErrorType.INVALID_VALUE, "The SCIM query is invalid.", request)
        } else when (request.method) {
            ScimHttpMethod.GET -> getGroup(request, id)
            ScimHttpMethod.PUT -> replaceGroup(request, id, requireNotNull(fingerprint))
            ScimHttpMethod.PATCH -> patchGroup(request, id, requireNotNull(fingerprint))
            ScimHttpMethod.DELETE -> deleteGroup(request, id, requireNotNull(fingerprint))
            ScimHttpMethod.POST -> methodNotAllowed(request, "GET, PUT, PATCH, DELETE")
        }

    private suspend fun listUsers(request: ScimRequest): ScimResponse {
        val records = when (val result = directory.listUsers(config.organizationId)) {
            is ScimDirectoryResult.Success -> result.value.filterNot { it.deleted }
            is ScimDirectoryResult.Failure -> return directoryError(result.error, request)
        }
        val filter = request.query["filter"]?.let(ScimFilterParser::parse)
        val filtered = records.filter { filter == null || userMatches(it, filter) }.sortedBy { it.id }
        val page = parsePage(request.query, config.maximumPageSize)
        val resources = page.apply(filtered).map { userResource(it, userGroups(it.id)) }
        return jsonResponse(
            200,
            ScimUserListResponse(
                totalResults = filtered.size,
                startIndex = page.startIndex,
                itemsPerPage = resources.size,
                resources = resources
            ),
            ScimUserListResponse.serializer(),
            request = request
        )
    }

    private suspend fun listGroups(request: ScimRequest): ScimResponse {
        val records = when (val result = directory.listGroups(config.organizationId)) {
            is ScimDirectoryResult.Success -> result.value.filterNot { it.deleted }
            is ScimDirectoryResult.Failure -> return directoryError(result.error, request)
        }
        val filter = request.query["filter"]?.let(ScimFilterParser::parse)
        val filtered = records.filter { filter == null || groupMatches(it, filter) }.sortedBy { it.id }
        val page = parsePage(request.query, config.maximumPageSize)
        val resources = page.apply(filtered).map(::groupResource)
        return jsonResponse(
            200,
            ScimGroupListResponse(
                totalResults = filtered.size,
                startIndex = page.startIndex,
                itemsPerPage = resources.size,
                resources = resources
            ),
            ScimGroupListResponse.serializer(),
            request = request
        )
    }

    private suspend fun getUser(request: ScimRequest, id: String): ScimResponse {
        val record = readUser(id, request) ?: return notFound(request)
        val etag = weakEtag(record.version)
        if (request.header("if-none-match") in setOf("*", etag)) return emptyResponse(304, request)
        return userResponse(200, record, request)
    }

    private suspend fun getGroup(request: ScimRequest, id: String): ScimResponse {
        val record = readGroup(id, request) ?: return notFound(request)
        val etag = weakEtag(record.version)
        if (request.header("if-none-match") in setOf("*", etag)) return emptyResponse(304, request)
        return groupResponse(200, record, request)
    }

    private suspend fun createUser(request: ScimRequest, fingerprint: String): ScimResponse {
        val document = boundedJson.decode(request.bodyBytes(), ScimUserDocument.serializer())
        val now = runtime.clock.now()
        val userId = ids.newUserId()
        val membershipId = ids.newMembershipId()
        val record = ScimUserRecord(
            id = userId.value,
            organizationId = config.organizationId,
            identityUserId = userId,
            membershipId = membershipId,
            externalId = document.externalId,
            userName = document.userName,
            name = document.name,
            displayName = document.displayName,
            nickName = document.nickName,
            profileUrl = document.profileUrl,
            title = document.title,
            userType = document.userType,
            preferredLanguage = document.preferredLanguage,
            locale = document.locale,
            timezone = document.timezone,
            active = document.active,
            emails = document.emails,
            phoneNumbers = document.phoneNumbers,
            ims = document.ims,
            photos = document.photos,
            addresses = document.addresses,
            entitlements = document.entitlements,
            roles = document.roles,
            x509Certificates = document.x509Certificates,
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        val user = User(
            id = userId,
            state = UserState.ACTIVE,
            displayName = profileDisplayName(document),
            primaryEmail = primaryEmail(document.emails),
            avatarUrl = primaryValue(document.photos),
            locale = document.locale ?: document.preferredLanguage,
            timeZone = document.timezone,
            createdAt = now,
            updatedAt = now,
            activatedAt = now
        )
        val membership = Membership(
            id = membershipId,
            organizationId = config.organizationId,
            userId = userId,
            role = OrganizationRole.VIEWER,
            state = if (document.active) MembershipState.ACTIVE else MembershipState.REMOVED,
            createdAt = now,
            updatedAt = now,
            removedAt = if (document.active) null else now
        )
        val commands = listOf(
            mutationCommand(request, user, null, ScimMutationType.UPSERT_USER, record.externalSubject(), now),
            mutationCommand(
                request,
                null,
                membership,
                if (document.active) ScimMutationType.UPSERT_MEMBERSHIP else ScimMutationType.REMOVE_MEMBERSHIP,
                record.externalSubject(),
                now
            )
        )
        val revocations = if (document.active) emptyList() else listOf(
            revocation(userId, "scim_membership_deactivated")
        )
        val reservation = ScimOperationReservation(
            operationId = requireNotNull(request.operationId),
            fingerprint = fingerprint,
            kind = ScimResourceKind.USER,
            resourceId = record.id,
            desiredUser = record,
            identityBatch = batchCommand(request, commands, revocations, userId = userId),
            reservedAt = now
        )
        return reserveAndFinish(reservation, request)
    }

    private suspend fun replaceUser(request: ScimRequest, id: String, fingerprint: String): ScimResponse {
        val existing = readUser(id, request) ?: return notFound(request)
        precondition(existing.version, request)?.let { return it }
        val document = boundedJson.decode(request.bodyBytes(), ScimUserDocument.serializer())
        return updateUser(request, existing, document, fingerprint)
    }

    private suspend fun patchUser(request: ScimRequest, id: String, fingerprint: String): ScimResponse {
        val existing = readUser(id, request) ?: return notFound(request)
        precondition(existing.version, request)?.let { return it }
        val patch = boundedJson.decode(request.bodyBytes(), ScimPatchRequest.serializer())
        val document = ScimPatchApplicator.user(existing, patch)
        return updateUser(request, existing, document, fingerprint)
    }

    private suspend fun updateUser(
        request: ScimRequest,
        existing: ScimUserRecord,
        document: ScimUserDocument,
        fingerprint: String
    ): ScimResponse {
        val now = runtime.clock.now()
        val identityUser = when (val result = identityStore.findUser(existing.identityUserId)) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return storeError(result.error, request)
        } ?: return error(503, null, "The identity store is unavailable.", request)
        val currentMembership = when (
            val result = identityStore.findMembershipForUser(existing.identityUserId, config.organizationId)
        ) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return storeError(result.error, request)
        }
        val desiredRole = if (document.active) roleForUser(existing.id) else {
            currentMembership?.role ?: OrganizationRole.VIEWER
        }
        val desiredMembership = if (currentMembership == null) {
            Membership(
                id = existing.membershipId,
                organizationId = config.organizationId,
                userId = existing.identityUserId,
                role = desiredRole,
                state = if (document.active) MembershipState.ACTIVE else MembershipState.REMOVED,
                createdAt = now,
                updatedAt = now,
                removedAt = if (document.active) null else now
            )
        } else {
            currentMembership.copy(
                role = desiredRole,
                state = if (document.active) MembershipState.ACTIVE else MembershipState.REMOVED,
                version = currentMembership.version + 1,
                updatedAt = now,
                removedAt = if (document.active) null else now
            )
        }
        val membership = desiredMembership.takeIf {
            currentMembership == null || currentMembership.role != it.role || currentMembership.state != it.state
        }
        val desiredDisplayName = profileDisplayName(document)
        val desiredPrimaryEmail = primaryEmail(document.emails)
        val desiredAvatarUrl = primaryValue(document.photos)
        val desiredLocale = document.locale ?: document.preferredLanguage
        val updatedUser = if (
            identityUser.displayName != desiredDisplayName || identityUser.primaryEmail != desiredPrimaryEmail ||
            identityUser.avatarUrl != desiredAvatarUrl || identityUser.locale != desiredLocale ||
            identityUser.timeZone != document.timezone
        ) {
            identityUser.copy(
                displayName = desiredDisplayName,
                primaryEmail = desiredPrimaryEmail,
                avatarUrl = desiredAvatarUrl,
                locale = desiredLocale,
                timeZone = document.timezone,
                version = identityUser.version + 1,
                updatedAt = now
            )
        } else null
        val record = existing.copy(
            externalId = document.externalId,
            userName = document.userName,
            name = document.name,
            displayName = document.displayName,
            nickName = document.nickName,
            profileUrl = document.profileUrl,
            title = document.title,
            userType = document.userType,
            preferredLanguage = document.preferredLanguage,
            locale = document.locale,
            timezone = document.timezone,
            active = document.active,
            emails = document.emails,
            phoneNumbers = document.phoneNumbers,
            ims = document.ims,
            photos = document.photos,
            addresses = document.addresses,
            entitlements = document.entitlements,
            roles = document.roles,
            x509Certificates = document.x509Certificates,
            version = existing.version + 1,
            updatedAt = now
        )
        val commands = buildList {
            updatedUser?.let {
                add(mutationCommand(request, it, null, ScimMutationType.UPSERT_USER, record.externalSubject(), now))
            }
            membership?.let {
                add(mutationCommand(
                    request,
                    null,
                    it,
                    if (document.active) ScimMutationType.UPSERT_MEMBERSHIP else ScimMutationType.REMOVE_MEMBERSHIP,
                    record.externalSubject(),
                    now
                ))
            }
        }
        val privilegeChanged = currentMembership != null && membership != null
        val revocations = if (privilegeChanged) listOf(
            revocation(existing.identityUserId, "scim_membership_changed")
        ) else emptyList()
        val reservation = ScimOperationReservation(
            operationId = requireNotNull(request.operationId),
            fingerprint = fingerprint,
            kind = ScimResourceKind.USER,
            resourceId = existing.id,
            expectedProjectionVersion = existing.version,
            desiredUser = record,
            identityBatch = batchCommand(
                request,
                commands,
                revocations,
                userId = existing.identityUserId
            ),
            reservedAt = now
        )
        return reserveAndFinish(reservation, request)
    }

    private suspend fun deleteUser(request: ScimRequest, id: String, fingerprint: String): ScimResponse {
        val existing = readUser(id, request) ?: return notFound(request)
        precondition(existing.version, request)?.let { return it }
        val membership = when (
            val result = identityStore.findMembershipForUser(existing.identityUserId, config.organizationId)
        ) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return storeError(result.error, request)
        }
        val now = runtime.clock.now()
        val commands = if (membership == null || membership.state == MembershipState.REMOVED) emptyList() else listOf(
            mutationCommand(
                request = request,
                user = null,
                membership = membership.copy(
                    state = MembershipState.REMOVED,
                    version = membership.version + 1,
                    updatedAt = now,
                    removedAt = now
                ),
                type = ScimMutationType.REMOVE_MEMBERSHIP,
                subject = existing.externalSubject(),
                at = now
            )
        )
        val reservation = ScimOperationReservation(
            operationId = requireNotNull(request.operationId),
            fingerprint = fingerprint,
            kind = ScimResourceKind.USER,
            resourceId = existing.id,
            expectedProjectionVersion = existing.version,
            desiredUser = existing.copy(
                active = false,
                version = existing.version + 1,
                updatedAt = now,
                deletedAt = now
            ),
            identityBatch = batchCommand(
                request,
                commands,
                listOf(revocation(existing.identityUserId, "scim_user_deleted")),
                userId = existing.identityUserId
            ),
            reservedAt = now
        )
        return reserveAndFinish(reservation, request)
    }

    private suspend fun createGroup(request: ScimRequest, fingerprint: String): ScimResponse {
        val document = boundedJson.decode(request.bodyBytes(), ScimGroupDocument.serializer())
        validateMembers(document.members, request)?.let { return it }
        val now = runtime.clock.now()
        val record = ScimGroupRecord(
            id = ids.newScimOperationId().value,
            organizationId = config.organizationId,
            externalId = document.externalId,
            displayName = document.displayName,
            memberUserResourceIds = document.members.mapTo(linkedSetOf()) { it.value },
            version = 1,
            createdAt = now,
            updatedAt = now
        )
        return writeGroup(request, old = null, desired = record, fingerprint = fingerprint)
    }

    private suspend fun replaceGroup(request: ScimRequest, id: String, fingerprint: String): ScimResponse {
        val existing = readGroup(id, request) ?: return notFound(request)
        precondition(existing.version, request)?.let { return it }
        val document = boundedJson.decode(request.bodyBytes(), ScimGroupDocument.serializer())
        validateMembers(document.members, request)?.let { return it }
        val desired = existing.copy(
            externalId = document.externalId,
            displayName = document.displayName,
            memberUserResourceIds = document.members.mapTo(linkedSetOf()) { it.value },
            version = existing.version + 1,
            updatedAt = runtime.clock.now()
        )
        return writeGroup(request, existing, desired, fingerprint)
    }

    private suspend fun patchGroup(request: ScimRequest, id: String, fingerprint: String): ScimResponse {
        val existing = readGroup(id, request) ?: return notFound(request)
        precondition(existing.version, request)?.let { return it }
        val patch = boundedJson.decode(request.bodyBytes(), ScimPatchRequest.serializer())
        val document = ScimPatchApplicator.group(existing, patch)
        validateMembers(document.members, request)?.let { return it }
        val desired = existing.copy(
            externalId = document.externalId,
            displayName = document.displayName,
            memberUserResourceIds = document.members.mapTo(linkedSetOf()) { it.value },
            version = existing.version + 1,
            updatedAt = runtime.clock.now()
        )
        return writeGroup(request, existing, desired, fingerprint)
    }

    private suspend fun deleteGroup(request: ScimRequest, id: String, fingerprint: String): ScimResponse {
        val existing = readGroup(id, request) ?: return notFound(request)
        precondition(existing.version, request)?.let { return it }
        val now = runtime.clock.now()
        return writeGroup(
            request,
            old = existing,
            desired = existing.copy(
                memberUserResourceIds = emptySet(),
                version = existing.version + 1,
                updatedAt = now,
                deletedAt = now
            ),
            fingerprint = fingerprint
        )
    }

    private suspend fun writeGroup(
        request: ScimRequest,
        old: ScimGroupRecord?,
        desired: ScimGroupRecord,
        fingerprint: String
    ): ScimResponse {
        val currentGroups = when (val result = directory.listGroups(config.organizationId)) {
            is ScimDirectoryResult.Success -> result.value.filterNot { it.deleted }
            is ScimDirectoryResult.Failure -> return directoryError(result.error, request)
        }
        val prospectiveGroups = currentGroups.filterNot { it.id == desired.id }.toMutableList().apply {
            if (!desired.deleted) add(desired)
        }
        val impacted = ((old?.memberUserResourceIds ?: emptySet()) + desired.memberUserResourceIds).sorted()
        val now = desired.updatedAt
        val commands = mutableListOf<ApplyScimMutationCommand>()
        val revocations = mutableListOf<ScimTenantRevocation>()
        val desiredIdentityMemberIds = linkedSetOf<codes.yousef.aether.auth.UserId>()
        for (resourceId in impacted) {
            val userRecord = when (val result = directory.findUser(config.organizationId, resourceId)) {
                is ScimDirectoryResult.Success -> result.value
                is ScimDirectoryResult.Failure -> return directoryError(result.error, request)
            } ?: continue
            if (resourceId in desired.memberUserResourceIds) {
                desiredIdentityMemberIds += userRecord.identityUserId
            }
            val existingMembership = when (
                val result = identityStore.findMembershipForUser(userRecord.identityUserId, config.organizationId)
            ) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> return storeError(result.error, request)
            }
            val shouldBeActive = userRecord.active && !userRecord.deleted
            val desiredRole = highestRole(prospectiveGroups.mapNotNull { group ->
                if (resourceId in group.memberUserResourceIds) config.roleFor(group) else null
            }) ?: OrganizationRole.VIEWER
            val replacement = when {
                existingMembership == null && shouldBeActive -> Membership(
                    id = userRecord.membershipId,
                    organizationId = config.organizationId,
                    userId = userRecord.identityUserId,
                    role = desiredRole,
                    createdAt = now,
                    updatedAt = now
                )
                existingMembership == null -> null
                !shouldBeActive && existingMembership.state != MembershipState.REMOVED -> existingMembership.copy(
                    state = MembershipState.REMOVED,
                    version = existingMembership.version + 1,
                    updatedAt = now,
                    removedAt = now
                )
                shouldBeActive && (existingMembership.state != MembershipState.ACTIVE || existingMembership.role != desiredRole) ->
                    existingMembership.copy(
                        role = desiredRole,
                        state = MembershipState.ACTIVE,
                        version = existingMembership.version + 1,
                        updatedAt = now,
                        removedAt = null
                    )
                else -> null
            }
            if (replacement != null) {
                val type = if (replacement.state == MembershipState.REMOVED) {
                    ScimMutationType.REMOVE_MEMBERSHIP
                } else {
                    ScimMutationType.UPSERT_MEMBERSHIP
                }
                val command = mutationCommand(
                    request,
                    null,
                    replacement,
                    type,
                    userRecord.externalSubject(),
                    now
                )
                commands += command
                if (existingMembership != null) {
                    revocations += revocation(
                        userRecord.identityUserId,
                        "scim_group_role_changed"
                    )
                }
            }
        }
        val reservation = ScimOperationReservation(
            operationId = requireNotNull(request.operationId),
            fingerprint = fingerprint,
            kind = ScimResourceKind.GROUP,
            resourceId = desired.id,
            expectedProjectionVersion = old?.version,
            desiredGroup = desired,
            identityBatch = batchCommand(
                request = request,
                mutations = commands,
                revocations = revocations,
                group = desired,
                groupMemberUserIds = desiredIdentityMemberIds
            ),
            reservedAt = now
        )
        return reserveAndFinish(reservation, request)
    }

    private suspend fun validateMembers(members: List<ScimMember>, request: ScimRequest): ScimResponse? {
        for (member in members) {
            val record = when (val result = directory.findUser(config.organizationId, member.value)) {
                is ScimDirectoryResult.Success -> result.value
                is ScimDirectoryResult.Failure -> return directoryError(result.error, request)
            }
            if (record == null || record.deleted) {
                return error(400, ScimErrorType.INVALID_VALUE, "A SCIM group member is invalid.", request)
            }
        }
        return null
    }

    private suspend fun reserveAndFinish(
        reservation: ScimOperationReservation,
        request: ScimRequest
    ): ScimResponse {
        val reserved = when (val result = directory.reserveOperation(reservation)) {
            is ScimDirectoryResult.Success -> result.value
            is ScimDirectoryResult.Failure -> return directoryError(result.error, request)
        }
        if (!reservationBelongsToTenant(reserved)) return notFound(request)
        if (reserved.fingerprint != reservation.fingerprint) {
            return error(409, ScimErrorType.UNIQUENESS, "The operation ID is already in use.", request)
        }
        return finishReserved(reserved, request)
    }

    private suspend fun finishReserved(
        reservation: ScimOperationReservation,
        request: ScimRequest
    ): ScimResponse {
        when (val result = identityStore.applyScimBatch(reservation.identityBatch)) {
            is StoreResult.Success -> Unit
            is StoreResult.Failure -> return storeError(result.error, request)
        }
        val commit = when (val result = directory.completeOperation(reservation.operationId)) {
            is ScimDirectoryResult.Success -> result.value
            is ScimDirectoryResult.Failure -> return directoryError(result.error, request)
        }
        val projectionMatches = when (reservation.kind) {
            ScimResourceKind.USER -> commit.user == reservation.desiredUser && commit.group == null
            ScimResourceKind.GROUP -> commit.group == reservation.desiredGroup && commit.user == null
        }
        if (!projectionMatches) return error(503, null, "The SCIM service is unavailable.", request)
        return when {
            commit.user?.deleted == true || commit.group?.deleted == true -> emptyResponse(204, request)
            commit.user != null -> userResponse(
                if (reservation.expectedProjectionVersion == null) 201 else 200,
                commit.user,
                request
            )
            commit.group != null -> groupResponse(
                if (reservation.expectedProjectionVersion == null) 201 else 200,
                commit.group,
                request
            )
            else -> error(503, null, "The SCIM service is unavailable.", request)
        }
    }

    private suspend fun userResponse(status: Int, record: ScimUserRecord, request: ScimRequest): ScimResponse {
        val resource = userResource(record, userGroups(record.id))
        return jsonResponse(
            status,
            resource,
            ScimUserResource.serializer(),
            headers = mapOf("ETag" to weakEtag(record.version), "Location" to resource.meta.location),
            request = request
        )
    }

    private fun groupResponse(status: Int, record: ScimGroupRecord, request: ScimRequest): ScimResponse {
        val resource = groupResource(record)
        return jsonResponse(
            status,
            resource,
            ScimGroupResource.serializer(),
            headers = mapOf("ETag" to weakEtag(record.version), "Location" to resource.meta.location),
            request = request
        )
    }

    private fun userResource(record: ScimUserRecord, groups: List<ScimMember>): ScimUserResource = ScimUserResource(
        id = record.id,
        externalId = record.externalId,
        userName = record.userName,
        name = record.name,
        displayName = record.displayName,
        nickName = record.nickName,
        profileUrl = record.profileUrl,
        title = record.title,
        userType = record.userType,
        preferredLanguage = record.preferredLanguage,
        locale = record.locale,
        timezone = record.timezone,
        active = record.active,
        emails = record.emails,
        phoneNumbers = record.phoneNumbers,
        ims = record.ims,
        photos = record.photos,
        addresses = record.addresses,
        entitlements = record.entitlements,
        roles = record.roles,
        x509Certificates = record.x509Certificates,
        groups = groups,
        meta = ScimMeta(
            resourceType = "User",
            created = record.createdAt,
            lastModified = record.updatedAt,
            location = "${config.scimBaseUrl}/Users/${record.id}",
            version = weakEtag(record.version)
        )
    )

    private fun groupResource(record: ScimGroupRecord): ScimGroupResource = ScimGroupResource(
        id = record.id,
        externalId = record.externalId,
        displayName = record.displayName,
        members = record.memberUserResourceIds.sorted().map { id ->
            ScimMember(value = id, reference = "${config.scimBaseUrl}/Users/$id", type = "User")
        },
        meta = ScimMeta(
            resourceType = "Group",
            created = record.createdAt,
            lastModified = record.updatedAt,
            location = "${config.scimBaseUrl}/Groups/${record.id}",
            version = weakEtag(record.version)
        )
    )

    private suspend fun userGroups(userResourceId: String): List<ScimMember> {
        val groups = when (val result = directory.listGroups(config.organizationId)) {
            is ScimDirectoryResult.Success -> result.value
            is ScimDirectoryResult.Failure -> throw DirectoryReadFailure(result.error)
        }
        return groups.filter { !it.deleted && userResourceId in it.memberUserResourceIds }
            .sortedBy { it.id }
            .map { group ->
                ScimMember(
                    value = group.id,
                    display = group.displayName,
                    reference = "${config.scimBaseUrl}/Groups/${group.id}",
                    type = "direct"
                )
            }
    }

    private suspend fun roleForUser(userResourceId: String): OrganizationRole {
        val groups = when (val result = directory.listGroups(config.organizationId)) {
            is ScimDirectoryResult.Success -> result.value
            is ScimDirectoryResult.Failure -> throw DirectoryReadFailure(result.error)
        }
        return highestRole(groups.filter { !it.deleted && userResourceId in it.memberUserResourceIds }
            .mapNotNull(config::roleFor)) ?: OrganizationRole.VIEWER
    }

    private fun highestRole(roles: List<OrganizationRole>): OrganizationRole? = when {
        OrganizationRole.ADMIN in roles -> OrganizationRole.ADMIN
        OrganizationRole.PUBLISHER in roles -> OrganizationRole.PUBLISHER
        OrganizationRole.VIEWER in roles -> OrganizationRole.VIEWER
        else -> null
    }

    private suspend fun readUser(id: String, request: ScimRequest): ScimUserRecord? =
        when (val result = directory.findUser(config.organizationId, id)) {
            is ScimDirectoryResult.Success -> result.value?.takeUnless { it.deleted }
            is ScimDirectoryResult.Failure -> throw DirectoryReadFailure(result.error)
        }

    private suspend fun readGroup(id: String, request: ScimRequest): ScimGroupRecord? =
        when (val result = directory.findGroup(config.organizationId, id)) {
            is ScimDirectoryResult.Success -> result.value?.takeUnless { it.deleted }
            is ScimDirectoryResult.Failure -> throw DirectoryReadFailure(result.error)
        }

    private fun precondition(version: Long, request: ScimRequest): ScimResponse? {
        val supplied = request.header("if-match")
        if (supplied == null && config.requireVersionPreconditions) {
            return error(412, null, "A current resource version is required.", request)
        }
        if (supplied != null && supplied != "*" && supplied != weakEtag(version)) {
            return error(412, null, "The resource version does not match.", request)
        }
        return null
    }

    private fun mutationCommand(
        request: ScimRequest,
        user: User?,
        membership: Membership?,
        type: ScimMutationType,
        subject: ExternalSubject,
        at: Instant
    ): ApplyScimMutationCommand {
        val target = if (membership != null) {
            AuditTarget(AuditTargetType.MEMBERSHIP, membership.id.value)
        } else {
            AuditTarget(AuditTargetType.USER, requireNotNull(user).id.value)
        }
        val audit = AuditEvent(
            id = ids.newAuditEventId(),
            actor = AuditActor(AuditActorType.SYSTEM),
            organizationId = config.organizationId,
            action = AuditAction.SCIM_MUTATION_APPLIED,
            target = target,
            outcome = AuditOutcome.SUCCEEDED,
            reasonCode = type.name.lowercase(),
            request = request.requestId?.let { id ->
                AuditRequestMetadata(
                    requestId = id,
                    method = request.method.name,
                    path = request.path
                )
            },
            occurredAt = at
        )
        return ApplyScimMutationCommand(
            mutation = ScimMutation(
                operationId = ids.newScimOperationId(),
                provider = config.tenantProviderKey,
                type = type,
                externalSubject = subject,
                user = user,
                membership = membership,
                occurredAt = at
            ),
            auditEvent = audit
        )
    }

    private fun batchCommand(
        request: ScimRequest,
        mutations: List<ApplyScimMutationCommand>,
        revocations: List<ScimTenantRevocation>,
        userId: codes.yousef.aether.auth.UserId? = null,
        group: ScimGroupRecord? = null,
        groupMemberUserIds: Set<codes.yousef.aether.auth.UserId> = emptySet()
    ): ApplyScimBatchCommand {
        require((userId == null) != (group == null)) { "SCIM batch must target one User or Group" }
        val groupAggregate = group?.let { record ->
            ScimGroup(
                id = record.id,
                organizationId = config.organizationId,
                provider = config.tenantProviderKey,
                externalId = record.externalId,
                displayName = record.displayName,
                memberUserIds = groupMemberUserIds.sortedBy { it.value }.toCollection(linkedSetOf()),
                state = if (record.deleted) ScimGroupState.DELETED else ScimGroupState.ACTIVE,
                version = record.version,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
                deletedAt = record.deletedAt
            )
        }
        val target = if (groupAggregate != null) {
            AuditTarget(AuditTargetType.SCIM_GROUP, groupAggregate.id)
        } else {
            AuditTarget(AuditTargetType.USER, requireNotNull(userId).value)
        }
        val audit = AuditEvent(
            id = ids.newAuditEventId(),
            actor = AuditActor(AuditActorType.SYSTEM),
            organizationId = config.organizationId,
            action = if (groupAggregate != null) AuditAction.SCIM_GROUP_CHANGED else AuditAction.SCIM_MUTATION_APPLIED,
            target = target,
            outcome = AuditOutcome.SUCCEEDED,
            reasonCode = if (groupAggregate != null) {
                if (groupAggregate.state == ScimGroupState.DELETED) "scim_group_deleted" else "scim_group_changed"
            } else {
                "scim_user_changed"
            },
            request = request.requestId?.let { id ->
                AuditRequestMetadata(
                    requestId = id,
                    method = request.method.name,
                    path = request.path
                )
            },
            occurredAt = groupAggregate?.updatedAt ?: mutations.firstOrNull()?.mutation?.occurredAt ?: runtime.clock.now()
        )
        return ApplyScimBatchCommand(
            operationId = requireNotNull(request.operationId),
            organizationId = config.organizationId,
            provider = config.tenantProviderKey,
            mutations = mutations,
            group = groupAggregate,
            expectedGroupVersion = groupAggregate?.version?.minus(1L),
            revocations = revocations,
            auditEvent = audit
        )
    }

    private fun revocation(userId: codes.yousef.aether.auth.UserId, reason: String) =
        ScimTenantRevocation(
            userId = userId,
            reasonCode = reason
        )

    private fun ScimUserRecord.externalSubject(): ExternalSubject = ExternalSubject(externalId ?: id)

    private fun profileDisplayName(document: ScimUserDocument): String =
        document.displayName ?: document.name?.formatted ?: document.userName

    private fun primaryEmail(emails: List<ScimEmail>): EmailAddress? =
        (emails.firstOrNull { it.primary } ?: emails.firstOrNull())?.value?.let(::EmailAddress)

    private fun primaryValue(values: List<ScimMultiValue>): String? =
        (values.firstOrNull { it.primary } ?: values.firstOrNull())?.value

    private fun userMatches(record: ScimUserRecord, filter: ScimEqualityFilter): Boolean = when (filter.attributePath) {
        "id" -> record.id == filter.value
        "externalid" -> record.externalId == filter.value
        "username" -> record.userName.equals(filter.value, ignoreCase = true)
        "displayname" -> record.displayName?.equals(filter.value, ignoreCase = true) == true
        "nickname" -> record.nickName?.equals(filter.value, ignoreCase = true) == true
        "title" -> record.title?.equals(filter.value, ignoreCase = true) == true
        "usertype" -> record.userType?.equals(filter.value, ignoreCase = true) == true
        "active" -> record.active == filter.value.toBooleanStrictOrNull()
        "emails.value" -> record.emails.any { it.value.equals(filter.value, ignoreCase = true) }
        "phonenumbers.value" -> record.phoneNumbers.any { it.value == filter.value }
        "ims.value" -> record.ims.any { it.value.equals(filter.value, ignoreCase = true) }
        "entitlements.value" -> record.entitlements.any { it.value == filter.value }
        "roles.value" -> record.roles.any { it.value == filter.value }
        else -> throw ScimFilterException()
    }

    private fun groupMatches(record: ScimGroupRecord, filter: ScimEqualityFilter): Boolean = when (filter.attributePath) {
        "id" -> record.id == filter.value
        "externalid" -> record.externalId == filter.value
        "displayname" -> record.displayName.equals(filter.value, ignoreCase = true)
        "members.value" -> filter.value in record.memberUserResourceIds
        else -> throw ScimFilterException()
    }

    private suspend fun operationFingerprint(request: ScimRequest): String {
        val prefix = buildString {
            append(request.method.name)
            append('\n')
            append(request.path)
            append('\n')
            append(request.header("if-match") ?: "")
            append('\n')
            request.query.toList().sortedBy { it.first }.forEach { (name, value) ->
                append(name)
                append('=')
                append(value)
                append('\n')
            }
        }.encodeToByteArray()
        return Base64Url.encode(runtime.crypto.sha256(prefix + request.bodyBytes()))
    }

    private fun resourceId(path: String, prefix: String): String? {
        val id = path.removePrefix(prefix)
        return id.takeIf { path.startsWith(prefix) && Regex("[A-Za-z0-9_-][A-Za-z0-9._:-]{0,254}").matches(it) }
    }

    private fun reservationBelongsToTenant(reservation: ScimOperationReservation): Boolean {
        val desiredOrganizationId = reservation.desiredUser?.organizationId ?: reservation.desiredGroup?.organizationId
        if (desiredOrganizationId != config.organizationId) return false
        val batch = reservation.identityBatch
        return batch.operationId == reservation.operationId &&
            batch.organizationId == config.organizationId &&
            batch.provider == config.tenantProviderKey
    }

    private fun <T> jsonResponse(
        status: Int,
        value: T,
        serializer: SerializationStrategy<T>,
        headers: Map<String, String> = emptyMap(),
        request: ScimRequest
    ): ScimResponse = ScimResponse(
        status = status,
        headers = responseHeaders(request) + mapOf("Content-Type" to "application/scim+json") + headers,
        body = json.encodeToString(serializer, value).encodeToByteArray()
    )

    private fun error(
        status: Int,
        type: ScimErrorType?,
        detail: String,
        request: ScimRequest
    ): ScimResponse = jsonResponse(
        status,
        ScimErrorResponse(status = status.toString(), scimType = type?.wireName, detail = detail),
        ScimErrorResponse.serializer(),
        request = request
    )

    private fun notFound(request: ScimRequest): ScimResponse =
        error(404, null, "The SCIM resource was not found.", request)

    private fun methodNotAllowed(request: ScimRequest, allow: String): ScimResponse = ScimResponse(
        status = 405,
        headers = responseHeaders(request) + mapOf("Allow" to allow, "Content-Type" to "application/scim+json"),
        body = json.encodeToString(
            ScimErrorResponse.serializer(),
            ScimErrorResponse(status = "405", detail = "The SCIM method is not allowed.")
        ).encodeToByteArray()
    )

    private fun emptyResponse(status: Int, request: ScimRequest): ScimResponse =
        ScimResponse(status, responseHeaders(request))

    private fun responseHeaders(request: ScimRequest): Map<String, String> =
        request.requestId?.let { mapOf("X-Request-Id" to it) } ?: emptyMap()

    private fun storeError(error: IdentityStoreError, request: ScimRequest): ScimResponse = when (error.code) {
        IdentityStoreErrorCode.NOT_FOUND -> notFound(request)
        IdentityStoreErrorCode.ALREADY_EXISTS,
        IdentityStoreErrorCode.UNIQUE_CONSTRAINT -> error(409, ScimErrorType.UNIQUENESS, "The SCIM resource is not unique.", request)
        IdentityStoreErrorCode.VERSION_CONFLICT -> error(
            412,
            null,
            "The resource version does not match.",
            request
        )
        IdentityStoreErrorCode.LAST_OWNER,
        IdentityStoreErrorCode.INVALID_TRANSITION,
        IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT -> error(
            409,
            ScimErrorType.INVALID_VALUE,
            "The SCIM change conflicts with current state.",
            request
        )
        IdentityStoreErrorCode.UNAVAILABLE,
        IdentityStoreErrorCode.INTERNAL -> error(503, null, "The SCIM service is unavailable.", request)
        else -> error(409, ScimErrorType.INVALID_VALUE, "The SCIM change conflicts with current state.", request)
    }

    private fun directoryError(error: ScimDirectoryError, request: ScimRequest): ScimResponse = when (error.code) {
        ScimDirectoryErrorCode.NOT_FOUND -> notFound(request)
        ScimDirectoryErrorCode.ALREADY_EXISTS,
        ScimDirectoryErrorCode.UNIQUENESS_CONFLICT -> error(
            409,
            ScimErrorType.UNIQUENESS,
            "The SCIM resource is not unique.",
            request
        )
        ScimDirectoryErrorCode.VERSION_CONFLICT -> error(
            412,
            null,
            "The resource version does not match.",
            request
        )
        ScimDirectoryErrorCode.IDEMPOTENCY_CONFLICT -> error(
            409,
            ScimErrorType.UNIQUENESS,
            "The operation ID is already in use.",
            request
        )
        ScimDirectoryErrorCode.UNAVAILABLE,
        ScimDirectoryErrorCode.INTERNAL -> error(503, null, "The SCIM service is unavailable.", request)
    }

    /** Converted to a safe response at the outer boundary without serializing the failure. */
    private class DirectoryReadFailure(
        val directoryError: ScimDirectoryError
    ) : IllegalArgumentException("SCIM directory read failed")
}
