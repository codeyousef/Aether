package codes.yousef.aether.auth.postgresql

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PostgresqlMigrationRunnerTest {
    @Test
    fun committedManifestMatchesTheCompletePackagedMigrationBundle() {
        val migrations = assertNotNull(loadPackagedPostgresqlMigrations())

        assertEquals((1..11).toList(), migrations.map(PostgresqlMigration::version))
        assertEquals(11, migrations.map(PostgresqlMigration::checksum).toSet().size)
        assertEquals(PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCES.toSet(), assertNotNull(
            discoverPackagedMigrationResources()
        ))
    }

    @Test
    fun bundleRejectsChangedOrMissingMigrationBytes() {
        val fixture = migrationFixture()
        val changedBytes = fixture.bytes.toMutableMap().apply {
            val resource = PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCE
            put(resource, getValue(resource) + "-- unreviewed change\n".encodeToByteArray())
        }
        assertNull(fixture.load(bytes = changedBytes))

        val missingBytes = fixture.bytes - PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCE
        assertNull(fixture.load(bytes = missingBytes))
    }

    @Test
    fun bundleRejectsMissingOrExtraManifestEntries() {
        val fixture = migrationFixture()
        val reviewed = PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCES
        assertNull(fixture.load(manifest = fixture.manifestFor(reviewed.dropLast(1))))

        val extra = "/db/aether-identity/V012__unreviewed.sql"
        val bytesWithExtra = fixture.bytes + (extra to "SELECT 'unreviewed';\n".encodeToByteArray())
        assertNull(
            fixture.load(
                bytes = bytesWithExtra,
                manifest = fixture.manifestFor(reviewed + extra, bytesWithExtra),
                inventory = reviewed.toSet() + extra
            )
        )
    }

    @Test
    fun bundleRejectsMissingOrExtraPackagedSqlResources() {
        val fixture = migrationFixture()
        val reviewed = PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCES
        assertNull(fixture.load(inventory = reviewed.dropLast(1).toSet()))
        assertNull(fixture.load(inventory = reviewed.toSet() + "/db/aether-identity/unreviewed.sql"))
    }

    private fun migrationFixture(): MigrationFixture {
        val bytes = PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCES.mapIndexed { index, resource ->
            resource to "SELECT ${index + 1};\n".encodeToByteArray()
        }.toMap()
        return MigrationFixture(bytes)
    }

    private data class MigrationFixture(val bytes: Map<String, ByteArray>) {
        private val reviewedResources = PostgresqlMigrationRunner.DEFAULT_MIGRATION_RESOURCES

        fun load(
            bytes: Map<String, ByteArray> = this.bytes,
            manifest: ByteArray = manifestFor(reviewedResources),
            inventory: Set<String> = reviewedResources.toSet()
        ): List<PostgresqlMigration>? = loadPostgresqlMigrationBundle(
            requestedResources = reviewedResources,
            manifestBytes = manifest,
            packagedMigrationResources = inventory,
            readResource = bytes::get
        )

        fun manifestFor(
            resources: List<String>,
            bytes: Map<String, ByteArray> = this.bytes
        ): ByteArray = resources.joinToString(separator = "\n", postfix = "\n") { resource ->
            "${bytes.getValue(resource).sha256()}  ${resource.substringAfterLast('/')}"
        }.encodeToByteArray()
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
