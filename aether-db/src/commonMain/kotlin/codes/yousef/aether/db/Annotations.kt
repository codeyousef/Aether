package codes.yousef.aether.db

/**
 * Annotation to mark a class as an Aether database model.
 * Used by the KSP processor to generate migrations.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AetherModel(
    /**
     * Override the table name. If not specified, derived from class name.
     */
    val tableName: String = ""
)

/**
 * Annotation to mark a property as a database column.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Column(
    /**
     * Column name in the database. If not specified, uses property name.
     */
    val name: String = "",
    
    /**
     * SQL column type. If not specified, inferred from Kotlin type.
     */
    val type: String = "",
    
    /**
     * Whether the column is nullable.
     */
    val nullable: Boolean = true,
    
    /**
     * Whether the column has a unique constraint.
     */
    val unique: Boolean = false,
    
    /**
     * Default value expression (SQL).
     */
    val defaultValue: String = ""
)

/**
 * Annotation to mark a property as the primary key.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class PrimaryKey(
    /**
     * Whether the primary key auto-increments.
     */
    val autoIncrement: Boolean = false
)

/**
 * Annotation to create an index on one or more columns.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class Index(
    /**
     * Index name.
     */
    val name: String,
    
    /**
     * Column names to include in the index.
     */
    val columns: Array<String>,
    
    /**
     * Whether this is a unique index.
     */
    val unique: Boolean = false
)

/**
 * Annotation to define a foreign key relationship.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class ForeignKey(
    /**
     * Referenced table name.
     */
    val table: String,
    
    /**
     * Referenced column name.
     */
    val column: String,
    
    /**
     * Action on delete.
     */
    val onDelete: ReferentialAction = ReferentialAction.NO_ACTION,
    
    /**
     * Action on update.
     */
    val onUpdate: ReferentialAction = ReferentialAction.NO_ACTION
)

/**
 * Referential actions for foreign keys.
 */
enum class ReferentialAction {
    NO_ACTION,
    RESTRICT,
    CASCADE,
    SET_NULL,
    SET_DEFAULT
}
