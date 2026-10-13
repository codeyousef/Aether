package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.*
import codes.yousef.aether.auth.testkit.*
import codes.yousef.aether.core.jvm.*
import codes.yousef.aether.core.pipeline.Pipeline
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.PemTrustOptions
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.pgclient.*
import io.vertx.sqlclient.PoolOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.net.URI
import java.net.http.*
import kotlin.test.*

class PostgresqlRuntimeRoleIntegrationTest {
    @Test
    fun freshAndUpgradeSupportNonOwnerRuntimeOverVerifiedTls() = runBlocking {
        val temp = Files.createTempDirectory("aether-pg-runtime-")
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/tls/$name")).use { it.readBytes() }
        val certificate = resource("test-cert.pem")
        Files.write(temp.resolve("cert.pem"), certificate)
        Files.write(temp.resolve("key.pem"), resource("test-key.pem"))
        Files.writeString(temp.resolve("tls.sh"), """
            cp /tmp/aether-cert.pem "${'$'}PGDATA/server.crt"
            cp /tmp/aether-key.pem "${'$'}PGDATA/server.key"
            chmod 600 "${'$'}PGDATA/server.key"
            echo "ssl=on" >> "${'$'}PGDATA/postgresql.conf"
            echo "ssl_cert_file='server.crt'" >> "${'$'}PGDATA/postgresql.conf"
            echo "ssl_key_file='server.key'" >> "${'$'}PGDATA/postgresql.conf"
        """.trimIndent())
        val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
            withDatabaseName("runtime_admin"); withUsername("admin"); withPassword("synthetic-admin-only")
            withCopyFileToContainer(MountableFile.forHostPath(temp.resolve("cert.pem")), "/tmp/aether-cert.pem")
            withCopyFileToContainer(MountableFile.forHostPath(temp.resolve("key.pem")), "/tmp/aether-key.pem")
            withCopyFileToContainer(MountableFile.forHostPath(temp.resolve("tls.sh"), 493), "/docker-entrypoint-initdb.d/001-tls.sh")
        }
        withContext(Dispatchers.IO) { postgres.start() }
        val vertx = Vertx.vertx()
        fun pool(database: String, role: String, password: String) = PgBuilder.pool().with(PoolOptions().setMaxSize(4))
            .connectingTo(PgConnectOptions().setHost("localhost").setPort(postgres.firstMappedPort).setDatabase(database)
                .setUser(role).setPassword(password).setSslMode(SslMode.VERIFY_FULL).setHostnameVerificationAlgorithm("HTTPS")
                .setPemTrustOptions(PemTrustOptions().addCertValue(Buffer.buffer(certificate))))
            .using(vertx).build()
        val admin = pool("runtime_admin", "admin", "synthetic-admin-only")
        try {
            admin.query("CREATE ROLE identity_owner LOGIN NOSUPERUSER NOCREATEROLE PASSWORD 'synthetic-owner-only'").execute().coAwait()
            admin.query("CREATE ROLE identity_runtime LOGIN NOSUPERUSER NOINHERIT PASSWORD 'synthetic-runtime-only'").execute().coAwait()
            for (upgrade in listOf(false, true)) {
                val database = if (upgrade) "runtime_test_upgrade" else "runtime_test_fresh"
                admin.query("CREATE DATABASE $database OWNER identity_owner").execute().coAwait()
                val owner = pool(database, "identity_owner", "synthetic-owner-only")
                val runtimePool = pool(database, "identity_runtime", "synthetic-runtime-only")
                try {
                    val previous = PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCES.dropLast(1)
                    assertIs<StoreResult.Success<*>>(PostgresqlMigrationRunner(owner,
                        if (upgrade) previous else PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCES).migrate())
                    owner.query("GRANT USAGE ON SCHEMA aether_identity TO identity_runtime").execute().coAwait()
                    owner.query("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA aether_identity TO identity_runtime").execute().coAwait()
                    owner.query("REVOKE INSERT, UPDATE, DELETE ON aether_identity.environment, aether_identity.schema_migrations FROM identity_runtime").execute().coAwait()
                    for (operation in PostgresqlRpcOperation.entries) {
                        owner.query("GRANT EXECUTE ON FUNCTION aether_identity.${operation.functionName}(JSONB) TO identity_runtime").execute().coAwait()
                    }
                    if (upgrade) assertIs<StoreResult.Success<*>>(PostgresqlMigrationRunner(owner).migrate())
                    owner.query("SELECT aether_identity.provision_environment('test', '$database')").execute().coAwait()
                    assertFalse(runtimePool.query("SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname=current_user").execute().coAwait().single().getBoolean("rolsuper"))
                    assertTrue(runtimePool.query("SELECT ssl FROM pg_catalog.pg_stat_ssl WHERE pid=pg_backend_pid()").execute().coAwait().single().getBoolean("ssl"))
                    val config = PostgresqlIdentityConfig(IdentityEnvironment.TEST, database)
                    val store = PostgresqlIdentityStore(config, VertxPostgresqlRpcTransport(config, runtimePool))
                    assertIs<StoreResult.Success<*>>(store.initialize())
                    // Bootstrap hits insert_session -> private federation validation under a non-federated method.
                    val user = IdentityFixtures.user()
                    val org = IdentityFixtures.organization()
                    val session = IdentityFixtures.session(userId = user.id, assurance = AuthenticationAssurance.RECOVERY,
                        authenticationMethod = SessionAuthenticationMethod.BOOTSTRAP)
                    assertIs<StoreResult.Success<*>>(store.bootstrapIdentity(BootstrapIdentityCommand(
                        SecretDigest(DigestAlgorithm.SHA256, "synthetic-bootstrap-receipt"), user, org,
                        IdentityFixtures.membership(organizationId = org.id, userId = user.id), session,
                        IdentityFixtures.auditEvent(action = AuditAction.IDENTITY_BOOTSTRAPPED, targetId = user.id.value).copy(organizationId = org.id))))
                    IdentityStoreConformanceSuite(store, "nonowner-${if (upgrade) "upgrade" else "fresh"}").runAll()
                    val runtime = jvmIdentityRuntime(secrets = IdentitySecretResolver { IdentitySecret.fromUtf8("synthetic-runtime-key") })
                    AccountSecurityConformanceSuite(store, runtime, JvmPasswordProvider(), database).runAll()
                    exerciseOriginalExchangeGuard(store, runtime)
                    assertIs<StoreResult.Failure>(PostgresqlIdentityStore(PostgresqlIdentityConfig(IdentityEnvironment.TEST, "foreign_test"),
                        VertxPostgresqlRpcTransport(PostgresqlIdentityConfig(IdentityEnvironment.TEST, "foreign_test"), runtimePool)).initialize())
                    suspend fun denied(sql: String) {
                        val failure = assertFailsWith<PgException> { runtimePool.query(sql).execute().coAwait() }
                        assertEquals("42501", failure.sqlState)
                    }
                    denied("SELECT aether_identity_internal.require_federated_session('{}'::jsonb)")
                    denied("SELECT aether_identity.provision_environment('test', '$database')")
                    denied("DELETE FROM aether_identity.environment")
                    denied("DELETE FROM aether_identity.schema_migrations")
                    owner.query("REVOKE EXECUTE ON FUNCTION aether_identity.v1_touch_identity_session(JSONB) FROM identity_runtime").execute().coAwait()
                    denied("SELECT aether_identity.v1_touch_identity_session('{}'::jsonb)")
                    owner.query("GRANT EXECUTE ON FUNCTION aether_identity.v1_touch_identity_session(JSONB) TO identity_runtime").execute().coAwait()
                    assertEquals(0L, owner.query("SELECT COUNT(*) AS count FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace " +
                        "WHERE n.nspname='aether_identity_internal' AND has_function_privilege('identity_runtime',p.oid,'EXECUTE')").execute().coAwait().single().getLong("count"))
                    assertFalse(runtimePool.query("SELECT has_schema_privilege(current_user,'aether_identity_internal','USAGE') AS allowed").execute().coAwait().single().getBoolean("allowed"))
                } finally { runtimePool.close().coAwait(); owner.close().coAwait() }
            }
        } finally {
            admin.close().coAwait(); vertx.close().coAwait()
            withContext(Dispatchers.IO) { postgres.stop() }
            temp.toFile().deleteRecursively()
        }
    }

    private suspend fun exerciseOriginalExchangeGuard(store: IdentityStore, runtime: IdentityRuntime) {
        val config = accountSecurityTestConfig()
        val ids = IdentityIdFactory(runtime)
        val now = runtime.clock.now()
        val user = IdentityFixtures.user(ids.newUserId()).copy(primaryEmail = EmailAddress("guard@example.invalid"), createdAt = now, updatedAt = now, activatedAt = now)
        assertIs<StoreResult.Success<*>>(store.applyScimMutation(ApplyScimMutationCommand(ScimMutation(ids.newScimOperationId(),
            "runtime-guard", ScimMutationType.UPSERT_USER, ExternalSubject(user.id.value), user = user, occurredAt = now),
            IdentityFixtures.auditEvent(ids.newAuditEventId(), action = AuditAction.SCIM_MUTATION_APPLIED, targetId = user.id.value).copy(occurredAt = now))))
        val issued = IdentitySessionIssuer(runtime, config).issue(user, AuthenticationAssurance.PASSKEY, SessionAuthenticationMethod.PASSKEY, now)
        assertIs<StoreResult.Success<*>>(store.createSession(CreateSessionCommand(issued.session,
            IdentityFixtures.auditEvent(ids.newAuditEventId(), action = AuditAction.SESSION_CREATED, targetId = user.id.value).copy(occurredAt = now))))
        val pipeline = Pipeline().apply { use(IdentityMiddleware(StoreBackedIdentityContextResolver(store, runtime, config), runtime.clock, runtime.secureRandom).asMiddleware()) }
        val guard = IdentityRequestAuthorityGuard(runtime, config)
        var calls = 0
        val server = VertxServer(VertxServerConfig(host = "127.0.0.1", port = 0), pipeline) { original ->
            guard.protect(original) { exchange ->
                assertSame(original, exchange); assertEquals("/application/mutate", exchange.request.path)
                assertEquals("original-body", exchange.request.bodyBytes().decodeToString())
                assertNotNull(exchange.request.connection.peerAddress)
                calls++; exchange.response.write("applied")
            }
        }
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            fun request(csrf: String?): Int {
                val builder = HttpRequest.newBuilder(URI("http://localhost:${server.actualPort}/application/mutate"))
                    .header("Cookie", "${config.cookie.name}=${issued.cookieValue()}").header("Origin", config.publicBaseUrl)
                    .POST(HttpRequest.BodyPublishers.ofString("original-body"))
                csrf?.let { builder.header("X-CSRF-Token", it) }
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode()
            }
            assertEquals(403, request(null)); assertEquals(0, calls)
            assertEquals(200, request(issued.csrfToken())); assertEquals(1, calls)
            assertTrue(assertIs<StoreResult.Success<IdentitySession?>>(store.findSession(issued.session.id)).value!!.version >= 2)
        } finally { server.close() }
    }
}
