package codes.yousef.aether.auth.scim

import codes.yousef.aether.auth.MembershipState
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.OrganizationRole
import codes.yousef.aether.auth.ScimOperationId
import codes.yousef.aether.auth.UserState
import codes.yousef.aether.auth.testkit.DeterministicIdentityRuntime
import codes.yousef.aether.auth.testkit.IdentityFixtures
import codes.yousef.aether.auth.testkit.InMemoryIdentityStore
import codes.yousef.aether.auth.testkit.InMemoryIdentityStoreSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ScimEngineTest {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = false }

    @Test
    fun userCrudIsIdempotentAndDeprovisionsOnlyTenantMembership() = runTest {
        val fixture = Fixture()
        val createBody = json.encodeToString(
            ScimUserDocument(
                schemas = listOf(ScimSchemas.USER),
                externalId = "directory-user-1",
                userName = "alice@example.test",
                displayName = "Alice",
                emails = listOf(ScimEmail("alice@example.test", primary = true))
            )
        ).encodeToByteArray()
        val create = fixture.engine.handle(
            request(ScimHttpMethod.POST, "/scim/v2/Users", "create-user-1", createBody)
        )
        assertEquals(201, create.status)
        assertTrue(create.bodyBytes().decodeToString().contains("\"schemas\":[\"${ScimSchemas.USER}\"]"))
        val resource = json.decodeFromString(ScimUserResource.serializer(), create.bodyBytes().decodeToString())

        val retried = fixture.engine.handle(
            request(ScimHttpMethod.POST, "/scim/v2/Users", "create-user-1", createBody)
        )
        assertEquals(201, retried.status)
        val retriedResource = json.decodeFromString(ScimUserResource.serializer(), retried.bodyBytes().decodeToString())
        assertEquals(resource.id, retriedResource.id)
        assertEquals(2, fixture.identity.snapshot().appliedScimOperationIds.size)
        assertEquals(1, fixture.identity.snapshot().appliedScimBatchOperationIds.size)

        val patchBody = """{
          "schemas":["${ScimSchemas.PATCH_OPERATION}"],
          "Operations":[{"op":"replace","path":"active","value":false}]
        }""".trimIndent().encodeToByteArray()
        val patched = fixture.engine.handle(
            request(
                ScimHttpMethod.PATCH,
                "/scim/v2/Users/${resource.id}",
                "deactivate-user-1",
                patchBody,
                mapOf("If-Match" to "W/\"1\"")
            )
        )
        assertEquals(200, patched.status)
        val snapshot = fixture.identity.snapshot()
        assertEquals(UserState.ACTIVE, snapshot.users.single().state)
        assertEquals(MembershipState.REMOVED, snapshot.memberships.single().state)
        assertEquals(2, snapshot.appliedScimBatchOperationIds.size)
    }

    @Test
    fun listSupportsEqualityFilteringAndOneBasedPagination() = runTest {
        val fixture = Fixture()
        fixture.createUser("alice@example.test", "ext-alice", "op-alice")
        fixture.createUser("bob@example.test", "ext-bob", "op-bob")
        val response = fixture.engine.handle(
            ScimRequest(
                method = ScimHttpMethod.GET,
                path = "/scim/v2/Users",
                query = mapOf("filter" to "userName eq \"BOB@example.test\"", "startIndex" to "1", "count" to "1")
            )
        )
        assertEquals(200, response.status)
        val list = json.decodeFromString(ScimUserListResponse.serializer(), response.bodyBytes().decodeToString())
        assertEquals(1, list.totalResults)
        assertEquals("bob@example.test", list.resources.single().userName)
    }

    @Test
    fun staleEtagFailsWithoutApplyingAMutation() = runTest {
        val fixture = Fixture()
        val user = fixture.createUser("alice@example.test", "ext-alice", "op-alice")
        val body = json.encodeToString(
            ScimUserDocument(
                schemas = listOf(ScimSchemas.USER),
                externalId = "ext-alice",
                userName = "alice@example.test",
                displayName = "Changed"
            )
        ).encodeToByteArray()
        val response = fixture.engine.handle(
            request(
                ScimHttpMethod.PUT,
                "/scim/v2/Users/${user.id}",
                "replace-alice",
                body,
                mapOf("If-Match" to "W/\"99\"")
            )
        )
        assertEquals(412, response.status)
        assertEquals(2, fixture.identity.snapshot().appliedScimOperationIds.size)
    }

    @Test
    fun coreUserAttributesRoundTripButInformationalRolesGrantNothing() = runTest {
        val fixture = Fixture()
        val body = json.encodeToString(
            ScimUserDocument(
                schemas = listOf(ScimSchemas.USER),
                externalId = "full-user",
                userName = "full@example.test",
                name = ScimName(givenName = "Full", familyName = "User"),
                nickName = "fu",
                profileUrl = "https://example.test/profiles/full",
                title = "Engineer",
                userType = "Employee",
                preferredLanguage = "en",
                locale = "en-US",
                timezone = "Asia/Riyadh",
                emails = listOf(ScimEmail("full@example.test", type = "work", primary = true)),
                phoneNumbers = listOf(ScimMultiValue("+966500000000", type = "work")),
                ims = listOf(ScimMultiValue("full-user", type = "matrix")),
                photos = listOf(ScimMultiValue("https://example.test/full.png", primary = true)),
                addresses = listOf(ScimAddress(locality = "Riyadh", country = "SA", type = "work")),
                entitlements = listOf(ScimMultiValue("billing-admin")),
                roles = listOf(ScimMultiValue("owner")),
                x509Certificates = listOf(ScimMultiValue("MIIBfixture"))
            )
        ).encodeToByteArray()
        val response = fixture.engine.handle(request(ScimHttpMethod.POST, "/scim/v2/Users", "full-user-op", body))
        assertEquals(201, response.status)
        val user = json.decodeFromString(ScimUserResource.serializer(), response.bodyBytes().decodeToString())
        assertEquals("Engineer", user.title)
        assertEquals("Riyadh", user.addresses.single().locality)
        assertEquals("owner", user.roles.single().value)
        assertEquals(OrganizationRole.VIEWER, fixture.identity.snapshot().memberships.single().role)
        val identityUser = fixture.identity.snapshot().users.single()
        assertEquals("en-US", identityUser.locale)
        assertEquals("Asia/Riyadh", identityUser.timeZone)
        assertEquals("https://example.test/full.png", identityUser.avatarUrl)
    }

    @Test
    fun passwordProvisioningIsRejectedAndNeverPersistsIdentityState() = runTest {
        val fixture = Fixture()
        val body = """{
          "schemas":["${ScimSchemas.USER}"],
          "userName":"password@example.test",
          "password":"must-not-be-stored"
        }""".trimIndent().encodeToByteArray()
        val response = fixture.engine.handle(request(ScimHttpMethod.POST, "/scim/v2/Users", "password-op", body))
        assertEquals(400, response.status)
        assertTrue(fixture.identity.snapshot().users.isEmpty())
        assertTrue(response.bodyBytes().decodeToString().contains("must-not-be-stored").not())
    }

    @Test
    fun uniquenessIsReservedBeforeAnySecondIdentityMutation() = runTest {
        val fixture = Fixture()
        fixture.createUser("duplicate@example.test", "external-one", "first-create")
        val duplicateBody = json.encodeToString(
            ScimUserDocument(
                schemas = listOf(ScimSchemas.USER),
                externalId = "external-two",
                userName = "DUPLICATE@example.test"
            )
        ).encodeToByteArray()
        val duplicate = fixture.engine.handle(
            request(ScimHttpMethod.POST, "/scim/v2/Users", "second-create", duplicateBody)
        )
        assertEquals(409, duplicate.status)
        assertEquals(1, fixture.identity.snapshot().users.size)
        assertEquals(2, fixture.identity.snapshot().appliedScimOperationIds.size)
    }

    @Test
    fun reusingOperationIdWithDifferentBodyFailsClosed() = runTest {
        val fixture = Fixture()
        fixture.createUser("alice@example.test", "ext-alice", "same-operation")
        val changed = json.encodeToString(
            ScimUserDocument(
                schemas = listOf(ScimSchemas.USER),
                externalId = "ext-bob",
                userName = "bob@example.test"
            )
        ).encodeToByteArray()
        val response = fixture.engine.handle(
            request(ScimHttpMethod.POST, "/scim/v2/Users", "same-operation", changed)
        )
        assertEquals(409, response.status)
        assertEquals(1, fixture.identity.snapshot().users.size)
    }

    @Test
    fun mappedGroupChangesRoleAndUnknownGroupGrantsNothing() = runTest {
        val fixture = Fixture(groupMappings = mapOf("directory-admins" to OrganizationRole.ADMIN))
        val alice = fixture.createUser("alice@example.test", "ext-alice", "op-alice")
        val bob = fixture.createUser("bob@example.test", "ext-bob", "op-bob")

        val mapped = fixture.createGroup("Admins", "directory-admins", alice.id, "group-admins")
        val afterMapped = fixture.identity.snapshot().memberships.single { it.userId.value == alice.id }
        assertEquals(OrganizationRole.ADMIN, afterMapped.role)

        fixture.createGroup("Mystery", "unknown-group", bob.id, "group-mystery")
        val afterUnknown = fixture.identity.snapshot().memberships.single { it.userId.value == bob.id }
        assertEquals(OrganizationRole.VIEWER, afterUnknown.role)

        val patch = """{
          "schemas":["${ScimSchemas.PATCH_OPERATION}"],
          "Operations":[{"op":"remove","path":"members[value eq \"${alice.id}\"]"}]
        }""".trimIndent().encodeToByteArray()
        val removed = fixture.engine.handle(
            request(
                ScimHttpMethod.PATCH,
                "/scim/v2/Groups/${mapped.id}",
                "group-admins-remove",
                patch,
                mapOf("If-Match" to "W/\"1\"")
            )
        )
        assertEquals(200, removed.status)
        val afterRemoval = fixture.identity.snapshot().memberships.single { it.userId.value == alice.id }
        assertEquals(OrganizationRole.VIEWER, afterRemoval.role)
        assertEquals(2L, fixture.identity.snapshot().scimGroups.single { it.id == mapped.id }.version)
    }

    @Test
    fun ownerGroupMappingIsRejectedByConfiguration() {
        assertFailsWith<IllegalArgumentException> {
            ScimConfig(
                organizationId = IdentityFixtures.organizationId("owner-mapping-config"),
                providerName = "test-directory",
                scimBaseUrl = "https://identity.example.test/scim/v2",
                groupRoleMappings = mapOf("directory-owners" to OrganizationRole.OWNER)
            )
        }
    }

    @Test
    fun engineNeverPromotesScimMembershipToOwnerFromMutatedMapping() = runTest {
        val mappings = mutableMapOf("directory-admins" to OrganizationRole.ADMIN)
        val fixture = Fixture(groupMappings = mappings)
        mappings["directory-owners"] = OrganizationRole.OWNER
        val alice = fixture.createUser("alice@example.test", "ext-alice", "owner-map-user")

        fixture.createGroup("Owners", "directory-owners", alice.id, "owner-map-group")

        val membership = fixture.identity.snapshot().memberships.single { it.userId.value == alice.id }
        assertEquals(OrganizationRole.VIEWER, membership.role)
    }

    @Test
    fun deleteReturnsTombstoneBehaviorAndNeverDeletesStableUser() = runTest {
        val fixture = Fixture()
        val user = fixture.createUser("alice@example.test", "ext-alice", "op-alice")
        val deleted = fixture.engine.handle(
            request(
                ScimHttpMethod.DELETE,
                "/scim/v2/Users/${user.id}",
                "delete-alice",
                headers = mapOf("If-Match" to "W/\"1\"")
            )
        )
        assertEquals(204, deleted.status)
        assertEquals(404, fixture.engine.handle(ScimRequest(ScimHttpMethod.GET, "/scim/v2/Users/${user.id}")).status)
        assertNotNull(fixture.identity.snapshot().users.singleOrNull { it.id.value == user.id })
        assertEquals(UserState.ACTIVE, fixture.identity.snapshot().users.single().state)
    }

    private fun request(
        method: ScimHttpMethod,
        path: String,
        operationId: String,
        body: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap()
    ): ScimRequest = ScimRequest(
        method = method,
        path = path,
        headers = headers,
        body = body,
        operationId = IdentityFixtures.scimOperationId(operationId),
        requestId = "request-$operationId"
    )

    private inner class Fixture(
        groupMappings: Map<String, OrganizationRole> = emptyMap()
    ) {
        val runtime = DeterministicIdentityRuntime()
        val organizationId = IdentityFixtures.organizationId("organization-1")
        val identity = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(organizations = listOf(IdentityFixtures.organization(organizationId)))
        )
        val directory = InMemoryScimDirectory()
        val engine = ScimEngine(
            identityStore = identity,
            directory = directory,
            runtime = runtime.runtime,
            config = ScimConfig(
                organizationId = organizationId,
                providerName = "test-directory",
                scimBaseUrl = "https://identity.example.test/scim/v2",
                groupRoleMappings = groupMappings
            )
        )

        suspend fun createUser(userName: String, externalId: String, operationId: String): ScimUserResource {
            val body = json.encodeToString(
                ScimUserDocument(
                    schemas = listOf(ScimSchemas.USER),
                    externalId = externalId,
                    userName = userName,
                    displayName = userName.substringBefore('@')
                )
            ).encodeToByteArray()
            val response = engine.handle(request(ScimHttpMethod.POST, "/scim/v2/Users", operationId, body))
            assertEquals(201, response.status)
            return json.decodeFromString(ScimUserResource.serializer(), response.bodyBytes().decodeToString())
        }

        suspend fun createGroup(
            displayName: String,
            externalId: String,
            memberId: String,
            operationId: String
        ): ScimGroupResource {
            val body = json.encodeToString(
                ScimGroupDocument(
                    schemas = listOf(ScimSchemas.GROUP),
                    externalId = externalId,
                    displayName = displayName,
                    members = listOf(ScimMember(memberId))
                )
            ).encodeToByteArray()
            val response = engine.handle(request(ScimHttpMethod.POST, "/scim/v2/Groups", operationId, body))
            assertEquals(201, response.status)
            return json.decodeFromString(ScimGroupResource.serializer(), response.bodyBytes().decodeToString())
        }
    }
}
