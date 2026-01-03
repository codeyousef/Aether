package codes.yousef.aether.admin

import codes.yousef.aether.db.BaseEntity
import codes.yousef.aether.db.Model

/**
 * Configuration for a model in the admin interface.
 */
open class ModelAdmin<T : BaseEntity<T>>(val model: Model<T>) {
    /**
     * Fields to display in the list view.
     * If empty, displays the string representation of the object.
     */
    open val listDisplay: List<String> = emptyList()
    
    /**
     * Fields to link to the edit view.
     * Defaults to the first field in listDisplay or the ID.
     */
    open val listDisplayLinks: List<String> = emptyList()
    
    /**
     * Fields to enable search for.
     */
    open val searchFields: List<String> = emptyList()
    
    /**
     * Fields to filter by in the sidebar.
     */
    open val listFilter: List<String> = emptyList()
    
    /**
     * Fields that should render as multiline textarea inputs.
     */
    open val multilineFields: List<String> = emptyList()
    
    /**
     * Fields to exclude from the add/edit form.
     * Useful for auto-generated or constant fields.
     */
    open val excludeFields: List<String> = emptyList()
    
    /**
     * Default values for fields when creating new objects.
     * Map of field name to default value.
     */
    open val defaultValues: Map<String, Any?> = emptyMap()
}
