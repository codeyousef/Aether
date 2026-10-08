package codes.yousef.aether.ksp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationSafetyTest {
    @Test
    fun `KSP requires manual review for destructive and ambiguous changes`() {
        val column = ColumnSchema("value", "TEXT")
        val changed = ColumnSchema("value", "BIGINT")
        val destructive = listOf(
            SchemaChange.DropTable("private_objects"),
            SchemaChange.RenameTable("old", "new"),
            SchemaChange.DropColumn("private_objects", "payload"),
            SchemaChange.AlterColumn("private_objects", column, changed),
            SchemaChange.RenameColumn("private_objects", "old", "new"),
            SchemaChange.DropConstraint("private_objects", "owner_fk"),
            SchemaChange.DropIndex("private_objects_owner_idx")
        )

        assertTrue(destructive.all(::requiresReviewedDestructiveMigration))
    }

    @Test
    fun `KSP allows only expand candidates`() {
        val table = TableSchema("new_table", listOf(ColumnSchema("id", "BIGINT")))
        val expand = listOf(
            SchemaChange.CreateTable(table),
            SchemaChange.AddColumn("private_objects", ColumnSchema("media_type", "TEXT")),
            SchemaChange.AddConstraint("private_objects", ConstraintSchema.Unique(listOf("object_id"))),
            SchemaChange.CreateIndex(
                "private_objects",
                ConstraintSchema.Index(listOf("owner_id"), "private_objects_owner_idx")
            )
        )

        assertFalse(expand.any(::requiresReviewedDestructiveMigration))
    }
}
