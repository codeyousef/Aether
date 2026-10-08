package codes.yousef.aether.cli

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationFileSafetyTest {
    @Test
    fun `candidate filenames and unreviewed SQL are never applyable`() {
        withMigrationDirectory { directory ->
            directory.resolve("V000001__create.sql.candidate").writeText("-- Status: UNREVIEWED\nCREATE TABLE private_data(id BIGINT);")

            val failure = assertFailsWith<IllegalArgumentException> { reviewedMigrationFiles(directory) }

            assertTrue(failure.message.orEmpty().contains("V000001__create.sql.candidate"))
        }

        withMigrationDirectory { directory ->
            directory.resolve("V000001__legacy.candidate.sql").writeText("-- Status: REVIEWED\nSELECT 1;")

            assertFailsWith<IllegalArgumentException> { reviewedMigrationFiles(directory) }
        }

        withMigrationDirectory { directory ->
            directory.resolve("V000001__renamed.sql").writeText("-- Status: UNREVIEWED\nSELECT 1;")

            assertFailsWith<IllegalArgumentException> { reviewedMigrationFiles(directory) }
        }
    }

    @Test
    fun `reviewed migrations require versions and are ordered deterministically`() {
        withMigrationDirectory { directory ->
            directory.resolve("20_second.sql").writeText("-- Status: REVIEWED\nSELECT 2;")
            directory.resolve("V000010__first.sql").writeText("-- Status: REVIEWED\nSELECT 1;")

            assertEquals(
                listOf("V000010__first.sql", "20_second.sql"),
                reviewedMigrationFiles(directory).map { it.file.name }
            )
            assertEquals(20L, migrationVersion("20_second.sql"))
            assertEquals(10L, migrationVersion("V000010__first.sql"))
        }
    }

    private fun withMigrationDirectory(block: (java.io.File) -> Unit) {
        val directory = createTempDirectory("aether-migrations-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
