package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdentityOrganizationServiceTest {
    @Test
    fun `organization creation atomically establishes one owner`() = runTest {
        val fixture = OrganizationFixture(withOrganization = false)

        val organization = fixture.service.createOrganization(
            fixture.actorWithoutOrganization,
            "Aether Team",
            "aether-team"
        ).expectOrganizationSuccess()

        val snapshot = fixture.store.snapshot()
        assertEquals(organization, snapshot.organizations.single())
        val owner = snapshot.memberships.single()
        assertEquals(organization.id, owner.organizationId)
        assertEquals(fixture.owner.id, owner.userId)
        assertEquals(OrganizationRole.OWNER, owner.role)
        assertEquals(AuditAction.ORGANIZATION_CREATED, snapshot.auditEvents.single().action)
    }

    @Test
    fun `federated organization listings expose only the session tenant`() = runTest {
        val user = IdentityFixtures.user(IdentityFixtures.userId("federated-list-user"))
        val boundOrganization = IdentityFixtures.organization(
            id = IdentityFixtures.organizationId("federated-list-bound"),
            slug = "federated-list-bound"
        )
        val otherOrganization = IdentityFixtures.organization(
            id = IdentityFixtures.organizationId("federated-list-other"),
            slug = "federated-list-other"
        )
        val boundMembership = IdentityFixtures.membership(
            id = IdentityFixtures.membershipId("federated-list-bound"),
            organizationId = boundOrganization.id,
            userId = user.id,
            role = OrganizationRole.VIEWER
        )
        val otherMembership = IdentityFixtures.membership(
            id = IdentityFixtures.membershipId("federated-list-other"),
            organizationId = otherOrganization.id,
            userId = user.id,
            role = OrganizationRole.OWNER
        )
        val session = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("federated-list-session"),
            userId = user.id,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = boundOrganization.id,
            federationProviderKey = IdentityFixtures.federationProviderStorageKey(
                FederationProviderKind.OIDC,
                "federated-list-bound"
            ),
            externalIdentityId = IdentityFixtures.externalIdentityId("federated-list-external")
        )
        val actor = IdentityContext(
            principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = user.id,
                displayName = user.displayName,
                assurance = session.assurance,
                authenticatedAt = session.authenticatedAt,
                sessionId = session.id
            ),
            session = session
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                sessions = listOf(session),
                organizations = listOf(boundOrganization, otherOrganization),
                memberships = listOf(boundMembership, otherMembership)
            )
        )
        val service = IdentityOrganizationService(
            store,
            DeterministicIdentityRuntime().runtime,
            organizationConfig()
        )

        assertEquals(
            listOf(boundOrganization),
            service.listOrganizations(actor).expectOrganizationSuccess()
        )
        assertEquals(
            listOf(boundOrganization.id),
            service.listOrganizationAccess(actor).expectOrganizationSuccess().map { it.id }
        )
        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            service.getOrganizationAccess(actor, otherOrganization.id).expectOrganizationFailure()
        )
    }

    @Test
    fun `invitation token is shown once and acceptance atomically creates membership`() = runTest {
        val fixture = OrganizationFixture()
        val issued = fixture.service.invite(
            fixture.ownerContext,
            fixture.organization.id,
            requireNotNull(fixture.invitee.primaryEmail),
            OrganizationRole.PUBLISHER
        ).expectOrganizationSuccess()
        val token = issued.revealToken()

        assertEquals(32, Base64Url.decode(token).size)
        var snapshot = fixture.store.snapshot()
        assertFalse(snapshot.toString().contains(token))
        assertEquals(DigestAlgorithm.HMAC_SHA256, snapshot.invitations.single().tokenDigest.algorithm)

        val membership = fixture.service.acceptInvitation(fixture.inviteeContext, token)
            .expectOrganizationSuccess()
        assertEquals(OrganizationRole.PUBLISHER, membership.role)
        assertEquals(fixture.organization.id, membership.organizationId)
        assertEquals(fixture.invitee.id, membership.userId)

        snapshot = fixture.store.snapshot()
        assertEquals(InvitationState.ACCEPTED, snapshot.invitations.single().state)
        assertEquals(2, snapshot.memberships.size)
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.INVITATION_ACCEPTED })
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            fixture.service.acceptInvitation(fixture.inviteeContext, token).expectOrganizationFailure()
        )
    }

    @Test
    fun `invitation enrolls one new user into a restricted single-use session under race`() = runTest {
        val fixture = OrganizationFixture()
        val issued = fixture.service.invite(
            fixture.ownerContext,
            fixture.organization.id,
            EmailAddress("brand-new-invitee@example.test"),
            OrganizationRole.PUBLISHER
        ).expectOrganizationSuccess()
        val token = issued.revealToken()

        val raced = listOf(
            async { fixture.service.enrollInvitation(token, "New Invitee") },
            async { fixture.service.enrollInvitation(token, "New Invitee") }
        ).awaitAll()
        val enrolled = raced.single { it is IdentityOperationResult.Success }
            .expectOrganizationSuccess()
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            raced.single { it is IdentityOperationResult.Failure }.expectOrganizationFailure()
        )
        assertEquals(issued.invitation.id, enrolled.invitation.id)
        assertEquals(OrganizationRole.PUBLISHER, enrolled.membership.role)
        assertEquals(enrolled.user.id, enrolled.membership.userId)
        assertEquals(AuthenticationAssurance.RECOVERY, enrolled.issuedEnrollmentSession.session.assurance)
        assertEquals(
            SessionAuthenticationMethod.INVITATION,
            enrolled.issuedEnrollmentSession.session.authenticationMethod
        )
        assertEquals(
            15 * 60 * 1_000L,
            enrolled.issuedEnrollmentSession.session.absoluteExpiresAt.toEpochMilliseconds() -
                enrolled.issuedEnrollmentSession.session.createdAt.toEpochMilliseconds()
        )
        val snapshot = fixture.store.snapshot()
        assertEquals(InvitationState.ACCEPTED, snapshot.invitations.single().state)
        assertEquals(1, snapshot.users.count { it.primaryEmail == EmailAddress("brand-new-invitee@example.test") })
        assertEquals(1, snapshot.sessions.count { it.authenticationMethod == SessionAuthenticationMethod.INVITATION })
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.INVITATION_ACCEPTED })
        assertFalse(snapshot.toString().contains(token))
    }

    @Test
    fun `registration policy distinguishes administrator and unowned invitation sources`() = runTest {
        val source = OrganizationFixture(registrationPolicy = RegistrationPolicy.OPEN)
        val issued = source.service.invite(
            source.ownerContext,
            source.organization.id,
            EmailAddress("policy-invitee@example.test"),
            OrganizationRole.VIEWER
        ).expectOrganizationSuccess()
        val token = issued.revealToken()
        val adminInvitation = source.store.snapshot().invitations.single()

        RegistrationPolicy.entries.forEach { policy ->
            suspend fun enroll(invitation: Invitation): IdentityOperationResult<EnrolledInvitation> {
                val store = InMemoryIdentityStore(
                    InMemoryIdentityStoreSeed(
                        organizations = listOf(source.organization),
                        invitations = listOf(invitation)
                    )
                )
                val service = IdentityOrganizationService(
                    store,
                    source.runtime.runtime,
                    source.config.copy(registrationPolicy = policy)
                )
                return service.enrollInvitation(token, "Policy Invitee")
            }

            val administratorResult = enroll(adminInvitation)
            if (policy == RegistrationPolicy.DISABLED) {
                assertEquals(
                    IdentityErrorCode.INVALID_CREDENTIALS,
                    administratorResult.expectOrganizationFailure(),
                    "DISABLED must reject administrator-issued onboarding"
                )
            } else {
                administratorResult.expectOrganizationSuccess()
            }

            val unownedResult = enroll(adminInvitation.copy(invitedByUserId = null))
            if (policy == RegistrationPolicy.OPEN || policy == RegistrationPolicy.INVITATION_ONLY) {
                unownedResult.expectOrganizationSuccess()
            } else {
                assertEquals(
                    IdentityErrorCode.INVALID_CREDENTIALS,
                    unownedResult.expectOrganizationFailure(),
                    "$policy must reject an invitation without an issuing administrator"
                )
            }
        }
    }

    @Test
    fun `disabled registration blocks new account enrollment without blocking existing member invitations`() = runTest {
        val fixture = OrganizationFixture(registrationPolicy = RegistrationPolicy.DISABLED)
        val existing = fixture.service.invite(
            fixture.ownerContext,
            fixture.organization.id,
            requireNotNull(fixture.invitee.primaryEmail),
            OrganizationRole.VIEWER
        ).expectOrganizationSuccess()
        fixture.service.acceptInvitation(fixture.inviteeContext, existing.revealToken())
            .expectOrganizationSuccess()

        val newAccount = fixture.service.invite(
            fixture.ownerContext,
            fixture.organization.id,
            EmailAddress("disabled@example.test"),
            OrganizationRole.VIEWER
        ).expectOrganizationSuccess()
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            fixture.service.enrollInvitation(newAccount.revealToken(), "Disabled Invitee")
                .expectOrganizationFailure()
        )
        val snapshot = fixture.store.snapshot()
        assertEquals(2, snapshot.users.size)
        assertTrue(snapshot.users.none { it.primaryEmail == EmailAddress("disabled@example.test") })
        assertEquals(InvitationState.PENDING, snapshot.invitations.single { it.id == newAccount.invitation.id }.state)
    }

    @Test
    fun `wrong expired and email-conflicting invitation enrollment rolls back`() = runTest {
        val wrongTokenFixture = OrganizationFixture()
        wrongTokenFixture.service.invite(
            wrongTokenFixture.ownerContext,
            wrongTokenFixture.organization.id,
            EmailAddress("wrong-token@example.test"),
            OrganizationRole.VIEWER
        ).expectOrganizationSuccess()
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            wrongTokenFixture.service.enrollInvitation(
                Base64Url.encode(ByteArray(32) { 0x7f }),
                "Wrong Token"
            ).expectOrganizationFailure()
        )
        assertEquals(InvitationState.PENDING, wrongTokenFixture.store.snapshot().invitations.single().state)

        val expiredFixture = OrganizationFixture()
        val expired = expiredFixture.service.invite(
            expiredFixture.ownerContext,
            expiredFixture.organization.id,
            EmailAddress("expired@example.test"),
            OrganizationRole.VIEWER
        ).expectOrganizationSuccess()
        expiredFixture.runtime.deterministicClock.advanceMilliseconds(
            expiredFixture.config.lifetimes.invitation.seconds * 1_000
        )
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            expiredFixture.service.enrollInvitation(expired.revealToken(), "Expired Invitee")
                .expectOrganizationFailure()
        )
        var snapshot = expiredFixture.store.snapshot()
        assertEquals(InvitationState.PENDING, snapshot.invitations.single().state)
        assertTrue(snapshot.sessions.none { it.authenticationMethod == SessionAuthenticationMethod.INVITATION })

        val duplicateFixture = OrganizationFixture()
        val duplicate = duplicateFixture.service.invite(
            duplicateFixture.ownerContext,
            duplicateFixture.organization.id,
            requireNotNull(duplicateFixture.invitee.primaryEmail),
            OrganizationRole.VIEWER
        ).expectOrganizationSuccess()
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            duplicateFixture.service.enrollInvitation(duplicate.revealToken(), "Duplicate Email")
                .expectOrganizationFailure()
        )
        snapshot = duplicateFixture.store.snapshot()
        assertEquals(InvitationState.PENDING, snapshot.invitations.single().state)
        assertEquals(2, snapshot.users.size)
        assertEquals(1, snapshot.memberships.size)
        assertTrue(snapshot.sessions.none { it.authenticationMethod == SessionAuthenticationMethod.INVITATION })
        assertTrue(snapshot.auditEvents.none { it.action == AuditAction.INVITATION_ACCEPTED })
    }

    @Test
    fun `membership privilege changes invalidate sessions and preserve the last owner`() = runTest {
        val fixture = OrganizationFixture(includeInviteeMembership = true)

        val changed = fixture.service.changeMembershipRole(
            fixture.ownerContext,
            fixture.organization.id,
            fixture.inviteeMembership.id,
            OrganizationRole.PUBLISHER
        ).expectOrganizationSuccess()
        assertEquals(OrganizationRole.PUBLISHER, changed.role)

        var snapshot = fixture.store.snapshot()
        assertEquals(1L, snapshot.users.single { it.id == fixture.invitee.id }.sessionEpoch)
        assertEquals(
            SessionState.REVOKED,
            snapshot.sessions.single { it.userId == fixture.invitee.id }.state
        )

        assertEquals(
            IdentityErrorCode.CONFLICT,
            fixture.service.removeMembership(
                fixture.ownerContext,
                fixture.organization.id,
                fixture.ownerMembership.id
            ).expectOrganizationFailure()
        )
        snapshot = fixture.store.snapshot()
        assertEquals(MembershipState.ACTIVE, snapshot.memberships.single { it.id == fixture.ownerMembership.id }.state)
        assertEquals(OrganizationRole.OWNER, snapshot.memberships.single { it.id == fixture.ownerMembership.id }.role)
    }

    @Test
    fun `admin cannot mutate owners and unauthorized tenant access is not found`() = runTest {
        val fixture = OrganizationFixture(includeInviteeMembership = true, inviteeRole = OrganizationRole.ADMIN)

        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            fixture.service.changeMembershipRole(
                fixture.inviteeScopedContext,
                fixture.organization.id,
                fixture.ownerMembership.id,
                OrganizationRole.ADMIN
            ).expectOrganizationFailure()
        )
        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            fixture.service.listMemberships(
                fixture.ownerContext,
                OrganizationId("another-organization")
            ).expectOrganizationFailure()
        )
    }

    @Test
    fun `audit reads require tenant capability and return only safe fields`() = runTest {
        val organizationId = IdentityFixtures.organizationId(3)
        val sensitiveMarker = "never-return-audit-digest-or-user-agent"
        val event = IdentityFixtures.auditEvent(
            IdentityFixtures.auditEventId(10)
        ).copy(
            organizationId = organizationId,
            request = AuditRequestMetadata(
                requestId = "request-visible",
                method = "POST",
                path = "/identity/v1/organizations/${organizationId.value}",
                userAgent = PseudonymousValue(sensitiveMarker),
                clientIpDigest = PseudonymousValue(sensitiveMarker),
                trustedProxy = true
            )
        )
        val other = event.copy(
            id = IdentityFixtures.auditEventId(11),
            organizationId = IdentityFixtures.organizationId(12)
        )
        val fixture = OrganizationFixture(auditEvents = listOf(event, other))

        val page = fixture.service.listAuditEvents(
            fixture.ownerContext,
            fixture.organization.id,
            limit = 10
        ).expectOrganizationSuccess()
        assertEquals(listOf(event.id), page.events.map { it.id })
        assertEquals(
            AuditRequestView("request-visible", "POST", event.request!!.path, trustedProxy = true),
            page.events.single().request
        )
        assertFalse(Json.encodeToString(page.events.single()).contains(sensitiveMarker))

        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            fixture.service.listAuditEvents(
                fixture.inviteeScopedContext,
                fixture.organization.id
            ).expectOrganizationFailure()
        )
        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            fixture.service.listAuditEvents(
                fixture.ownerContext,
                IdentityFixtures.organizationId(12)
            ).expectOrganizationFailure()
        )
    }
}

class IdentityServiceIdentityServiceTest {
    @Test
    fun `service credential is scoped digested expiring and rotation overlaps for ten minutes`() = runTest {
        val fixture = OrganizationFixture()
        val service = IdentityServiceIdentityService(
            fixture.store,
            fixture.runtime.runtime,
            fixture.config,
            allowedCapabilities = setOf(Capability.CONTENT_READ, Capability.CONTENT_PUBLISH),
            capabilityResolver = CapabilityResolver {
                setOf(Capability.CONTENT_READ, Capability.CONTENT_PUBLISH)
            }
        )

        val issued = service.create(
            fixture.ownerContext,
            fixture.organization.id,
            "Release robot",
            capabilities = setOf(Capability.CONTENT_READ)
        ).expectServiceSuccess()
        val initialToken = issued.revealToken()
        assertEquals(32, Base64Url.decode(initialToken.substringAfter('.')).size)
        var snapshot = fixture.store.snapshot()
        assertFalse(snapshot.toString().contains(initialToken))
        assertEquals(DigestAlgorithm.HMAC_SHA256, snapshot.serviceCredentials.single().secretDigest.algorithm)

        val authenticated = service.authenticate(initialToken).expectServiceSuccess()
        assertEquals(IdentityPrincipalKind.SERVICE, authenticated.principal?.kind)
        assertEquals(fixture.organization.id, authenticated.organization?.id)
        assertEquals(setOf(Capability.CONTENT_READ), authenticated.effectiveCapabilities)

        val initialId = issued.value.credential.id
        val rotated = service.rotateCredential(
            fixture.ownerContext,
            fixture.organization.id,
            initialId
        ).expectServiceSuccess()
        val replacementToken = rotated.revealToken()
        service.authenticate(initialToken).expectServiceSuccess()
        service.authenticate(replacementToken).expectServiceSuccess()

        fixture.runtime.deterministicClock.advanceMilliseconds(600_000)
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            service.authenticate(initialToken).expectServiceFailure()
        )
        service.authenticate(replacementToken).expectServiceSuccess()

        service.revokeCredential(
            fixture.ownerContext.copy(
                principal = fixture.ownerContext.principal?.copy(
                    authenticatedAt = fixture.runtime.deterministicClock.now()
                )
            ),
            fixture.organization.id,
            rotated.credential.id
        ).expectServiceSuccess()
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            service.authenticate(replacementToken).expectServiceFailure()
        )

        snapshot = fixture.store.snapshot()
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.SERVICE_CREDENTIAL_ROTATED })
        assertTrue(snapshot.auditEvents.any { it.action == AuditAction.SERVICE_CREDENTIAL_REVOKED })
    }

    @Test
    fun `service scopes must come from the configured allowlist`() = runTest {
        val fixture = OrganizationFixture()
        val service = IdentityServiceIdentityService(
            fixture.store,
            fixture.runtime.runtime,
            fixture.config,
            allowedCapabilities = setOf(Capability.CONTENT_READ)
        )

        assertEquals(
            IdentityErrorCode.REQUEST_INVALID,
            service.create(
                fixture.ownerContext,
                fixture.organization.id,
                "Overpowered robot",
                capabilities = setOf(Capability.ORGANIZATION_DELETE)
            ).expectServiceFailure()
        )
        assertTrue(fixture.store.snapshot().serviceIdentities.isEmpty())
    }

    @Test
    fun `service credential allowlist rejects identity management and global recovery capabilities`() {
        val fixture = OrganizationFixture()
        val forbidden = Capability.IDENTITY_MANAGEMENT + Capability.ACCOUNT_RECOVERY_ADMIN

        forbidden.forEach { capability ->
            assertFailsWith<IllegalArgumentException>(capability.wireName) {
                IdentityServiceIdentityService(
                    fixture.store,
                    fixture.runtime.runtime,
                    fixture.config,
                    allowedCapabilities = setOf(Capability.CONTENT_READ, capability)
                )
            }
        }
    }

    @Test
    fun `administrator cannot mint identity owner or global recovery authority`() = runTest {
        val fixture = OrganizationFixture(
            includeInviteeMembership = true,
            inviteeRole = OrganizationRole.ADMIN
        )
        val service = IdentityServiceIdentityService(
            fixture.store,
            fixture.runtime.runtime,
            fixture.config,
            allowedCapabilities = setOf(Capability.CONTENT_READ),
            capabilityResolver = CapabilityResolver {
                Capability.IDENTITY_MANAGEMENT + Capability.ACCOUNT_RECOVERY_ADMIN + Capability.CONTENT_READ
            }
        )

        val forbidden = setOf(
            Capability.ORGANIZATION_DELETE,
            Capability.ORGANIZATION_TRANSFER_OWNERSHIP,
            Capability.MEMBERSHIP_UPDATE,
            Capability.ACCOUNT_RECOVERY_ADMIN
        )
        forbidden.forEach { capability ->
            assertEquals(
                IdentityErrorCode.REQUEST_INVALID,
                service.create(
                    fixture.inviteeScopedContext,
                    fixture.organization.id,
                    "Overpowered ${capability.wireName}",
                    capabilities = setOf(capability)
                ).expectServiceFailure(),
                capability.wireName
            )
        }
        assertTrue(fixture.store.snapshot().serviceIdentities.isEmpty())
    }

    @Test
    fun `administrator delegates only effective application capabilities for the organization`() = runTest {
        val fixture = OrganizationFixture(
            includeInviteeMembership = true,
            inviteeRole = OrganizationRole.ADMIN
        )
        val service = IdentityServiceIdentityService(
            fixture.store,
            fixture.runtime.runtime,
            fixture.config,
            allowedCapabilities = setOf(Capability.CONTENT_READ, Capability.CONTENT_PUBLISH),
            capabilityResolver = CapabilityResolver { context ->
                when (context.principal?.userId) {
                    fixture.owner.id -> setOf(Capability.CONTENT_READ, Capability.CONTENT_PUBLISH)
                    fixture.invitee.id -> setOf(Capability.CONTENT_READ)
                    else -> emptySet()
                }
            }
        )

        val identity = service.create(
            fixture.ownerContext,
            fixture.organization.id,
            "Release robot",
            capabilities = setOf(Capability.CONTENT_READ, Capability.CONTENT_PUBLISH)
        ).expectServiceSuccess()

        assertEquals(
            IdentityErrorCode.REQUEST_INVALID,
            service.createCredential(
                fixture.inviteeScopedContext,
                fixture.organization.id,
                identity.value.identity.id,
                capabilities = setOf(Capability.CONTENT_PUBLISH)
            ).expectServiceFailure()
        )
        assertEquals(
            IdentityErrorCode.REQUEST_INVALID,
            service.rotateCredential(
                fixture.inviteeScopedContext,
                fixture.organization.id,
                identity.value.credential.id
            ).expectServiceFailure()
        )
        val delegated = service.createCredential(
            fixture.inviteeScopedContext,
            fixture.organization.id,
            identity.value.identity.id,
            capabilities = setOf(Capability.CONTENT_READ)
        ).expectServiceSuccess()
        assertEquals(setOf(Capability.CONTENT_READ), delegated.credential.capabilities)

        val adminIdentity = service.create(
            fixture.inviteeScopedContext,
            fixture.organization.id,
            "Read robot",
            capabilities = setOf(Capability.CONTENT_READ)
        ).expectServiceSuccess()
        assertEquals(setOf(Capability.CONTENT_READ), adminIdentity.value.identity.capabilities)
    }
}

private class OrganizationFixture(
    withOrganization: Boolean = true,
    includeInviteeMembership: Boolean = false,
    inviteeRole: OrganizationRole = OrganizationRole.VIEWER,
    auditEvents: List<AuditEvent> = emptyList(),
    registrationPolicy: RegistrationPolicy = RegistrationPolicy.INVITATION_ONLY
) {
    val owner = IdentityFixtures.user(IdentityFixtures.userId(1))
    val invitee = IdentityFixtures.user(IdentityFixtures.userId(2))
    val organization = IdentityFixtures.organization(IdentityFixtures.organizationId(3))
    val ownerMembership = IdentityFixtures.membership(
        id = IdentityFixtures.membershipId(4),
        organizationId = organization.id,
        userId = owner.id
    )
    val inviteeMembership = IdentityFixtures.membership(
        id = IdentityFixtures.membershipId(5),
        organizationId = organization.id,
        userId = invitee.id,
        role = inviteeRole
    )
    private val ownerSession = IdentityFixtures.session(
        id = IdentityFixtures.sessionId(6),
        userId = owner.id
    )
    private val inviteeSession = IdentityFixtures.session(
        id = IdentityFixtures.sessionId(7),
        userId = invitee.id
    )
    val config = organizationConfig(registrationPolicy)
    val runtime = DeterministicIdentityRuntime(
        deterministicSecrets = DeterministicIdentitySecretResolver(
            mapOf(
                config.keys.sessionPepper to ByteArray(32) { 0x21 },
                config.keys.recoveryPepper to ByteArray(32) { 0x31 },
                config.keys.serviceCredentialPepper to ByteArray(32) { 0x42 }
            )
        )
    )
    val store = InMemoryIdentityStore(
        InMemoryIdentityStoreSeed(
            users = listOf(owner, invitee),
            sessions = listOf(ownerSession, inviteeSession),
            organizations = if (withOrganization) listOf(organization) else emptyList(),
            memberships = buildList {
                if (withOrganization) add(ownerMembership)
                if (withOrganization && includeInviteeMembership) add(inviteeMembership)
            },
            auditEvents = auditEvents
        )
    )
    val service = IdentityOrganizationService(store, runtime.runtime, config)
    val actorWithoutOrganization = userContext(owner, ownerSession)
    val ownerContext = userContext(owner, ownerSession, organization, ownerMembership)
    val inviteeContext = userContext(invitee, inviteeSession)
    val inviteeScopedContext = userContext(invitee, inviteeSession, organization, inviteeMembership)

    private fun userContext(
        user: User,
        session: IdentitySession,
        organization: Organization? = null,
        membership: Membership? = null
    ) = IdentityContext(
        principal = IdentityPrincipal(
            kind = IdentityPrincipalKind.USER,
            userId = user.id,
            displayName = user.displayName,
            assurance = AuthenticationAssurance.PASSKEY,
            authenticatedAt = session.authenticatedAt,
            sessionId = session.id
        ),
        session = session,
        organization = organization,
        membership = membership
    )
}

private fun organizationConfig(
    registrationPolicy: RegistrationPolicy = RegistrationPolicy.INVITATION_ONLY
): IdentityConfig {
    val environment = if (registrationPolicy == RegistrationPolicy.OPEN) {
        IdentityEnvironment.DEVELOPMENT
    } else {
        IdentityEnvironment.TEST
    }
    fun secret(name: String) = SecretReference("test", name, "v1", environment)
    return IdentityConfig(
        environment = environment,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig("localhost", "Aether Organization Test", setOf("http://localhost:8080")),
        keys = IdentityKeyConfig(
            sessionPepper = secret("session"),
            recoveryPepper = secret("recovery"),
            deviceTokenPepper = secret("device"),
            serviceCredentialPepper = secret("service"),
            auditPseudonymizationKey = secret("audit"),
            encryptionKey = secret("encryption"),
            signingKey = secret("signing")
        ),
        registrationPolicy = registrationPolicy
    )
}

private fun <T> IdentityOperationResult<T>.expectOrganizationSuccess(): T = when (this) {
    is IdentityOperationResult.Success -> value
    is IdentityOperationResult.Failure -> error("Expected success, got $code")
}

private fun IdentityOperationResult<*>.expectOrganizationFailure(): IdentityErrorCode = when (this) {
    is IdentityOperationResult.Success -> error("Expected failure")
    is IdentityOperationResult.Failure -> code
}

private fun <T> IdentityOperationResult<T>.expectServiceSuccess(): T = when (this) {
    is IdentityOperationResult.Success -> value
    is IdentityOperationResult.Failure -> error("Expected service success, got $code")
}

private fun IdentityOperationResult<*>.expectServiceFailure(): IdentityErrorCode = when (this) {
    is IdentityOperationResult.Success -> error("Expected service failure")
    is IdentityOperationResult.Failure -> code
}
