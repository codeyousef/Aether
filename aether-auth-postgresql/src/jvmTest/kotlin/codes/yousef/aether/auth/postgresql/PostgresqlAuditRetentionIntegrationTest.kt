package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.*
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class PostgresqlAuditRetentionIntegrationTest {
    @Test
    fun migrationAndRpcDeleteOnlyTheBoundedStrictlyExpiredBatch() = runBlocking {
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("aether_identity_retention_test")
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
            val migration = assertIs<StoreResult.Success<PostgresqlMigrationReport>>(
                PostgresqlMigrationRunner(pool).migrate()
            ).value
            assertEquals(11, migration.version)
            val config = PostgresqlIdentityConfig(IdentityEnvironment.TEST, "aether_test")
            pool.preparedQuery(
                "SELECT aether_identity.provision_environment(\$1, \$2)"
            ).execute(Tuple.of(config.environment.wireName, config.namespace)).coAwait()
            val store = PostgresqlIdentityStore(config, VertxPostgresqlRpcTransport(config, pool))
            assertIs<StoreResult.Success<Unit>>(store.initialize())
            val cutoff = Instant.fromEpochMilliseconds(1_735_689_603_000)
            suspend fun append(id: String, milliseconds: Long) {
                assertIs<StoreResult.Success<AuditEvent>>(
                    store.appendAuditEvent(
                        AuditEvent(
                            id = AuditEventId.parse(id),
                            actor = AuditActor(AuditActorType.SYSTEM),
                            action = AuditAction.USER_STATE_CHANGED,
                            outcome = AuditOutcome.SUCCEEDED,
                            occurredAt = Instant.fromEpochMilliseconds(milliseconds)
                        )
                    )
                )
            }
            append("018f0f2e-7b00-7000-8000-000000000021", cutoff.toEpochMilliseconds() - 2_000)
            append("018f0f2e-7b00-7000-8000-000000000022", cutoff.toEpochMilliseconds() - 1_000)
            append("018f0f2e-7b00-7000-8000-000000000023", cutoff.toEpochMilliseconds())

            assertEquals(
                PurgeAuditEventsCommit(deletedCount = 1, hasMore = true),
                assertIs<StoreResult.Success<PurgeAuditEventsCommit>>(
                    store.purgeAuditEvents(PurgeAuditEventsCommand(cutoff, maximumEvents = 1))
                ).value
            )
            assertEquals(
                PurgeAuditEventsCommit(deletedCount = 1, hasMore = false),
                assertIs<StoreResult.Success<PurgeAuditEventsCommit>>(
                    store.purgeAuditEvents(PurgeAuditEventsCommand(cutoff, maximumEvents = 1))
                ).value
            )
            assertEquals(
                "018f0f2e-7b00-7000-8000-000000000023",
                pool.query("SELECT id FROM aether_identity.audit_events")
                    .execute().coAwait().single().getString("id")
            )
            assertEquals(
                PurgeAuditEventsCommit(deletedCount = 0, hasMore = false),
                assertIs<StoreResult.Success<PurgeAuditEventsCommit>>(
                    store.purgeAuditEvents(PurgeAuditEventsCommand(cutoff))
                ).value
            )
        } finally {
            pool.close().coAwait()
            vertx.close().coAwait()
            withContext(Dispatchers.IO) { postgres.stop() }
        }
    }
}
