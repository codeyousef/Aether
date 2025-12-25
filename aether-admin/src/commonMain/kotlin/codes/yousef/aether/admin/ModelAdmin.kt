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
}
