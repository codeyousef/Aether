package codes.yousef.aether.auth.postgresql

import codes.yousef.aether.auth.StoreResult
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import java.net.JarURLConnection
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

data class PostgresqlMigrationReport(
    val version: Int,
    val applied: Boolean,
    val checksum: String
)

/** Applies the reviewed identity migrations only after the complete packaged bundle matches its manifest. */
class PostgresqlMigrationRunner(
    private val pool: Pool,
    private val migrationResources: List<String> = DEFAULT_MIGRATION_RESOURCES
) {
    constructor(pool: Pool, migrationResource: String) : this(pool, listOf(migrationResource))

    suspend fun migrate(): StoreResult<PostgresqlMigrationReport> {
        val migrations = loadMigrations()
            ?: return StoreResult.Failure(PostgresqlFailureMapper.internal())
        val connection = try {
            pool.connection.coAwait()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return StoreResult.Failure(PostgresqlFailureMapper.unavailable())
        }

        val transaction = try {
            connection.begin().coAwait()
        } catch (cancelled: CancellationException) {
            connection.close().coAwait()
            throw cancelled
        } catch (_: Throwable) {
            connection.close().coAwait()
            return StoreResult.Failure(PostgresqlFailureMapper.unavailable())
        }

        return try {
            connection.query(BOOTSTRAP_SQL).execute().coAwait()
            connection.preparedQuery("SELECT pg_advisory_xact_lock(hashtext(\$1))")
                .execute(Tuple.of(MODULE_NAME)).coAwait()
            var applied = false
            for (migration in migrations) {
                val rows = connection.preparedQuery(
                    "SELECT checksum FROM aether_identity.schema_migrations " +
                        "WHERE module = \$1 AND version = \$2 FOR UPDATE"
                ).execute(Tuple.of(MODULE_NAME, migration.version)).coAwait()
                val iterator = rows.iterator()
                val existingChecksum = if (iterator.hasNext()) iterator.next().getString("checksum") else null
                when {
                    existingChecksum == null -> {
                        connection.query(migration.sql).execute().coAwait()
                        connection.preparedQuery(
                            "INSERT INTO aether_identity.schema_migrations(module, version, checksum) " +
                                "VALUES (\$1, \$2, \$3)"
                        ).execute(Tuple.of(MODULE_NAME, migration.version, migration.checksum)).coAwait()
                        applied = true
                    }
                    existingChecksum != migration.checksum -> {
                        transaction.rollback().coAwait()
                        return StoreResult.Failure(PostgresqlFailureMapper.internal())
                    }
                }
            }
            transaction.commit().coAwait()
            val latest = migrations.last()
            StoreResult.Success(PostgresqlMigrationReport(latest.version, applied, latest.checksum))
        } catch (cancelled: CancellationException) {
            rollbackQuietly(transaction)
            throw cancelled
        } catch (failure: PgException) {
            rollbackQuietly(transaction)
            StoreResult.Failure(PostgresqlFailureMapper.fromProviderCode(failure.sqlState))
        } catch (_: Throwable) {
            rollbackQuietly(transaction)
            StoreResult.Failure(PostgresqlFailureMapper.internal())
        } finally {
            runCatching { connection.close().coAwait() }
        }
    }

    private fun loadMigrations(): List<PostgresqlMigration>? = loadPackagedPostgresqlMigrations(
        requestedResources = migrationResources
    )

    private suspend fun rollbackQuietly(transaction: io.vertx.sqlclient.Transaction) {
        runCatching { transaction.rollback().coAwait() }
    }

    companion object {
        const val DEFAULT_MIGRATION_RESOURCE: String = "/db/aether-identity/V001__identity_foundation.sql"
        const val FEDERATED_SESSION_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V002__federated_session_provenance.sql"
        const val ORGANIZATION_AUDIT_READ_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V003__organization_audit_reads.sql"
        const val AUDIT_RETENTION_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V004__audit_retention.sql"
        const val DEVICE_GRANT_CAS_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V005__device_grant_cas_serialization.sql"
        const val IDENTITY_SESSION_TOUCH_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V006__identity_session_touch.sql"
        const val ADMINISTRATIVE_RECOVERY_ACTIVATION_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V007__administrative_recovery_activation.sql"
        const val DEVICE_MEMBERSHIP_BINDING_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V008__device_membership_binding.sql"
        const val FAIL_CLOSED_ENVIRONMENT_MARKER_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V009__fail_closed_environment_marker.sql"
        const val TERMINAL_WEBAUTHN_ATTEMPTS_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V010__terminal_webauthn_attempts.sql"
        const val FEDERATION_PROVIDER_LIFECYCLE_MIGRATION_RESOURCE: String =
            "/db/aether-identity/V011__federation_provider_lifecycle.sql"
        const val MIGRATION_CHECKSUM_MANIFEST_RESOURCE: String =
            "/db/aether-identity/SHA256SUMS"
        val DEFAULT_MIGRATION_RESOURCES: List<String> = listOf(
            DEFAULT_MIGRATION_RESOURCE,
            FEDERATED_SESSION_MIGRATION_RESOURCE,
            ORGANIZATION_AUDIT_READ_MIGRATION_RESOURCE,
            AUDIT_RETENTION_MIGRATION_RESOURCE,
            DEVICE_GRANT_CAS_MIGRATION_RESOURCE,
            IDENTITY_SESSION_TOUCH_MIGRATION_RESOURCE,
            ADMINISTRATIVE_RECOVERY_ACTIVATION_MIGRATION_RESOURCE,
            DEVICE_MEMBERSHIP_BINDING_MIGRATION_RESOURCE,
            FAIL_CLOSED_ENVIRONMENT_MARKER_MIGRATION_RESOURCE,
            TERMINAL_WEBAUTHN_ATTEMPTS_MIGRATION_RESOURCE,
            FEDERATION_PROVIDER_LIFECYCLE_MIGRATION_RESOURCE
        )
        private const val MODULE_NAME: String = "aether-auth-postgresql"
        private val BOOTSTRAP_SQL = """
            CREATE SCHEMA IF NOT EXISTS aether_identity;
            CREATE TABLE IF NOT EXISTS aether_identity.schema_migrations (
                module TEXT NOT NULL,
                version INTEGER NOT NULL,
                checksum TEXT NOT NULL,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (module, version)
            );
        """.trimIndent()
    }
}

internal fun loadPackagedPostgresqlMigrations(
    requestedResources: List<String> = PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCES
): List<PostgresqlMigration>? = loadPostgresqlMigrationBundle(
    requestedResources = requestedResources,
    manifestBytes = readPackagedResource(PostgresqlMigrationRunner.MIGRATION_CHECKSUM_MANIFEST_RESOURCE),
    packagedMigrationResources = discoverPackagedMigrationResources(),
    readResource = ::readPackagedResource
)

internal data class PostgresqlMigration(
    val version: Int,
    val sql: String,
    val checksum: String
)

internal fun loadPostgresqlMigrationBundle(
    requestedResources: List<String>,
    manifestBytes: ByteArray?,
    packagedMigrationResources: Set<String>?,
    readResource: (String) -> ByteArray?
): List<PostgresqlMigration>? {
    if (manifestBytes == null || packagedMigrationResources == null) return null
    if (requestedResources.isEmpty() || requestedResources.toSet().size != requestedResources.size) return null

    val manifest = parseMigrationChecksumManifest(manifestBytes) ?: return null
    val reviewedResources = PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCES
    if (manifest.map(MigrationChecksumEntry::resource) != reviewedResources) return null
    if (packagedMigrationResources != reviewedResources.toSet()) return null
    if (requestedResources.any { it !in reviewedResources }) return null

    val reviewedMigrations = try {
        manifest.associate { entry ->
            val bytes = readResource(entry.resource) ?: return null
            if (bytes.sha256() != entry.checksum) return null
            val sql = bytes.decodeToString(throwOnInvalidSequence = true)
            val version = entry.resource.migrationVersion() ?: return null
            entry.resource to PostgresqlMigration(version, sql, entry.checksum)
        }
    } catch (_: Exception) {
        return null
    }
    if (reviewedMigrations.values.map(PostgresqlMigration::version).toSet().size != reviewedMigrations.size) {
        return null
    }
    return requestedResources.map { reviewedMigrations.getValue(it) }.sortedBy(PostgresqlMigration::version)
}

private data class MigrationChecksumEntry(
    val resource: String,
    val checksum: String
)

private fun parseMigrationChecksumManifest(bytes: ByteArray): List<MigrationChecksumEntry>? {
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        return null
    }
    val rawLines = text.split('\n')
    val lines = if (rawLines.lastOrNull().isNullOrEmpty()) rawLines.dropLast(1) else rawLines
    if (lines.isEmpty() || lines.any(String::isEmpty)) return null

    val entries = lines.map { line ->
        val match = MIGRATION_CHECKSUM_LINE.matchEntire(line) ?: return null
        MigrationChecksumEntry(
            resource = "$MIGRATION_RESOURCE_DIRECTORY/${match.groupValues[2]}",
            checksum = match.groupValues[1]
        )
    }
    if (entries.map(MigrationChecksumEntry::resource).toSet().size != entries.size) return null
    return entries
}

private fun readPackagedResource(resource: String): ByteArray? = try {
    PostgresqlMigrationRunner::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
} catch (_: Exception) {
    null
}

/**
 * Inventories SQL resources in the same classpath container as the committed manifest. An
 * unsupported container protocol is rejected instead of silently skipping the extra-file check.
 */
internal fun discoverPackagedMigrationResources(): Set<String>? {
    return try {
        val manifestUrl = PostgresqlMigrationRunner::class.java.getResource(
            PostgresqlMigrationRunner.MIGRATION_CHECKSUM_MANIFEST_RESOURCE
        ) ?: return null
        when (manifestUrl.protocol) {
            "file" -> {
                val directory = java.nio.file.Paths.get(manifestUrl.toURI()).parent
                Files.walk(directory).use { paths ->
                    paths
                        .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sql") }
                        .map { path ->
                            val relative = directory.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                            "$MIGRATION_RESOURCE_DIRECTORY/$relative"
                        }
                        .toList()
                        .toSet()
                }
            }
            "jar" -> {
                val connection = manifestUrl.openConnection() as? JarURLConnection ?: return null
                connection.useCaches = false
                connection.jarFile.use { jar ->
                    jar.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                                entry.name.startsWith(MIGRATION_RESOURCE_DIRECTORY.removePrefix("/") + "/") &&
                                entry.name.endsWith(".sql")
                        }
                        .map { "/${it.name}" }
                        .toSet()
                }
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun String.migrationVersion(): Int? {
    val fileName = substringAfterLast('/')
    val version = fileName.substringAfter('V', missingDelimiterValue = "")
        .substringBefore("__", missingDelimiterValue = "")
        .toIntOrNull()
    return version
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private const val MIGRATION_RESOURCE_DIRECTORY: String = "/db/aether-identity"
private val MIGRATION_CHECKSUM_LINE = Regex("^([0-9a-f]{64})  (V[0-9]{3}__[A-Za-z0-9_.-]+\\.sql)$")
