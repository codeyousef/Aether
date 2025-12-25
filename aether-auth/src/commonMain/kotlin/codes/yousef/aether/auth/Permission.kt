package codes.yousef.aether.auth

import codes.yousef.aether.db.*

/**
 * Permission model.
 */
object Permissions : Model<Permission>() {
    override val tableName = "auth_permission"
    
    val id = bigSerial("id")
    val name = varchar("name", length = 255)
    val codename = varchar("codename", length = 100, unique = true)
    
    val groups = manyToMany(Groups, tableName = "auth_group_permissions")
    
    override fun createInstance() = Permission()
}

class Permission : BaseEntity<Permission>() {
    override fun getModel() = Permissions
    
    var id: Long?
        get() = Permissions.id.getValue(this)
        set(value) = Permissions.id.setValue(this, value)
        
    var name: String?
        get() = Permissions.name.getValue(this)
        set(value) = Permissions.name.setValue(this, value)
        
    var codename: String?
        get() = Permissions.codename.getValue(this)
        set(value) = Permissions.codename.setValue(this, value)
}

/**
 * Group model.
 */
object Groups : Model<Group>() {
    override val tableName = "auth_group"
    
    val id = bigSerial("id")
    val name = varchar("name", length = 150, unique = true)
    
    val permissions = manyToMany(Permissions, tableName = "auth_group_permissions")
    
    override fun createInstance() = Group()
}

class Group : BaseEntity<Group>() {
    override fun getModel() = Groups
    
    var id: Long?
        get() = Groups.id.getValue(this)
        set(value) = Groups.id.setValue(this, value)
        
    var name: String?
        get() = Groups.name.getValue(this)
        set(value) = Groups.name.setValue(this, value)
}
