package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.*
import codes.yousef.aether.auth.testkit.IdentityFixtures
import codes.yousef.aether.auth.testkit.IdentityStoreConformanceSuite
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject as VertxJsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostgresqlIdentityStoreIntegrationTest {
    @Test
    fun migrationAndDirectTransportPreserveAtomicStoreSemantics() = runBlocking {
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("aether_identity_test")
            withUsername("aether")
            withPassword("aether-test-password")
        }
        withContext(Dispatchers.IO) { postgres.start() }

        val vertx = Vertx.vertx()
        val pool = PgBuilder.pool()
            .with(PoolOptions().setMaxSize(8))
            .connectingTo(
                PgConnectOptions()
                    .setHost(postgres.host)
                    .setPort(postgres.firstMappedPort)
                    .setDatabase(postgres.databaseName)
                    .setUser(postgres.username)
                    .setPassword(postgres.password)
            )
            .using(vertx)
            .build()

        try {
            pool.query("SELECT 1").execute().coAwait()
            val foundationRunner = PostgresqlMigrationRunner(
                pool,
                PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCE
            )
            val foundationMigration = assertIs<StoreResult.Success<PostgresqlMigrationReport>>(
                foundationRunner.migrate()
            )
            assertEquals(1, foundationMigration.value.version)
            assertEquals(true, foundationMigration.value.applied)

            val migrationRunner = PostgresqlMigrationRunner(pool)
            val currentMigrationResult = migrationRunner.migrate()
            val currentMigration = assertIs<StoreResult.Success<PostgresqlMigrationReport>>(
                currentMigrationResult,
                "Migration failed safely: $currentMigrationResult"
            )
            assertEquals(11, currentMigration.value.version)
            assertEquals(true, currentMigration.value.applied)
            val secondMigration = assertIs<StoreResult.Success<PostgresqlMigrationReport>>(migrationRunner.migrate())
            assertEquals(false, secondMigration.value.applied)
            assertEquals(currentMigration.value.checksum, secondMigration.value.checksum)
            assertEquals(
                11L,
                pool.query(
                    "SELECT COUNT(*) AS count FROM aether_identity.schema_migrations " +
                        "WHERE module = 'aether-auth-postgresql'"
                    ).execute().coAwait().iterator().next().getLong("count")
            )
            val reviewedMigrations = assertNotNull(loadPackagedPostgresqlMigrations())
            val storedMigrationChecksums = pool.query(
                "SELECT version, checksum FROM aether_identity.schema_migrations " +
                    "WHERE module = 'aether-auth-postgresql' ORDER BY version"
            ).execute().coAwait().map { row ->
                row.getInteger("version") to row.getString("checksum")
            }
            assertEquals(
                reviewedMigrations.map { migration -> migration.version to migration.checksum },
                storedMigrationChecksums
            )
            assertEquals(
                false,
                pool.query(
                    "SELECT has_schema_privilege(" +
                        "'public', 'aether_identity_internal', 'USAGE') AS allowed"
                ).execute().coAwait().single().getBoolean("allowed")
            )
            assertEquals(
                0L,
                pool.query(
                    "SELECT COUNT(*) AS count FROM pg_catalog.pg_proc p " +
                        "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " +
                        "WHERE n.nspname = 'aether_identity_internal' " +
                        "AND p.proname IN ('v1_complete_credential_registration', " +
                        "'v1_complete_credential_authentication', " +
                        "'v1_quarantine_credential_authentication', " +
                        "'v1_complete_recovery_enrollment', " +
                        "'resolve_web_authn_attempt', 'web_authn_store_error_code') " +
                        "AND has_function_privilege('public', p.oid, 'EXECUTE')"
                ).execute().coAwait().single().getLong("count")
            )
            assertEquals(
                false,
                pool.query(
                    "SELECT has_function_privilege(" +
                        "'public', 'aether_identity.v1_revoke_federated_sessions(jsonb)', " +
                        "'EXECUTE') AS allowed"
                ).execute().coAwait().single().getBoolean("allowed")
            )
            assertEquals(
                0L,
                pool.query(
                    "SELECT COUNT(*) AS count FROM pg_catalog.pg_proc p " +
                        "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " +
                        "WHERE n.nspname = 'aether_identity_internal' " +
                        "AND p.proname IN ('federation_provider_lease', " +
                        "'require_federation_provider_lease', 'require_federated_session') " +
                        "AND has_function_privilege('public', p.oid, 'EXECUTE')"
                ).execute().coAwait().single().getLong("count")
            )
            assertEquals(
                5L,
                pool.query(
                    "SELECT COUNT(*) AS count FROM pg_catalog.pg_proc p " +
                        "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " +
                        "WHERE n.nspname = 'aether_identity' " +
                        "AND p.proname IN ('v1_find_federation_provider_control', " +
                        "'v1_find_federation_provider_control_by_storage_key', " +
                        "'v1_acquire_federation_provider_lease', " +
                        "'v1_validate_federation_provider_lease', " +
                        "'v1_compare_and_set_federation_provider_state')"
                ).execute().coAwait().single().getLong("count")
            )
            assertEquals(
                1L,
                pool.query(
                    "SELECT COUNT(*) AS count FROM information_schema.columns " +
                        "WHERE table_schema = 'aether_identity' AND table_name = 'sessions' " +
                        "AND column_name = 'federation_provider_session_epoch'"
                ).execute().coAwait().single().getLong("count")
            )
            assertEquals(
                4L,
                pool.query(
                    "SELECT COUNT(*) AS count FROM pg_catalog.pg_proc p " +
                        "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace " +
                        "WHERE n.nspname = 'aether_identity' " +
                        "AND p.proname IN ('v1_complete_credential_registration', " +
                        "'v1_complete_credential_authentication', " +
                        "'v1_quarantine_credential_authentication', " +
                        "'v1_complete_recovery_enrollment') " +
                        "AND p.prosecdef AND p.proconfig = ARRAY['search_path=pg_catalog']::text[]"
                ).execute().coAwait().single().getLong("count")
            )

            val config = PostgresqlIdentityConfig(IdentityEnvironment.TEST, "aether_test")
            val store = PostgresqlIdentityStore(config, VertxPostgresqlRpcTransport(config, pool))
            val missingMarker = assertIs<StoreResult.Failure>(store.initialize())
            assertEquals(IdentityStoreErrorCode.INTERNAL, missingMarker.error.code)
            assertEquals(
                false,
                pool.query(
                    "SELECT has_function_privilege(" +
                        "'public', 'aether_identity.provision_environment(text,text)', 'EXECUTE') AS allowed"
                ).execute().coAwait().single().getBoolean("allowed")
            )
            provisionEnvironment(pool, IdentityEnvironment.TEST, "aether_test")
            provisionEnvironment(pool, IdentityEnvironment.TEST, "aether_test")
            val mismatchedProvision = try {
                provisionEnvironment(pool, IdentityEnvironment.DEVELOPMENT, "aether_development")
                null
            } catch (failure: io.vertx.pgclient.PgException) {
                failure
            }
            assertEquals("A0001", assertNotNull(mismatchedProvision).sqlState)

            val wireJson = defaultPostgresqlJson()
            val environmentRequest = PostgresqlRpcRequestEnvelope(
                operation = PostgresqlRpcOperation.ASSERT_ENVIRONMENT.wireName,
                environment = IdentityEnvironment.TEST,
                namespace = "aether_test",
                payload = buildJsonObject { }
            )
            val wireRows = pool.preparedQuery(
                "SELECT aether_identity.v1_assert_environment(\$1::jsonb)::text AS response"
            ).execute(Tuple.of(VertxJsonObject(wireJson.encodeToString(environmentRequest)))).coAwait()
            val wireResponseText = wireRows.iterator().next().getString("response")
            val wireResponse = wireJson.decodeFromString<PostgresqlRpcResponseEnvelope>(wireResponseText)
            assertEquals(PostgresqlRpcOutcome.SUCCESS, wireResponse.outcome)

            val initialization = store.initialize()
            assertIs<StoreResult.Success<Unit>>(initialization, "Initialization failed safely: $initialization")

            val user = verifyConcurrentBootstrap(store, pool)
            IdentityStoreConformanceSuite(store, "postgresql-real").runAll()
            verifyFederationJitRaceLeavesNoOrphans(store)

            val scimUser = IdentityFixtures.user(IdentityFixtures.userId("user-scim"))
            val scimAudit = IdentityFixtures.auditEvent(
                id = IdentityFixtures.auditEventId("audit-scim-create-user"),
                action = AuditAction.SCIM_MUTATION_APPLIED,
                targetId = scimUser.id.value
            )
            val scimCommand =
                ApplyScimMutationCommand(IdentityFixtures.scimMutation(user = scimUser), scimAudit)
            val scimCreate = store.applyScimMutation(scimCommand)
            val scimCreateCommit = assertIs<StoreResult.Success<ScimMutationCommit>>(
                scimCreate,
                "SCIM user creation failed safely: $scimCreate"
            )
            assertEquals(false, scimCreateCommit.value.alreadyApplied)
            val scimRetry = assertIs<StoreResult.Success<ScimMutationCommit>>(
                store.applyScimMutation(scimCommand)
            )
            assertEquals(true, scimRetry.value.alreadyApplied)
            assertEquals(null, scimRetry.value.auditEvent)
            assertEquals(scimUser, assertIs<StoreResult.Success<*>>(store.findUser(scimUser.id)).value)
            assertEquals(
                scimUser,
                assertIs<StoreResult.Success<*>>(store.findUserByEmail(scimUser.primaryEmail!!)).value
            )

            val challenge = IdentityFixtures.challenge(id = IdentityFixtures.challengeId("challenge-concurrent"))
            assertIs<StoreResult.Success<*>>(store.createChallenge(CreateChallengeCommand(challenge)))
            val consume = codes.yousef.aether.auth.ConsumeChallengeCommand(
                challengeId = challenge.id,
                expectedVersion = 0,
                terminalState = codes.yousef.aether.auth.ChallengeState.CONSUMED,
                consumedAt = IdentityFixtures.instant(1_000)
            )
            val concurrentResults = listOf(
                async(Dispatchers.Default) { store.consumeChallenge(consume) },
                async(Dispatchers.Default) { store.consumeChallenge(consume) }
            ).awaitAll()
            assertEquals(1, concurrentResults.count { it is StoreResult.Success })
            val raceFailure = assertIs<StoreResult.Failure>(concurrentResults.single { it is StoreResult.Failure })
            assertEquals(IdentityStoreErrorCode.VERSION_CONFLICT, raceFailure.error.code)

            val firstRegistrationChallenge = IdentityFixtures.challenge(
                id = IdentityFixtures.challengeId("challenge-register-1"),
                purpose = ChallengePurpose.WEBAUTHN_REGISTRATION
            )
            val secondRegistrationChallenge = IdentityFixtures.challenge(
                id = IdentityFixtures.challengeId("challenge-register-2"),
                purpose = ChallengePurpose.WEBAUTHN_REGISTRATION
            )
            assertIs<StoreResult.Success<*>>(
                store.createChallenge(CreateChallengeCommand(firstRegistrationChallenge))
            )
            assertIs<StoreResult.Success<*>>(
                store.createChallenge(CreateChallengeCommand(secondRegistrationChallenge))
            )

            val firstCredential = IdentityFixtures.credential(
                id = IdentityFixtures.credentialId("credential-internal-1"),
                signCount = 10
            )
            val firstRegistration = CompleteCredentialRegistrationCommand(
                challengeId = firstRegistrationChallenge.id,
                expectedChallengeVersion = 0,
                credential = firstCredential,
                auditEvent = IdentityFixtures.auditEvent(
                    id = IdentityFixtures.auditEventId("audit-register-1"),
                    action = AuditAction.CREDENTIAL_REGISTERED
                ),
                rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(firstRegistrationChallenge.id)
            )
            assertIs<StoreResult.Success<*>>(store.completeCredentialRegistration(firstRegistration))
            assertEquals(
                firstCredential,
                assertIs<StoreResult.Success<*>>(
                    store.findCredentialByWebAuthnId(firstCredential.webAuthnId)
                ).value
            )

            val duplicateCredential = IdentityFixtures.credential(
                id = IdentityFixtures.credentialId("credential-internal-2"),
                webAuthnId = firstCredential.webAuthnId
            )
            val duplicateResult = store.completeCredentialRegistration(
                CompleteCredentialRegistrationCommand(
                    challengeId = secondRegistrationChallenge.id,
                    expectedChallengeVersion = 0,
                    credential = duplicateCredential,
                    auditEvent = IdentityFixtures.auditEvent(
                        id = IdentityFixtures.auditEventId("audit-register-2"),
                        action = AuditAction.CREDENTIAL_REGISTERED
                    ),
                    rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(secondRegistrationChallenge.id)
                )
            )
            assertEquals(
                IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
                assertIs<StoreResult.Success<WebAuthnCeremonyAttemptCommit<CredentialRegistrationCommit>>>(
                    duplicateResult
                ).value.rejection?.error?.code
            )
            assertEquals(
                ChallengeState.FAILED,
                assertIs<StoreResult.Success<*>>(store.findChallenge(secondRegistrationChallenge.id))
                    .value.let { it as codes.yousef.aether.auth.Challenge }.state
            )

            val immutableBackupEligibilityChallenge = IdentityFixtures.challenge(
                id = IdentityFixtures.challengeId("challenge-auth-immutable-backup-eligibility")
            )
            assertIs<StoreResult.Success<*>>(
                store.createChallenge(CreateChallengeCommand(immutableBackupEligibilityChallenge))
            )
            val authenticationAt = IdentityFixtures.instant(1_500)
            val backupEligibilityChange = store.completeCredentialAuthentication(
                CompleteCredentialAuthenticationCommand(
                    challengeId = immutableBackupEligibilityChallenge.id,
                    expectedChallengeVersion = 0,
                    credentialId = firstCredential.id,
                    expectedCredentialVersion = 0,
                    newSignCount = 11,
                    backupEligible = true,
                    backedUp = false,
                    authenticatedAt = authenticationAt,
                    session = IdentityFixtures.session(
                        id = IdentityFixtures.sessionId("session-invalid-backup-eligibility"),
                        createdAt = authenticationAt
                    ),
                    auditEvent = IdentityFixtures.auditEvent(
                        id = IdentityFixtures.auditEventId("audit-auth-invalid-backup-eligibility"),
                        action = AuditAction.CREDENTIAL_AUTHENTICATED,
                        targetId = firstCredential.id.value
                    ).copy(occurredAt = authenticationAt),
                    rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(
                        immutableBackupEligibilityChallenge.id,
                        authenticationAt
                    )
                )
            )
            assertEquals(
                IdentityStoreErrorCode.INVALID_TRANSITION,
                assertIs<StoreResult.Success<WebAuthnCeremonyAttemptCommit<CredentialAuthenticationCommit>>>(
                    backupEligibilityChange
                ).value.rejection?.error?.code
            )
            assertEquals(
                ChallengeState.FAILED,
                assertIs<StoreResult.Success<*>>(
                    store.findChallenge(immutableBackupEligibilityChallenge.id)
                ).value.let { it as codes.yousef.aether.auth.Challenge }.state
            )

            val quarantineChallenge = IdentityFixtures.challenge(id = IdentityFixtures.challengeId("challenge-quarantine"))
            assertIs<StoreResult.Success<*>>(
                store.createChallenge(CreateChallengeCommand(quarantineChallenge))
            )
            val detectedAt = IdentityFixtures.instant(2_000)
            val quarantineAudit = IdentityFixtures.auditEvent(
                id = IdentityFixtures.auditEventId("audit-quarantine"),
                action = AuditAction.CREDENTIAL_QUARANTINED,
                targetId = firstCredential.id.value
            ).copy(outcome = AuditOutcome.DENIED, occurredAt = detectedAt)
            val quarantineBackupEligibilityChange = store.quarantineCredentialAuthentication(
                QuarantineCredentialAuthenticationCommand(
                    challengeId = quarantineChallenge.id,
                    expectedChallengeVersion = 0,
                    credentialId = firstCredential.id,
                    expectedCredentialVersion = 0,
                    observedSignCount = 9,
                    backupEligible = true,
                    backedUp = false,
                    detectedAt = detectedAt,
                    auditEvent = quarantineAudit,
                    rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(
                        quarantineChallenge.id,
                        detectedAt
                    )
                )
            )
            assertEquals(
                IdentityStoreErrorCode.INVALID_TRANSITION,
                assertIs<StoreResult.Success<WebAuthnCeremonyAttemptCommit<CredentialQuarantineCommit>>>(
                    quarantineBackupEligibilityChange
                ).value.rejection?.error?.code
            )
            val validQuarantineChallenge = IdentityFixtures.challenge(
                id = IdentityFixtures.challengeId("challenge-quarantine-valid")
            )
            assertIs<StoreResult.Success<*>>(
                store.createChallenge(CreateChallengeCommand(validQuarantineChallenge))
            )
            val quarantineAttempt = assertIs<
                StoreResult.Success<WebAuthnCeremonyAttemptCommit<CredentialQuarantineCommit>>
            >(
                store.quarantineCredentialAuthentication(
                    QuarantineCredentialAuthenticationCommand(
                        challengeId = validQuarantineChallenge.id,
                        expectedChallengeVersion = 0,
                        credentialId = firstCredential.id,
                        expectedCredentialVersion = 0,
                        observedSignCount = 9,
                        backupEligible = false,
                        backedUp = false,
                        detectedAt = detectedAt,
                        auditEvent = quarantineAudit.copy(
                            id = IdentityFixtures.auditEventId("audit-quarantine-valid")
                        ),
                        rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(
                            validQuarantineChallenge.id,
                            detectedAt
                        )
                    )
                )
            )
            val quarantine = requireNotNull(quarantineAttempt.value.completion)
            assertEquals(codes.yousef.aether.auth.CredentialState.SUSPECTED_CLONE, quarantine.credential.state)
            assertEquals(9, quarantine.credential.signCount)
            assertEquals("signature_counter_anomaly", quarantine.credential.revocationReasonCode)
            assertNotNull(quarantine.challenge.consumedAt)

            val renamedAt = IdentityFixtures.instant(2_500)
            val renamedCredential = quarantine.credential.copy(
                name = "Renamed security key",
                version = quarantine.credential.version + 1,
                updatedAt = renamedAt
            )
            assertEquals(
                renamedCredential,
                assertIs<StoreResult.Success<Credential>>(
                    store.mutateCredential(
                        MutateCredentialCommand(
                            credentialId = renamedCredential.id,
                            expectedVersion = quarantine.credential.version,
                            replacement = renamedCredential,
                            auditEvent = IdentityFixtures.auditEvent(
                                IdentityFixtures.auditEventId("audit-credential-rename"),
                                AuditAction.CREDENTIAL_RENAMED,
                                renamedCredential.id.value
                            ).copy(
                                target = AuditTarget(AuditTargetType.CREDENTIAL, renamedCredential.id.value),
                                occurredAt = renamedAt
                            )
                        )
                    )
                ).value
            )
            val revokedCredential = renamedCredential.copy(
                state = CredentialState.REVOKED,
                version = renamedCredential.version + 1,
                updatedAt = IdentityFixtures.instant(2_600),
                revokedAt = IdentityFixtures.instant(2_600),
                revocationReasonCode = "user_revoked"
            )
            assertEquals(
                revokedCredential,
                assertIs<StoreResult.Success<Credential>>(
                    store.mutateCredential(
                        MutateCredentialCommand(
                            credentialId = revokedCredential.id,
                            expectedVersion = renamedCredential.version,
                            replacement = revokedCredential,
                            auditEvent = IdentityFixtures.auditEvent(
                                IdentityFixtures.auditEventId("audit-credential-revoke"),
                                AuditAction.CREDENTIAL_REVOKED,
                                revokedCredential.id.value
                            ).copy(
                                target = AuditTarget(AuditTargetType.CREDENTIAL, revokedCredential.id.value),
                                occurredAt = revokedCredential.revokedAt!!
                            )
                        )
                    )
                ).value
            )

            val organization = IdentityFixtures.organization()
            val owner = IdentityFixtures.membership()
            val organizationAudit = IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId("audit-organization-create"),
                AuditAction.ORGANIZATION_CREATED,
                organization.id.value
            ).copy(target = AuditTarget(AuditTargetType.ORGANIZATION, organization.id.value))
            assertIs<StoreResult.Success<OrganizationCreationCommit>>(
                store.createOrganization(CreateOrganizationCommand(organization, owner, organizationAudit))
            )
            assertEquals(
                organization,
                assertIs<StoreResult.Success<Organization?>>(store.findOrganizationBySlug(organization.slug)).value
            )
            assertEquals(
                setOf(organization.id, IdentityFixtures.organizationId("organization-bootstrap")),
                assertIs<StoreResult.Success<List<Organization>>>(
                    store.listOrganizationsForUser(user.id)
                ).value.map { it.id }.toSet()
            )

            val secondUser = IdentityFixtures.user(IdentityFixtures.userId("user-2"))
            assertIs<StoreResult.Success<ScimMutationCommit>>(
                store.applyScimMutation(
                    ApplyScimMutationCommand(
                        IdentityFixtures.scimMutation(IdentityFixtures.scimOperationId("scim-operation-user-2"), secondUser),
                        IdentityFixtures.auditEvent(
                            IdentityFixtures.auditEventId("audit-scim-create-user-2"),
                            AuditAction.SCIM_MUTATION_APPLIED,
                            secondUser.id.value
                        )
                    )
                )
            )
            val secondOwner = IdentityFixtures.membership(
                id = IdentityFixtures.membershipId("membership-owner-2"),
                userId = secondUser.id
            )
            assertIs<StoreResult.Success<Membership>>(
                store.createMembership(
                    CreateMembershipCommand(
                        secondOwner,
                        auditEvent = IdentityFixtures.auditEvent(
                            IdentityFixtures.auditEventId("audit-membership-owner-2"),
                            AuditAction.MEMBERSHIP_CREATED,
                            secondOwner.id.value
                        )
                    )
                )
            )
            val epochSession = IdentityFixtures.session(
                id = IdentityFixtures.sessionId("session-membership-epoch"),
                createdAt = IdentityFixtures.instant(3_000)
            )
            assertIs<StoreResult.Success<IdentitySession>>(
                store.createSession(
                    CreateSessionCommand(
                        epochSession,
                        IdentityFixtures.auditEvent(
                            IdentityFixtures.auditEventId("audit-session-membership-epoch"),
                            AuditAction.SESSION_CREATED,
                            epochSession.id.value
                        ).copy(occurredAt = epochSession.createdAt)
                    )
                )
            )
            val membershipChangedAt = IdentityFixtures.instant(4_000)
            val demotedOwner = owner.copy(
                role = OrganizationRole.ADMIN,
                version = owner.version + 1,
                updatedAt = membershipChangedAt
            )
            assertIs<StoreResult.Success<Membership>>(
                store.mutateMembership(
                    MutateMembershipCommand(
                        membershipId = owner.id,
                        expectedVersion = owner.version,
                        replacement = demotedOwner,
                        auditEvent = IdentityFixtures.auditEvent(
                            IdentityFixtures.auditEventId("audit-owner-demote-safe"),
                            AuditAction.MEMBERSHIP_CHANGED,
                            owner.id.value
                        ).copy(occurredAt = membershipChangedAt),
                        expectedUserVersion = user.version,
                        expectedSessionEpoch = user.sessionEpoch,
                        newSessionEpoch = user.sessionEpoch + 1,
                        sessionsRevokedAt = membershipChangedAt,
                        sessionRevocationReasonCode = "organization_privilege_changed"
                    )
                )
            )
            assertEquals(
                1,
                assertIs<StoreResult.Success<User?>>(store.findUser(user.id)).value?.sessionEpoch
            )
            assertEquals(
                SessionState.REVOKED,
                assertIs<StoreResult.Success<IdentitySession?>>(store.findSession(epochSession.id)).value?.state
            )
            assertEquals(
                2,
                assertIs<StoreResult.Success<List<Membership>>>(
                    store.listMembershipsForOrganization(organization.id)
                ).value.size
            )

            val invitation = IdentityFixtures.invitation()
            val invitationAudit = IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId("audit-invitation-create"),
                AuditAction.INVITATION_CREATED,
                invitation.id.value
            ).copy(target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value))
            assertIs<StoreResult.Success<Invitation>>(
                store.createInvitation(CreateInvitationCommand(invitation, invitationAudit))
            )
            assertEquals(
                invitation,
                assertIs<StoreResult.Success<Invitation?>>(
                    store.findInvitationByTokenDigest(invitation.tokenDigest)
                ).value
            )
            assertEquals(
                listOf(invitation),
                assertIs<StoreResult.Success<List<Invitation>>>(
                    store.listInvitationsForOrganization(organization.id)
                ).value
            )
            val revokedInvitation = invitation.copy(
                state = InvitationState.REVOKED,
                version = 1,
                revokedAt = IdentityFixtures.instant(4_500)
            )
            assertEquals(
                revokedInvitation,
                assertIs<StoreResult.Success<Invitation>>(
                    store.mutateInvitation(
                        MutateInvitationCommand(
                            invitation.id,
                            invitation.version,
                            revokedInvitation,
                            IdentityFixtures.auditEvent(
                                IdentityFixtures.auditEventId("audit-invitation-revoke"),
                                AuditAction.INVITATION_REVOKED,
                                invitation.id.value
                            ).copy(target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value))
                        )
                    )
                ).value
            )

            verifyOrganizationAuditAndInvitationEnrollment(store, organization, secondUser)

            val serviceIdentity = IdentityFixtures.serviceIdentity()
            val initialServiceCredential = IdentityFixtures.serviceCredential()
            val serviceCreateAudit = IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId("audit-service-create"),
                AuditAction.SERVICE_IDENTITY_CREATED,
                serviceIdentity.id.value
            ).copy(target = AuditTarget(AuditTargetType.SERVICE_IDENTITY, serviceIdentity.id.value))
            val serviceIdentityCreate = store.createServiceIdentity(
                CreateServiceIdentityCommand(serviceIdentity, initialServiceCredential, serviceCreateAudit)
            )
            assertIs<StoreResult.Success<ServiceIdentityCreationCommit>>(
                serviceIdentityCreate,
                "Service identity creation failed safely: $serviceIdentityCreate"
            )
            assertEquals(
                listOf(serviceIdentity),
                assertIs<StoreResult.Success<List<ServiceIdentity>>>(
                    store.listServiceIdentitiesForOrganization(organization.id)
                ).value
            )
            val rotatedServiceAt = IdentityFixtures.instant(5_000)
            val replacementServiceCredential = IdentityFixtures.serviceCredential(
                id = IdentityFixtures.serviceCredentialId("service-credential-2")
            ).copy(
                createdAt = rotatedServiceAt,
                expiresAt = IdentityFixtures.instant(86_405_000)
            )
            val serviceRotation = assertIs<StoreResult.Success<ServiceCredentialRotationCommit>>(
                store.rotateServiceCredential(
                    RotateServiceCredentialCommand(
                        initialServiceCredential.id,
                        initialServiceCredential.version,
                        replacementServiceCredential,
                        rotatedServiceAt,
                        IdentityFixtures.auditEvent(
                            IdentityFixtures.auditEventId("audit-service-rotate"),
                            AuditAction.SERVICE_CREDENTIAL_ROTATED,
                            initialServiceCredential.id.value
                        ).copy(occurredAt = rotatedServiceAt)
                    )
                )
            ).value
            assertEquals(rotatedServiceAt, serviceRotation.previous.rotatedAt)
            assertEquals(
                2,
                assertIs<StoreResult.Success<List<ServiceCredential>>>(
                    store.listServiceCredentialsForIdentity(serviceIdentity.id)
                ).value.size
            )
            val serviceRevokedAt = IdentityFixtures.instant(5_500)
            val revokedServiceIdentity = serviceIdentity.copy(
                state = ServiceIdentityState.REVOKED,
                version = 1,
                updatedAt = serviceRevokedAt,
                revokedAt = serviceRevokedAt
            )
            assertIs<StoreResult.Success<ServiceIdentity>>(
                store.mutateServiceIdentity(
                    MutateServiceIdentityCommand(
                        serviceIdentity.id,
                        serviceIdentity.version,
                        revokedServiceIdentity,
                        serviceRevokedAt,
                        IdentityFixtures.auditEvent(
                            IdentityFixtures.auditEventId("audit-service-revoke"),
                            AuditAction.SERVICE_IDENTITY_REVOKED,
                            serviceIdentity.id.value
                        ).copy(
                            target = AuditTarget(AuditTargetType.SERVICE_IDENTITY, serviceIdentity.id.value),
                            occurredAt = serviceRevokedAt
                        )
                    )
                )
            )
            assertTrue(
                assertIs<StoreResult.Success<List<ServiceCredential>>>(
                    store.listServiceCredentialsForIdentity(serviceIdentity.id)
                ).value.all { it.state == ServiceCredentialState.REVOKED }
            )

            val verifyRecoveryFlows: suspend () -> Unit = {
                val initialRecoveryCodes = (1..10).map { index ->
                    IdentityFixtures.recoveryCode(
                        id = IdentityFixtures.recoveryCodeId("recovery-initial-$index"),
                        userId = user.id,
                        generation = 0
                    )
                }
                assertIs<StoreResult.Success<RecoveryCodeReplacementCommit>>(
                    store.replaceRecoveryCodes(
                        ReplaceRecoveryCodesCommand(
                            user.id,
                            expectedGeneration = null,
                            newGeneration = 0,
                            codes = initialRecoveryCodes,
                            auditEvent = IdentityFixtures.auditEvent(
                                IdentityFixtures.auditEventId("audit-recovery-codes-initial"),
                                AuditAction.RECOVERY_CODES_REPLACED,
                                user.id.value
                            )
                        )
                    )
                )
                assertEquals(
                    initialRecoveryCodes.toSet(),
                    assertIs<StoreResult.Success<List<RecoveryCode>>>(
                        store.listRecoveryCodesForUser(user.id)
                    ).value.toSet()
                )

                val administrativeTicket = IdentityFixtures.challenge(
                    id = IdentityFixtures.challengeId("challenge-administrative-recovery"),
                    purpose = ChallengePurpose.ACCOUNT_RECOVERY,
                    userId = user.id
                )
                assertIs<StoreResult.Success<Challenge>>(
                    store.createChallenge(CreateChallengeCommand(administrativeTicket))
                )
                val ticketCreatedAudit = IdentityFixtures.auditEvent(
                    IdentityFixtures.auditEventId("audit-administrative-ticket-created"),
                    AuditAction.RECOVERY_ADMIN_TICKET_CREATED,
                    user.id.value
                )
                assertEquals(
                    ticketCreatedAudit,
                    assertIs<StoreResult.Success<AuditEvent>>(
                        store.appendAuditEvent(ticketCreatedAudit)
                    ).value
                )
                assertEquals(
                    IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
                    assertIs<StoreResult.Failure>(store.appendAuditEvent(ticketCreatedAudit)).error.code
                )
                val ticketActivatedAt = IdentityFixtures.instant(9_000)
                val ticketDeliveryAudit = IdentityFixtures.auditEvent(
                    IdentityFixtures.auditEventId("audit-administrative-ticket-delivered"),
                    AuditAction.RECOVERY_ADMIN_TICKET_DELIVERED,
                    user.id.value
                ).copy(occurredAt = ticketActivatedAt)
                val activatedTicket = assertIs<StoreResult.Success<AdministrativeRecoveryTicketActivationCommit>>(
                    store.activateAdministrativeRecoveryTicket(
                        ActivateAdministrativeRecoveryTicketCommand(
                            challengeId = administrativeTicket.id,
                            expectedChallengeVersion = administrativeTicket.version,
                            activatedAt = ticketActivatedAt,
                            auditEvent = ticketDeliveryAudit
                        )
                    )
                ).value.challenge
                assertEquals(ticketActivatedAt, activatedTicket.activatedAt)
                val redeemedAt = IdentityFixtures.instant(10_000)
                val administrativeRecoverySession = IdentityFixtures.session(
                    id = IdentityFixtures.sessionId("session-administrative-recovery"),
                    userId = user.id,
                    userSessionEpoch = 1,
                    assurance = AuthenticationAssurance.RECOVERY,
                    createdAt = redeemedAt
                )
                val redeemTicketCommand = RedeemAdministrativeRecoveryTicketCommand(
                    challengeId = administrativeTicket.id,
                    expectedChallengeVersion = activatedTicket.version,
                    redeemedAt = redeemedAt,
                    recoverySession = administrativeRecoverySession,
                    auditEvent = IdentityFixtures.auditEvent(
                        IdentityFixtures.auditEventId("audit-administrative-ticket-used"),
                        AuditAction.RECOVERY_ADMIN_TICKET_USED,
                        user.id.value
                    ).copy(occurredAt = redeemedAt)
                )
                val redeemedTicket = assertIs<StoreResult.Success<AdministrativeRecoveryTicketRedemptionCommit>>(
                    store.redeemAdministrativeRecoveryTicket(redeemTicketCommand)
                ).value
                assertEquals(ChallengeState.CONSUMED, redeemedTicket.challenge.state)
                assertEquals(administrativeRecoverySession, redeemedTicket.recoverySession)
                assertEquals(
                    IdentityStoreErrorCode.VERSION_CONFLICT,
                    assertIs<StoreResult.Failure>(
                        store.redeemAdministrativeRecoveryTicket(redeemTicketCommand)
                    ).error.code
                )

                val recoveryEnrollmentChallenge = IdentityFixtures.challenge(
                    id = IdentityFixtures.challengeId("challenge-recovery-enrollment"),
                    purpose = ChallengePurpose.WEBAUTHN_REGISTRATION,
                    userId = user.id
                )
                assertIs<StoreResult.Success<Challenge>>(
                    store.createChallenge(CreateChallengeCommand(recoveryEnrollmentChallenge))
                )
                val recoveryCompletedAt = IdentityFixtures.instant(11_000)
                val recoveredCredential = IdentityFixtures.credential(
                    id = IdentityFixtures.credentialId("credential-recovery-enrollment"),
                    userId = user.id
                ).copy(createdAt = recoveryCompletedAt, updatedAt = recoveryCompletedAt)
                val replacementRecoveryCodes = (1..10).map { index ->
                    IdentityFixtures.recoveryCode(
                        id = IdentityFixtures.recoveryCodeId("recovery-replacement-$index"),
                        userId = user.id,
                        generation = 1
                    ).copy(createdAt = recoveryCompletedAt)
                }
                val recoveryEnrollmentCommand = CompleteRecoveryEnrollmentCommand(
                    challengeId = recoveryEnrollmentChallenge.id,
                    expectedChallengeVersion = recoveryEnrollmentChallenge.version,
                    credential = recoveredCredential,
                    recoverySessionId = administrativeRecoverySession.id,
                    expectedRecoverySessionVersion = administrativeRecoverySession.version,
                    expectedUserVersion = 1,
                    expectedSessionEpoch = 1,
                    newSessionEpoch = 2,
                    expectedRecoveryGeneration = 0,
                    newRecoveryGeneration = 1,
                    replacementRecoveryCodes = replacementRecoveryCodes,
                    completedAt = recoveryCompletedAt,
                    auditEvent = IdentityFixtures.auditEvent(
                        IdentityFixtures.auditEventId("audit-recovery-enrollment-complete"),
                        AuditAction.RECOVERY_ENROLLMENT_COMPLETED,
                        user.id.value
                    ).copy(occurredAt = recoveryCompletedAt),
                    rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(
                        recoveryEnrollmentChallenge.id,
                        recoveryCompletedAt
                    )
                )
                val recoveryEnrollmentAttempt = assertIs<
                    StoreResult.Success<WebAuthnCeremonyAttemptCommit<RecoveryEnrollmentCommit>>
                >(
                    store.completeRecoveryEnrollment(recoveryEnrollmentCommand)
                ).value
                val recoveryEnrollment = requireNotNull(recoveryEnrollmentAttempt.completion)
                assertEquals(ChallengeState.CONSUMED, recoveryEnrollment.challenge.state)
                assertEquals(recoveredCredential, recoveryEnrollment.credential)
                assertEquals(listOf(administrativeRecoverySession.id), recoveryEnrollment.revokedSessionIds)
                assertEquals(2, recoveryEnrollment.user.sessionEpoch)
                assertEquals(1, recoveryEnrollment.recoveryGeneration)
                assertEquals(replacementRecoveryCodes, recoveryEnrollment.recoveryCodes)
                assertEquals(
                    SessionState.REVOKED,
                    assertIs<StoreResult.Success<IdentitySession?>>(
                        store.findSession(administrativeRecoverySession.id)
                    ).value?.state
                )
                val storedRecoveryCodes = assertIs<StoreResult.Success<List<RecoveryCode>>>(
                    store.listRecoveryCodesForUser(user.id)
                ).value
                assertEquals(20, storedRecoveryCodes.size)
                assertEquals(10, storedRecoveryCodes.count {
                    it.generation == 0L && it.state == RecoveryCodeState.REVOKED
                })
                assertEquals(replacementRecoveryCodes.toSet(), storedRecoveryCodes.filter {
                    it.generation == 1L && it.state == RecoveryCodeState.ACTIVE
                }.toSet())
                assertEquals(
                    IdentityStoreErrorCode.VERSION_CONFLICT,
                    assertIs<StoreResult.Failure>(
                        store.completeRecoveryEnrollment(recoveryEnrollmentCommand)
                    ).error.code
                )
                }
            verifyRecoveryFlows()
            verifyDeviceGrantAndTokenLifecycle(store, user, organization, demotedOwner)

            verifyScimBatchAtomicity(
                store = store,
                client = pool,
                wireJson = wireJson,
                user = user,
                secondUser = secondUser,
                organization = organization,
                demotedOwner = demotedOwner,
                secondOwner = secondOwner,
                otherOrganizationId = IdentityFixtures.organizationId("organization-bootstrap")
            )

            val wrongConfig = PostgresqlIdentityConfig(
                environment = IdentityEnvironment.DEVELOPMENT,
                namespace = "aether_development"
            )
            val wrongStore = PostgresqlIdentityStore(
                wrongConfig,
                VertxPostgresqlRpcTransport(wrongConfig, pool)
            )
            assertEquals(
                IdentityStoreErrorCode.INTERNAL,
                assertIs<StoreResult.Failure>(wrongStore.initialize()).error.code
            )
        } finally {
            pool.close().coAwait()
            vertx.close().coAwait()
            withContext(Dispatchers.IO) { postgres.stop() }
        }
    }

    @Test
    fun identitySessionTouchIsAtomicAndAuditFree() = runBlocking {
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("aether_identity_touch_test")
            withUsername("aether")
            withPassword("aether-test-password")
        }
        withContext(Dispatchers.IO) { postgres.start() }
        val vertx = Vertx.vertx()
        val pool = PgBuilder.pool()
            .with(PoolOptions().setMaxSize(4))
            .connectingTo(
                PgConnectOptions()
                    .setHost(postgres.host)
                    .setPort(postgres.firstMappedPort)
                    .setDatabase(postgres.databaseName)
                    .setUser(postgres.username)
                    .setPassword(postgres.password)
            )
            .using(vertx)
            .build()
        try {
            val migrated = PostgresqlMigrationRunner(pool).migrate()
            assertEquals(11, assertIs<StoreResult.Success<PostgresqlMigrationReport>>(migrated).value.version)
            val config = PostgresqlIdentityConfig(IdentityEnvironment.TEST, "aether_touch_test")
            provisionEnvironment(pool, IdentityEnvironment.TEST, "aether_touch_test")
            val store = PostgresqlIdentityStore(config, VertxPostgresqlRpcTransport(config, pool))
            assertIs<StoreResult.Success<Unit>>(store.initialize())
            val user = verifyConcurrentBootstrap(store, pool)
            verifyIdentitySessionTouch(store, pool, user)
        } finally {
            pool.close().coAwait()
            vertx.close().coAwait()
            withContext(Dispatchers.IO) { postgres.stop() }
        }
    }

    private suspend fun verifyDeviceGrantAndTokenLifecycle(
        store: PostgresqlIdentityStore,
        user: User,
        organization: Organization,
        membership: Membership
    ) {
        val pendingGrant = IdentityFixtures.deviceGrant()
        assertIs<StoreResult.Success<DeviceGrant>>(
            store.compareAndSetDeviceGrant(
                CompareAndSetDeviceGrantCommand(
                    null,
                    pendingGrant,
                    IdentityFixtures.auditEvent(
                        IdentityFixtures.auditEventId("audit-device-start"),
                        AuditAction.DEVICE_GRANT_CHANGED,
                        pendingGrant.id.value
                    )
                )
            )
        )
        assertEquals(
            pendingGrant,
            assertIs<StoreResult.Success<DeviceGrant?>>(
                store.findDeviceGrantByDeviceCodeDigest(pendingGrant.deviceCodeDigest)
            ).value
        )
        assertEquals(
            pendingGrant,
            assertIs<StoreResult.Success<DeviceGrant?>>(
                store.findDeviceGrantByUserCodeDigest(pendingGrant.userCodeDigest)
            ).value
        )
        val authorizedAt = IdentityFixtures.instant(6_000)
        val authorizedGrant = pendingGrant.copy(
            approvedCapabilities = pendingGrant.requestedCapabilities,
            state = DeviceGrantState.AUTHORIZED,
            userId = user.id,
            organizationId = organization.id,
            membershipId = membership.id,
            membershipVersion = membership.version,
            authorizedByUserId = user.id,
            version = pendingGrant.version + 1,
            authorizedAt = authorizedAt
        )
        assertIs<StoreResult.Success<DeviceGrant>>(
            store.compareAndSetDeviceGrant(
                CompareAndSetDeviceGrantCommand(
                    pendingGrant.version,
                    authorizedGrant,
                    IdentityFixtures.auditEvent(
                        IdentityFixtures.auditEventId("audit-device-authorize"),
                        AuditAction.DEVICE_GRANT_CHANGED,
                        authorizedGrant.id.value
                    ).copy(occurredAt = authorizedAt)
                )
            )
        )
        val exchangedAt = IdentityFixtures.instant(7_000)
        val family = DeviceTokenFamily(
            id = IdentityFixtures.deviceTokenFamilyId("device-family-1"),
            deviceGrantId = authorizedGrant.id,
            clientId = authorizedGrant.clientId,
            userId = user.id,
            organizationId = organization.id,
            membershipId = membership.id,
            membershipVersion = membership.version,
            capabilities = authorizedGrant.approvedCapabilities,
            createdAt = exchangedAt,
            expiresAt = IdentityFixtures.instant(2_592_007_000)
        )
        val access = DeviceAccessToken(
            id = IdentityFixtures.deviceAccessTokenId("device-access-1"),
            familyId = family.id,
            publicSelector = "access_selector_1",
            secretDigest = IdentityFixtures.digest("device-access-1"),
            createdAt = exchangedAt,
            expiresAt = IdentityFixtures.instant(907_000)
        )
        val refresh = DeviceRefreshToken(
            id = IdentityFixtures.deviceRefreshTokenId("device-refresh-1"),
            familyId = family.id,
            publicSelector = "refresh_selector_1",
            secretDigest = IdentityFixtures.digest("device-refresh-1"),
            rotationCounter = 0,
            createdAt = exchangedAt,
            expiresAt = family.expiresAt
        )
        val exchangeCommand = ExchangeDeviceGrantCommand(
            authorizedGrant.id,
            authorizedGrant.version,
            family,
            access,
            refresh,
            exchangedAt,
            IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId("audit-device-exchange"),
                AuditAction.DEVICE_TOKEN_ISSUED,
                authorizedGrant.id.value
            ).copy(
                organizationId = organization.id,
                target = AuditTarget(AuditTargetType.DEVICE_GRANT, authorizedGrant.id.value),
                occurredAt = exchangedAt
            )
        )
        val exchangeRace = coroutineScope {
            listOf(
                async(Dispatchers.Default) { store.exchangeDeviceGrant(exchangeCommand) },
                async(Dispatchers.Default) { store.exchangeDeviceGrant(exchangeCommand) }
            ).awaitAll()
        }
        assertEquals(1, exchangeRace.count { it is StoreResult.Success })
        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            assertIs<StoreResult.Failure>(exchangeRace.single { it is StoreResult.Failure }).error.code
        )
        assertEquals(
            family,
            assertIs<StoreResult.Success<DeviceTokenFamily?>>(store.findDeviceTokenFamily(family.id)).value
        )
        assertEquals(
            access,
            assertIs<StoreResult.Success<DeviceAccessToken?>>(
                store.findDeviceAccessTokenBySelector(access.publicSelector)
            ).value
        )
        val rotatedAt = IdentityFixtures.instant(8_000)
        val replacementAccess = DeviceAccessToken(
            id = IdentityFixtures.deviceAccessTokenId("device-access-2"),
            familyId = family.id,
            publicSelector = "access_selector_2",
            secretDigest = IdentityFixtures.digest("device-access-2"),
            createdAt = rotatedAt,
            expiresAt = IdentityFixtures.instant(908_000)
        )
        val replacementRefresh = DeviceRefreshToken(
            id = IdentityFixtures.deviceRefreshTokenId("device-refresh-2"),
            familyId = family.id,
            publicSelector = "refresh_selector_2",
            secretDigest = IdentityFixtures.digest("device-refresh-2"),
            rotationCounter = 1,
            createdAt = rotatedAt,
            expiresAt = family.expiresAt
        )
        val rotation = assertIs<StoreResult.Success<DeviceTokenRotationCommit>>(
            store.rotateDeviceRefreshToken(
                RotateDeviceRefreshTokenCommand(
                    refresh.id,
                    refresh.version,
                    family.version,
                    replacementAccess,
                    replacementRefresh,
                    rotatedAt,
                    IdentityFixtures.auditEvent(
                        IdentityFixtures.auditEventId("audit-device-refresh"),
                        AuditAction.DEVICE_TOKEN_REFRESHED,
                        family.deviceGrantId.value
                    ).copy(
                        organizationId = organization.id,
                        target = AuditTarget(AuditTargetType.DEVICE_GRANT, family.deviceGrantId.value),
                        occurredAt = rotatedAt
                    )
                )
            )
        ).value
        assertEquals(DeviceRefreshTokenState.ROTATED, rotation.previousRefreshToken.state)
        assertEquals(
            DeviceRefreshTokenState.ROTATED,
            assertIs<StoreResult.Success<DeviceRefreshToken?>>(
                store.findDeviceRefreshTokenBySelector(refresh.publicSelector)
            ).value?.state
        )
        val familyRevocation = assertIs<StoreResult.Success<DeviceTokenFamilyRevocationCommit>>(
            store.revokeDeviceTokenFamily(
                RevokeDeviceTokenFamilyCommand(
                    family.id,
                    family.version,
                    IdentityFixtures.instant(9_000),
                    "refresh_replay",
                    replayDetected = true,
                    auditEvent = IdentityFixtures.auditEvent(
                        IdentityFixtures.auditEventId("audit-device-replay"),
                        AuditAction.DEVICE_TOKEN_REPLAY_DETECTED,
                        family.deviceGrantId.value
                    ).copy(
                        organizationId = organization.id,
                        target = AuditTarget(AuditTargetType.DEVICE_GRANT, family.deviceGrantId.value),
                        occurredAt = IdentityFixtures.instant(9_000)
                    )
                )
            )
        ).value
        assertEquals(DeviceTokenFamilyState.REVOKED, familyRevocation.family.state)
        assertEquals(listOf(access.id, replacementAccess.id), familyRevocation.revokedAccessTokenIds)
        assertEquals(listOf(replacementRefresh.id), familyRevocation.revokedRefreshTokenIds)
        assertEquals(
            DeviceAccessTokenState.REVOKED,
            assertIs<StoreResult.Success<DeviceAccessToken?>>(
                store.findDeviceAccessTokenBySelector(replacementAccess.publicSelector)
            ).value?.state
        )
    }

    private suspend fun verifyOrganizationAuditAndInvitationEnrollment(
        store: PostgresqlIdentityStore,
        organization: Organization,
        existingUser: User
    ) {
        verifyOrganizationAuditPagination(store, organization.id)
        val invitation = IdentityFixtures.invitation(
            id = IdentityFixtures.invitationId("invitation-enrollment-race"),
            organizationId = organization.id
        ).copy(email = EmailAddress("new-pg-invitee@example.test"))
        createInvitation(store, invitation, "audit-invitation-enrollment-race-create")
        val command = invitationEnrollmentCommand(invitation, "race", IdentityFixtures.instant(20_000))
        val raced = coroutineScope {
            listOf(
                async(Dispatchers.Default) { store.enrollInvitation(command) },
                async(Dispatchers.Default) { store.enrollInvitation(command) }
            ).awaitAll()
        }
        val commit = assertIs<StoreResult.Success<InvitationEnrollmentCommit>>(
            raced.single { it is StoreResult.Success }
        ).value
        assertEquals(InvitationState.ACCEPTED, commit.invitation.state)
        assertEquals(SessionAuthenticationMethod.INVITATION, commit.enrollmentSession.authenticationMethod)
        assertEquals(AuthenticationAssurance.RECOVERY, commit.enrollmentSession.assurance)
        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            assertIs<StoreResult.Failure>(raced.single { it is StoreResult.Failure }).error.code
        )
        assertEquals(commit.user, assertIs<StoreResult.Success<User?>>(store.findUser(commit.user.id)).value)
        assertEquals(
            commit.membership,
            assertIs<StoreResult.Success<Membership?>>(store.findMembership(commit.membership.id)).value
        )
        assertEquals(
            commit.enrollmentSession,
            assertIs<StoreResult.Success<IdentitySession?>>(store.findSession(commit.enrollmentSession.id)).value
        )

        val wrongTokenInvitation = IdentityFixtures.invitation(
            id = IdentityFixtures.invitationId("invitation-enrollment-wrong-token"),
            organizationId = organization.id
        ).copy(email = EmailAddress("wrong-token-pg@example.test"))
        createInvitation(store, wrongTokenInvitation, "audit-invitation-enrollment-wrong-token-create")
        val wrongTokenCommand = invitationEnrollmentCommand(
            wrongTokenInvitation,
            "wrong-token",
            IdentityFixtures.instant(21_000)
        ).copy(
            expectedTokenDigest = wrongTokenInvitation.tokenDigest.copy(encoded = "wrong-token-digest")
        )
        assertEquals(
            IdentityStoreErrorCode.NOT_FOUND,
            assertIs<StoreResult.Failure>(store.enrollInvitation(wrongTokenCommand)).error.code
        )
        assertInvitationEnrollmentRolledBack(store, wrongTokenInvitation, wrongTokenCommand)

        val expiredInvitation = IdentityFixtures.invitation(
            id = IdentityFixtures.invitationId("invitation-enrollment-expired"),
            organizationId = organization.id
        ).copy(email = EmailAddress("expired-pg@example.test"))
        createInvitation(store, expiredInvitation, "audit-invitation-enrollment-expired-create")
        val expiredCommand = invitationEnrollmentCommand(
            expiredInvitation,
            "expired",
            expiredInvitation.expiresAt
        )
        assertEquals(
            IdentityStoreErrorCode.INVALID_TRANSITION,
            assertIs<StoreResult.Failure>(store.enrollInvitation(expiredCommand)).error.code
        )
        assertInvitationEnrollmentRolledBack(store, expiredInvitation, expiredCommand)

        val duplicateEmailInvitation = IdentityFixtures.invitation(
            id = IdentityFixtures.invitationId("invitation-enrollment-duplicate-email"),
            organizationId = organization.id
        ).copy(email = requireNotNull(existingUser.primaryEmail))
        createInvitation(store, duplicateEmailInvitation, "audit-invitation-enrollment-duplicate-email-create")
        val duplicateEmailCommand = invitationEnrollmentCommand(
            duplicateEmailInvitation,
            "duplicate-email",
            IdentityFixtures.instant(22_000)
        )
        assertEquals(
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            assertIs<StoreResult.Failure>(store.enrollInvitation(duplicateEmailCommand)).error.code
        )
        assertInvitationEnrollmentRolledBack(store, duplicateEmailInvitation, duplicateEmailCommand)
    }

    private suspend fun createInvitation(
        store: PostgresqlIdentityStore,
        invitation: Invitation,
        auditId: String
    ) {
        val audit = IdentityFixtures.auditEvent(
            IdentityFixtures.auditEventId(auditId),
            AuditAction.INVITATION_CREATED,
            invitation.id.value
        ).copy(
            organizationId = invitation.organizationId,
            target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value)
        )
        assertEquals(
            invitation,
            assertIs<StoreResult.Success<Invitation>>(
                store.createInvitation(CreateInvitationCommand(invitation, audit))
            ).value
        )
    }

    private fun invitationEnrollmentCommand(
        invitation: Invitation,
        suffix: String,
        enrolledAt: kotlin.time.Instant
    ): EnrollInvitationCommand {
        val user = IdentityFixtures.user(IdentityFixtures.userId("user-invitation-$suffix")).copy(
            primaryEmail = invitation.email,
            createdAt = enrolledAt,
            updatedAt = enrolledAt,
            activatedAt = enrolledAt
        )
        val membership = IdentityFixtures.membership(
            id = IdentityFixtures.membershipId("membership-invitation-$suffix"),
            organizationId = invitation.organizationId,
            userId = user.id,
            role = invitation.role
        ).copy(createdAt = enrolledAt, updatedAt = enrolledAt)
        val expiresAt = kotlin.time.Instant.fromEpochMilliseconds(enrolledAt.toEpochMilliseconds() + 900_000)
        val session = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("session-invitation-$suffix"),
            userId = user.id,
            assurance = AuthenticationAssurance.RECOVERY,
            authenticationMethod = SessionAuthenticationMethod.INVITATION,
            createdAt = enrolledAt
        ).copy(idleExpiresAt = expiresAt, absoluteExpiresAt = expiresAt)
        val audit = IdentityFixtures.auditEvent(
            IdentityFixtures.auditEventId("audit-invitation-enrollment-$suffix"),
            AuditAction.INVITATION_ACCEPTED,
            invitation.id.value
        ).copy(
            organizationId = invitation.organizationId,
            target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value),
            occurredAt = enrolledAt
        )
        return EnrollInvitationCommand(
            invitationId = invitation.id,
            expectedInvitationVersion = invitation.version,
            expectedTokenDigest = invitation.tokenDigest,
            user = user,
            membership = membership,
            enrollmentSession = session,
            enrolledAt = enrolledAt,
            auditEvent = audit
        )
    }

    private suspend fun assertInvitationEnrollmentRolledBack(
        store: PostgresqlIdentityStore,
        invitation: Invitation,
        command: EnrollInvitationCommand
    ) {
        assertEquals(
            InvitationState.PENDING,
            assertIs<StoreResult.Success<Invitation?>>(store.findInvitation(invitation.id)).value?.state
        )
        assertEquals(null, assertIs<StoreResult.Success<User?>>(store.findUser(command.user.id)).value)
        assertEquals(
            null,
            assertIs<StoreResult.Success<Membership?>>(store.findMembership(command.membership.id)).value
        )
        assertEquals(
            null,
            assertIs<StoreResult.Success<IdentitySession?>>(store.findSession(command.enrollmentSession.id)).value
        )
    }

    private suspend fun verifyScimBatchAtomicity(
        store: PostgresqlIdentityStore,
        client: SqlClient,
        wireJson: kotlinx.serialization.json.Json,
        user: User,
        secondUser: User,
        organization: Organization,
        demotedOwner: Membership,
        secondOwner: Membership,
        otherOrganizationId: OrganizationId
    ) {
        val provider = "test-scim-batch"
        val changedAt = IdentityFixtures.instant(14_000)
        val removedSecondOwner = secondOwner.copy(
            state = MembershipState.REMOVED,
            version = secondOwner.version + 1,
            updatedAt = changedAt,
            removedAt = changedAt
        )
        val promotedOwner = demotedOwner.copy(
            role = OrganizationRole.OWNER,
            version = demotedOwner.version + 1,
            updatedAt = changedAt
        )
        val removeSecondOwner = scimMembershipCommand(
            operationId = "scim-batch-remove-second-owner",
            provider = provider,
            type = ScimMutationType.REMOVE_MEMBERSHIP,
            membership = removedSecondOwner,
            auditId = "audit-scim-batch-remove-second-owner",
            occurredAt = changedAt
        )
        val promoteFirstOwner = scimMembershipCommand(
            operationId = "scim-batch-promote-first-owner",
            provider = provider,
            type = ScimMutationType.UPSERT_MEMBERSHIP,
            membership = promotedOwner,
            auditId = "audit-scim-batch-promote-first-owner",
            occurredAt = changedAt
        )
        val group = ScimGroup(
            id = "scim-group-publishers",
            organizationId = organization.id,
            provider = provider,
            externalId = "external-publishers",
            displayName = "Publishers",
            memberUserIds = setOf(user.id, secondUser.id),
            version = 1,
            createdAt = changedAt,
            updatedAt = changedAt
        )
        val groupAudit = IdentityFixtures.auditEvent(
            IdentityFixtures.auditEventId("audit-scim-group-create"),
            AuditAction.SCIM_GROUP_CHANGED,
            group.id
        ).copy(
            organizationId = organization.id,
            target = AuditTarget(AuditTargetType.SCIM_GROUP, group.id),
            occurredAt = changedAt
        )
        val groupCreateCommand = ApplyScimBatchCommand(
            operationId = IdentityFixtures.scimOperationId("scim-batch-group-create"),
            organizationId = organization.id,
            provider = provider,
            // This order temporarily removes the only owner; only the final state is authoritative.
            mutations = listOf(removeSecondOwner, promoteFirstOwner),
            group = group,
            expectedGroupVersion = 0,
            auditEvent = groupAudit
        )
        val groupCreate = assertIs<StoreResult.Success<ScimBatchCommit>>(
            store.applyScimBatch(groupCreateCommand)
        ).value
        assertEquals(false, groupCreate.alreadyApplied)
        assertEquals(group, groupCreate.group)
        assertTrue(groupCreate.mutationCommits.all { !it.alreadyApplied && it.auditEvent != null })
        assertEquals(
            MembershipState.REMOVED,
            assertIs<StoreResult.Success<Membership?>>(store.findMembership(secondOwner.id)).value?.state
        )
        assertEquals(
            OrganizationRole.OWNER,
            assertIs<StoreResult.Success<Membership?>>(store.findMembership(demotedOwner.id)).value?.role
        )
        assertEquals(
            group,
            assertIs<StoreResult.Success<ScimGroup?>>(
                store.findScimGroup(provider, organization.id, group.id)
            ).value
        )
        assertEquals(
            null,
            assertIs<StoreResult.Success<ScimGroup?>>(
                store.findScimGroup("other-scim-provider", organization.id, group.id)
            ).value
        )

        val groupReplay = assertIs<StoreResult.Success<ScimBatchCommit>>(
            store.applyScimBatch(groupCreateCommand)
        ).value
        assertTrue(groupReplay.alreadyApplied)
        assertEquals(null, groupReplay.auditEvent)
        assertTrue(groupReplay.mutationCommits.all { it.alreadyApplied && it.auditEvent == null })
        val changedFingerprint = groupCreateCommand.copy(
            auditEvent = groupAudit.copy(reasonCode = "changed_command")
        )
        assertEquals(
            IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT,
            assertIs<StoreResult.Failure>(store.applyScimBatch(changedFingerprint)).error.code
        )

        val updatedAt = IdentityFixtures.instant(14_500)
        val updatedGroup = group.copy(
            displayName = "Release Publishers",
            version = 2,
            updatedAt = updatedAt
        )
        val updateGroupCommand = ApplyScimBatchCommand(
            operationId = IdentityFixtures.scimOperationId("scim-batch-group-update"),
            organizationId = organization.id,
            provider = provider,
            group = updatedGroup,
            expectedGroupVersion = 1,
            auditEvent = IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId("audit-scim-group-update"),
                AuditAction.SCIM_GROUP_CHANGED,
                group.id
            ).copy(
                organizationId = organization.id,
                target = AuditTarget(AuditTargetType.SCIM_GROUP, group.id),
                occurredAt = updatedAt
            )
        )
        assertEquals(
            updatedGroup,
            assertIs<StoreResult.Success<ScimBatchCommit>>(
                store.applyScimBatch(updateGroupCommand)
            ).value.group
        )
        val staleAt = IdentityFixtures.instant(15_000)
        val staleGroupCommand = ApplyScimBatchCommand(
            operationId = IdentityFixtures.scimOperationId("scim-batch-group-stale-update"),
            organizationId = organization.id,
            provider = provider,
            group = updatedGroup.copy(displayName = "Stale Publishers", updatedAt = staleAt),
            expectedGroupVersion = 1,
            auditEvent = IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId("audit-scim-group-stale-update"),
                AuditAction.SCIM_GROUP_CHANGED,
                group.id
            ).copy(
                organizationId = organization.id,
                target = AuditTarget(AuditTargetType.SCIM_GROUP, group.id),
                occurredAt = staleAt
            )
        )
        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            assertIs<StoreResult.Failure>(store.applyScimBatch(staleGroupCommand)).error.code
        )

        val rollbackAt = IdentityFixtures.instant(15_500)
        val rollbackUser = IdentityFixtures.user(IdentityFixtures.userId("user-scim-batch-rollback"))
        val createRollbackUser = ApplyScimMutationCommand(
            ScimMutation(
                operationId = IdentityFixtures.scimOperationId("scim-batch-create-rollback-user"),
                provider = provider,
                type = ScimMutationType.UPSERT_USER,
                externalSubject = ExternalSubject("rollback-user"),
                user = rollbackUser,
                occurredAt = rollbackAt
            ),
            IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId("audit-scim-batch-create-rollback-user"),
                AuditAction.SCIM_MUTATION_APPLIED,
                rollbackUser.id.value
            ).copy(organizationId = organization.id, occurredAt = rollbackAt)
        )
        val removeLastOwner = scimMembershipCommand(
            operationId = "scim-batch-remove-last-owner",
            provider = provider,
            type = ScimMutationType.REMOVE_MEMBERSHIP,
            membership = promotedOwner.copy(
                state = MembershipState.REMOVED,
                version = promotedOwner.version + 1,
                updatedAt = rollbackAt,
                removedAt = rollbackAt
            ),
            auditId = "audit-scim-batch-remove-last-owner",
            occurredAt = rollbackAt
        )
        val rejectedLastOwnerBatch = ApplyScimBatchCommand(
            operationId = IdentityFixtures.scimOperationId("scim-batch-last-owner-rejected"),
            organizationId = organization.id,
            provider = provider,
            mutations = listOf(createRollbackUser, removeLastOwner),
            auditEvent = IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId("audit-scim-batch-last-owner-rejected"),
                AuditAction.SCIM_MUTATION_APPLIED,
                organization.id.value
            ).copy(organizationId = organization.id, occurredAt = rollbackAt)
        )
        assertEquals(
            IdentityStoreErrorCode.LAST_OWNER,
            assertIs<StoreResult.Failure>(store.applyScimBatch(rejectedLastOwnerBatch)).error.code
        )
        assertEquals(
            null,
            assertIs<StoreResult.Success<User?>>(store.findUser(rollbackUser.id)).value
        )
        assertEquals(
            promotedOwner,
            assertIs<StoreResult.Success<Membership?>>(store.findMembership(promotedOwner.id)).value
        )

        val sessionUser = assertIs<StoreResult.Success<User?>>(store.findUser(user.id)).value
            ?: error("SCIM revocation user is missing")
        val targetProviderKey = IdentityFixtures.federationProviderStorageKey(
            FederationProviderKind.OIDC,
            "postgresql-scim-target"
        )
        val targetProviderLease = assertIs<StoreResult.Success<FederationProviderLease>>(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organizationId = organization.id,
                    kind = FederationProviderKind.OIDC,
                    providerId = "scim-target",
                    storageKey = targetProviderKey,
                    acquiredAt = IdentityFixtures.instant(15_600)
                )
            )
        ).value
        val controlProviderKey = IdentityFixtures.federationProviderStorageKey(
            FederationProviderKind.OIDC,
            "postgresql-scim-control"
        )
        val controlProviderLease = assertIs<StoreResult.Success<FederationProviderLease>>(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organizationId = otherOrganizationId,
                    kind = FederationProviderKind.OIDC,
                    providerId = "scim-control",
                    storageKey = controlProviderKey,
                    acquiredAt = IdentityFixtures.instant(15_610)
                )
            )
        ).value
        val targetSession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("session-federated-other-provider"),
            userId = user.id,
            userSessionEpoch = sessionUser.sessionEpoch,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = organization.id,
            federationProviderKey = targetProviderKey,
            federationProviderSessionEpoch = targetProviderLease.sessionEpoch,
            externalIdentityId = IdentityFixtures.externalIdentityId("scim-target-session"),
            createdAt = IdentityFixtures.instant(15_700)
        )
        val otherTenantSession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("session-federated-other-tenant"),
            userId = user.id,
            userSessionEpoch = sessionUser.sessionEpoch,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = otherOrganizationId,
            federationProviderKey = controlProviderKey,
            federationProviderSessionEpoch = controlProviderLease.sessionEpoch,
            externalIdentityId = IdentityFixtures.externalIdentityId("scim-control-session"),
            createdAt = IdentityFixtures.instant(15_710)
        )
        val passkeyControlSession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("session-federated-passkey-control"),
            userId = user.id,
            userSessionEpoch = sessionUser.sessionEpoch,
            createdAt = IdentityFixtures.instant(15_720)
        )
        listOf(targetSession, otherTenantSession, passkeyControlSession).forEachIndexed { index, session ->
            assertIs<StoreResult.Success<IdentitySession>>(
                store.createSession(
                    CreateSessionCommand(
                        session = session,
                        auditEvent = IdentityFixtures.auditEvent(
                            IdentityFixtures.auditEventId("audit-scim-revocation-session-$index"),
                            AuditAction.SESSION_CREATED,
                            session.id.value
                        ).copy(
                            organizationId = session.federationOrganizationId,
                            target = AuditTarget(AuditTargetType.SESSION, session.id.value),
                            occurredAt = session.createdAt
                        )
                    )
                )
            )
        }

        val deviceCreatedAt = IdentityFixtures.instant(16_000)
        val targetFamily = DeviceTokenFamily(
            id = IdentityFixtures.deviceTokenFamilyId("scim-device-family-target"),
            deviceGrantId = IdentityFixtures.deviceGrantId(),
            clientId = "aether-scim-test-client",
            userId = user.id,
            organizationId = organization.id,
            membershipId = promotedOwner.id,
            membershipVersion = promotedOwner.version,
            capabilities = setOf(Capability.CONTENT_READ),
            createdAt = deviceCreatedAt,
            expiresAt = IdentityFixtures.instant(2_592_016_000)
        )
        val targetAccess = DeviceAccessToken(
            id = IdentityFixtures.deviceAccessTokenId("scim-device-access-target"),
            familyId = targetFamily.id,
            publicSelector = "scim_access_target",
            secretDigest = IdentityFixtures.digest("scim-device-access-target"),
            createdAt = deviceCreatedAt,
            expiresAt = IdentityFixtures.instant(916_000)
        )
        val targetRefresh = DeviceRefreshToken(
            id = IdentityFixtures.deviceRefreshTokenId("scim-device-refresh-target"),
            familyId = targetFamily.id,
            publicSelector = "scim_refresh_target",
            secretDigest = IdentityFixtures.digest("scim-device-refresh-target"),
            rotationCounter = 0,
            createdAt = deviceCreatedAt,
            expiresAt = targetFamily.expiresAt
        )
        val controlFamily = targetFamily.copy(
            id = IdentityFixtures.deviceTokenFamilyId("scim-device-family-control"),
            organizationId = otherOrganizationId,
            membershipId = IdentityFixtures.membershipId("membership-bootstrap-owner"),
            membershipVersion = 0
        )
        val controlAccess = targetAccess.copy(
            id = IdentityFixtures.deviceAccessTokenId("scim-device-access-control"),
            familyId = controlFamily.id,
            publicSelector = "scim_access_control",
            secretDigest = IdentityFixtures.digest("scim-device-access-control")
        )
        val controlRefresh = targetRefresh.copy(
            id = IdentityFixtures.deviceRefreshTokenId("scim-device-refresh-control"),
            familyId = controlFamily.id,
            publicSelector = "scim_refresh_control",
            secretDigest = IdentityFixtures.digest("scim-device-refresh-control")
        )
        insertScimDeviceArtifacts(client, wireJson, targetFamily, targetAccess, targetRefresh)
        insertScimDeviceArtifacts(client, wireJson, controlFamily, controlAccess, controlRefresh)

        val revokedAt = IdentityFixtures.instant(17_000)
        val revocationCommand = ApplyScimBatchCommand(
            operationId = IdentityFixtures.scimOperationId("scim-batch-tenant-revocation"),
            organizationId = organization.id,
            provider = provider,
            revocations = listOf(
                ScimTenantRevocation(
                    userId = user.id,
                    reasonCode = "scim_membership_deprovisioned"
                )
            ),
            auditEvent = IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId("audit-scim-batch-tenant-revocation"),
                AuditAction.SCIM_MUTATION_APPLIED,
                user.id.value
            ).copy(organizationId = organization.id, occurredAt = revokedAt)
        )
        val revocation = assertIs<StoreResult.Success<ScimBatchCommit>>(
            store.applyScimBatch(revocationCommand)
        ).value
        assertEquals(listOf(targetSession.id), revocation.revokedSessionIds)
        assertEquals(listOf(targetFamily.id), revocation.revokedDeviceTokenFamilyIds)
        assertEquals(listOf(targetAccess.id), revocation.revokedDeviceAccessTokenIds)
        assertEquals(listOf(targetRefresh.id), revocation.revokedDeviceRefreshTokenIds)
        assertEquals(
            SessionState.ACTIVE,
            assertIs<StoreResult.Success<IdentitySession?>>(
                store.findSession(otherTenantSession.id)
            ).value?.state
        )
        assertEquals(
            SessionState.ACTIVE,
            assertIs<StoreResult.Success<IdentitySession?>>(
                store.findSession(passkeyControlSession.id)
            ).value?.state
        )
        assertEquals(
            DeviceTokenFamilyState.REVOKED,
            assertIs<StoreResult.Success<DeviceTokenFamily?>>(store.findDeviceTokenFamily(targetFamily.id)).value?.state
        )
        assertEquals(
            DeviceTokenFamilyState.ACTIVE,
            assertIs<StoreResult.Success<DeviceTokenFamily?>>(store.findDeviceTokenFamily(controlFamily.id)).value?.state
        )
        assertEquals(
            DeviceAccessTokenState.REVOKED,
            assertIs<StoreResult.Success<DeviceAccessToken?>>(
                store.findDeviceAccessTokenBySelector(targetAccess.publicSelector)
            ).value?.state
        )
        assertEquals(
            DeviceAccessTokenState.ACTIVE,
            assertIs<StoreResult.Success<DeviceAccessToken?>>(
                store.findDeviceAccessTokenBySelector(controlAccess.publicSelector)
            ).value?.state
        )
        val revocationReplay = assertIs<StoreResult.Success<ScimBatchCommit>>(
            store.applyScimBatch(revocationCommand)
        ).value
        assertTrue(revocationReplay.alreadyApplied)
        assertEquals(null, revocationReplay.auditEvent)
        assertEquals(revocation.revokedSessionIds, revocationReplay.revokedSessionIds)
        assertEquals(revocation.revokedDeviceTokenFamilyIds, revocationReplay.revokedDeviceTokenFamilyIds)
    }

    private fun scimMembershipCommand(
        operationId: String,
        provider: String,
        type: ScimMutationType,
        membership: Membership,
        auditId: String,
        occurredAt: kotlin.time.Instant
    ): ApplyScimMutationCommand = ApplyScimMutationCommand(
        mutation = ScimMutation(
            operationId = IdentityFixtures.scimOperationId(operationId),
            provider = provider,
            type = type,
            externalSubject = ExternalSubject("subject-$operationId"),
            membership = membership,
            occurredAt = occurredAt
        ),
        auditEvent = IdentityFixtures.auditEvent(
            IdentityFixtures.auditEventId(auditId),
            AuditAction.SCIM_MUTATION_APPLIED,
            membership.id.value
        ).copy(organizationId = membership.organizationId, occurredAt = occurredAt)
    )

    private suspend fun insertScimDeviceArtifacts(
        client: SqlClient,
        wireJson: kotlinx.serialization.json.Json,
        family: DeviceTokenFamily,
        access: DeviceAccessToken,
        refresh: DeviceRefreshToken
    ) {
        val inserts = listOf(
            "insert_device_token_family" to wireJson.encodeToString(DeviceTokenFamily.serializer(), family),
            "insert_device_access_token" to wireJson.encodeToString(DeviceAccessToken.serializer(), access),
            "insert_device_refresh_token" to wireJson.encodeToString(DeviceRefreshToken.serializer(), refresh)
        )
        inserts.forEach { (function, document) ->
            client.preparedQuery("SELECT aether_identity.$function(\$1::jsonb)")
                .execute(Tuple.of(VertxJsonObject(document))).coAwait()
        }
    }


    private suspend fun verifyOrganizationAuditPagination(
        store: PostgresqlIdentityStore,
        organizationId: OrganizationId
    ) {
        val otherOrganizationId = IdentityFixtures.organizationId("organization-bootstrap")
        suspend fun append(id: String, scope: OrganizationId, offset: Long) {
            val event = IdentityFixtures.auditEvent(
                IdentityFixtures.auditEventId(id),
                AuditAction.ORGANIZATION_CHANGED,
                scope.value
            ).copy(
                organizationId = scope,
                target = AuditTarget(AuditTargetType.ORGANIZATION, scope.value),
                occurredAt = IdentityFixtures.instant(offset)
            )
            assertEquals(event, assertIs<StoreResult.Success<AuditEvent>>(store.appendAuditEvent(event)).value)
        }
        append("audit-pg-page-3000", organizationId, 3_000)
        append("audit-pg-page-2000-b", organizationId, 2_000)
        append("audit-pg-page-2000-a", organizationId, 2_000)
        append("audit-pg-page-1000", organizationId, 1_000)
        append("audit-pg-page-other", otherOrganizationId, 4_000)

        val first = assertIs<StoreResult.Success<OrganizationAuditEventPage>>(
            store.listAuditEventsForOrganization(
                OrganizationAuditEventPageRequest(organizationId, limit = 2)
            )
        ).value
        assertEquals(
            listOf(
                IdentityFixtures.auditEventId("audit-pg-page-3000").value,
                IdentityFixtures.auditEventId("audit-pg-page-2000-b").value
            ),
            first.events.map { it.id.value }
        )
        assertEquals(first.events.last().toOrganizationAuditCursor(), first.nextCursor)

        append("audit-pg-page-4000-new", organizationId, 4_000)
        val second = assertIs<StoreResult.Success<OrganizationAuditEventPage>>(
            store.listAuditEventsForOrganization(
                OrganizationAuditEventPageRequest(organizationId, cursor = first.nextCursor, limit = 2)
            )
        ).value
        assertEquals(
            listOf(
                IdentityFixtures.auditEventId("audit-pg-page-2000-a").value,
                IdentityFixtures.auditEventId("audit-pg-page-1000").value
            ),
            second.events.map { it.id.value }
        )
        assertEquals(null, second.nextCursor)
    }

    private suspend fun verifyConcurrentBootstrap(
        store: PostgresqlIdentityStore,
        client: SqlClient
    ): User = coroutineScope {
        val user = IdentityFixtures.user()
        val organization = IdentityFixtures.organization(
            id = IdentityFixtures.organizationId("organization-bootstrap"),
            slug = "bootstrap-org"
        )
        val owner = IdentityFixtures.membership(
            id = IdentityFixtures.membershipId("membership-bootstrap-owner"),
            organizationId = organization.id,
            userId = user.id
        )
        val enrollmentSession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("session-bootstrap-enrollment"),
            userId = user.id,
            assurance = AuthenticationAssurance.RECOVERY,
            authenticationMethod = SessionAuthenticationMethod.BOOTSTRAP
        )
        val command = BootstrapIdentityCommand(
            bootstrapSecretDigest = SecretDigest(
                algorithm = DigestAlgorithm.SHA256,
                encoded = "bootstrap-secret-receipt"
            ),
            user = user,
            organization = organization,
            ownerMembership = owner,
            enrollmentSession = enrollmentSession,
            auditEvent = IdentityFixtures.auditEvent(
                id = IdentityFixtures.auditEventId("audit-identity-bootstrap"),
                action = AuditAction.IDENTITY_BOOTSTRAPPED,
                targetId = user.id.value
            ).copy(organizationId = organization.id)
        )
        val race = listOf(
            async(Dispatchers.Default) { store.bootstrapIdentity(command) },
            async(Dispatchers.Default) { store.bootstrapIdentity(command) }
        ).awaitAll()
        val commit = assertIs<StoreResult.Success<BootstrapIdentityCommit>>(
            race.single { it is StoreResult.Success }
        ).value
        assertEquals(user, commit.user)
        assertEquals(organization, commit.organization)
        assertEquals(owner, commit.ownerMembership)
        assertEquals(enrollmentSession, commit.enrollmentSession)
        assertEquals(
            IdentityStoreErrorCode.ALREADY_EXISTS,
            assertIs<StoreResult.Failure>(race.single { it is StoreResult.Failure }).error.code
        )
        assertEquals(
            1L,
            client.query("SELECT COUNT(*) AS count FROM aether_identity.bootstrap_receipts")
                .execute().coAwait().iterator().next().getLong("count")
        )
        assertEquals(user, assertIs<StoreResult.Success<User?>>(store.findUser(user.id)).value)
        assertEquals(
            enrollmentSession,
            assertIs<StoreResult.Success<IdentitySession?>>(
                store.findSession(enrollmentSession.id)
            ).value
        )
        assertEquals(
            owner,
            assertIs<StoreResult.Success<Membership?>>(
                store.findMembershipForUser(user.id, organization.id)
            ).value
        )
        user
    }

    private suspend fun verifyFederationJitRaceLeavesNoOrphans(store: PostgresqlIdentityStore) = coroutineScope {
        val organizationId = IdentityFixtures.organizationId("organization-bootstrap")
        val kind = FederationProviderKind.OIDC
        val providerId = "jit-race"
        val storageKey = IdentityFixtures.federationProviderStorageKey(kind, providerId)
        val at = IdentityFixtures.instant(25_000)
        val lease = assertIs<StoreResult.Success<FederationProviderLease>>(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organizationId,
                    kind,
                    providerId,
                    storageKey,
                    at
                )
            )
        ).value
        val subject = ExternalSubject("postgresql-jit-race-subject")
        val commands = (1..2).map { contender ->
            val user = IdentityFixtures.user(IdentityFixtures.userId("postgresql-jit-race-$contender")).copy(
                primaryEmail = null,
                createdAt = at,
                updatedAt = at,
                activatedAt = at
            )
            val membership = IdentityFixtures.membership(
                id = IdentityFixtures.membershipId("postgresql-jit-race-$contender"),
                organizationId = organizationId,
                userId = user.id,
                role = OrganizationRole.VIEWER
            ).copy(createdAt = at, updatedAt = at)
            val identity = IdentityFixtures.externalIdentity(
                id = IdentityFixtures.externalIdentityId("postgresql-jit-race-$contender"),
                userId = user.id,
                provider = storageKey,
                subject = subject
            ).copy(createdAt = at, updatedAt = at)
            LinkExternalIdentityCommand(
                identity = identity,
                replayReceipt = IdentityFixtures.replayReceipt(
                    id = IdentityFixtures.replayReceiptId("postgresql-jit-race-$contender"),
                    provider = storageKey
                ).copy(receivedAt = at, expiresAt = IdentityFixtures.instant(625_000)),
                federationProviderLease = lease,
                auditEvent = IdentityFixtures.auditEvent(
                    IdentityFixtures.auditEventId("postgresql-jit-race-$contender"),
                    AuditAction.EXTERNAL_IDENTITY_LINKED,
                    identity.id.value
                ).copy(
                    organizationId = organizationId,
                    target = AuditTarget(AuditTargetType.EXTERNAL_IDENTITY, identity.id.value),
                    occurredAt = at
                ),
                jitProvisioning = FederationJitProvisioning(user, membership)
            )
        }
        val results = commands.map { command ->
            async(Dispatchers.Default) { store.linkExternalIdentity(command) }
        }.awaitAll()
        val winnerIndex = results.indexOfFirst { it is StoreResult.Success }
        val loserIndex = results.indexOfFirst { it is StoreResult.Failure }
        assertTrue(winnerIndex >= 0)
        assertTrue(loserIndex >= 0)
        assertEquals(1, results.count { it is StoreResult.Success })
        assertEquals(
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            assertIs<StoreResult.Failure>(results[loserIndex]).error.code
        )
        val winner = commands[winnerIndex].jitProvisioning!!
        val loser = commands[loserIndex].jitProvisioning!!
        assertEquals(
            winner.user,
            assertIs<StoreResult.Success<User?>>(store.findUser(winner.user.id)).value
        )
        assertEquals(
            winner.membership,
            assertIs<StoreResult.Success<Membership?>>(store.findMembership(winner.membership.id)).value
        )
        assertEquals(null, assertIs<StoreResult.Success<User?>>(store.findUser(loser.user.id)).value)
        assertEquals(
            null,
            assertIs<StoreResult.Success<Membership?>>(store.findMembership(loser.membership.id)).value
        )
    }

    private suspend fun verifyIdentitySessionTouch(
        store: PostgresqlIdentityStore,
        client: SqlClient,
        user: User
    ) {
        val createdAt = IdentityFixtures.instant(100_000)
        val session = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("session-idle-touch"),
            userId = user.id,
            userSessionEpoch = user.sessionEpoch,
            createdAt = createdAt
        )
        assertEquals(
            session,
            assertIs<StoreResult.Success<IdentitySession>>(
                store.createSession(
                    CreateSessionCommand(
                        session,
                        IdentityFixtures.auditEvent(
                            IdentityFixtures.auditEventId("audit-session-idle-touch-create"),
                            AuditAction.SESSION_CREATED,
                            session.id.value
                        ).copy(occurredAt = createdAt)
                    )
                )
            ).value
        )
        val auditCountBeforeTouches = client.query(
            "SELECT COUNT(*) AS count FROM aether_identity.audit_events"
        ).execute().coAwait().single().getLong("count")
        val touchedAt = IdentityFixtures.instant(160_000)
        val extendedIdleExpiresAt = IdentityFixtures.instant(3_760_000)
        val command = TouchIdentitySessionCommand(
            sessionId = session.id,
            expectedVersion = session.version,
            lastUsedAt = touchedAt,
            idleExpiresAt = extendedIdleExpiresAt
        )
        val raced = coroutineScope {
            listOf(
                async(Dispatchers.Default) { store.touchIdentitySession(command) },
                async(Dispatchers.Default) { store.touchIdentitySession(command) }
            ).awaitAll()
        }
        val touched = assertIs<StoreResult.Success<IdentitySession>>(
            raced.single { it is StoreResult.Success }
        ).value
        assertEquals(
            session.copy(
                version = 1,
                lastUsedAt = touchedAt,
                idleExpiresAt = extendedIdleExpiresAt
            ),
            touched
        )
        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            assertIs<StoreResult.Failure>(raced.single { it is StoreResult.Failure }).error.code
        )

        val row = client.preparedQuery(
            "SELECT version, " +
                "idle_expires_at = \$2::text::timestamptz AS idle_matches, " +
                "(document->>'lastUsedAt')::timestamptz = \$3::text::timestamptz AS last_used_matches, " +
                "(document->>'idleExpiresAt')::timestamptz = \$2::text::timestamptz AS document_idle_matches " +
                "FROM aether_identity.sessions WHERE id = \$1"
        ).execute(Tuple.of(session.id.value, extendedIdleExpiresAt.toString(), touchedAt.toString()))
            .coAwait().single()
        assertEquals(1L, row.getLong("version"))
        assertEquals(true, row.getBoolean("idle_matches"))
        assertEquals(true, row.getBoolean("last_used_matches"))
        assertEquals(true, row.getBoolean("document_idle_matches"))

        // Policy changes may shorten the next idle window; equal touch times are valid clock ties.
        val shortenedIdleExpiresAt = IdentityFixtures.instant(1_960_000)
        val shortened = assertIs<StoreResult.Success<IdentitySession>>(
            store.touchIdentitySession(
                TouchIdentitySessionCommand(
                    sessionId = session.id,
                    expectedVersion = touched.version,
                    lastUsedAt = touchedAt,
                    idleExpiresAt = shortenedIdleExpiresAt
                )
            )
        ).value
        assertEquals(2L, shortened.version)
        assertEquals(touchedAt, shortened.lastUsedAt)
        assertEquals(shortenedIdleExpiresAt, shortened.idleExpiresAt)
        assertEquals(
            auditCountBeforeTouches,
            client.query("SELECT COUNT(*) AS count FROM aether_identity.audit_events")
                .execute().coAwait().single().getLong("count")
        )

        assertEquals(
            IdentityStoreErrorCode.INVALID_TRANSITION,
            assertIs<StoreResult.Failure>(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        sessionId = session.id,
                        expectedVersion = shortened.version,
                        lastUsedAt = createdAt,
                        idleExpiresAt = shortenedIdleExpiresAt
                    )
                )
            ).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.INVALID_TRANSITION,
            assertIs<StoreResult.Failure>(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        sessionId = session.id,
                        expectedVersion = shortened.version,
                        lastUsedAt = touchedAt,
                        idleExpiresAt = kotlin.time.Instant.fromEpochMilliseconds(
                            session.absoluteExpiresAt.toEpochMilliseconds() + 1
                        )
                    )
                )
            ).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.SESSION_EXPIRED,
            assertIs<StoreResult.Failure>(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        sessionId = session.id,
                        expectedVersion = shortened.version,
                        lastUsedAt = shortenedIdleExpiresAt,
                        idleExpiresAt = shortenedIdleExpiresAt
                    )
                )
            ).error.code
        )

        val revokedAt = kotlin.time.Instant.fromEpochMilliseconds(touchedAt.toEpochMilliseconds() + 1)
        val revoked = assertIs<StoreResult.Success<IdentitySession>>(
            store.revokeSession(
                RevokeSessionCommand(
                    sessionId = session.id,
                    expectedVersion = shortened.version,
                    revokedAt = revokedAt,
                    reasonCode = "test_cleanup",
                    auditEvent = IdentityFixtures.auditEvent(
                        IdentityFixtures.auditEventId("audit-session-idle-touch-revoke"),
                        AuditAction.SESSION_REVOKED,
                        session.id.value
                    ).copy(occurredAt = revokedAt)
                )
            )
        ).value
        assertEquals(
            IdentityStoreErrorCode.SESSION_NOT_ACTIVE,
            assertIs<StoreResult.Failure>(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        sessionId = session.id,
                        expectedVersion = revoked.version,
                        lastUsedAt = touchedAt,
                        idleExpiresAt = shortenedIdleExpiresAt
                    )
                )
            ).error.code
        )
    }
}

private suspend fun provisionEnvironment(
    client: SqlClient,
    environment: IdentityEnvironment,
    namespace: String
) {
    client.preparedQuery(
        "SELECT aether_identity.provision_environment(\$1, \$2)"
    ).execute(Tuple.of(environment.wireName, namespace)).coAwait()
}
