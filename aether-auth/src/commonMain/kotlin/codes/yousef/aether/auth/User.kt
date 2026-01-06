package codes.yousef.aether.auth

import codes.yousef.aether.db.BaseEntity
import codes.yousef.aether.db.Model
import codes.yousef.aether.db.varchar
import codes.yousef.aether.db.boolean
import codes.yousef.aether.db.long
import codes.yousef.aether.db.bigSerial
import codes.yousef.aether.db.manyToMany
import codes.yousef.aether.db.eq

/**
 * Base class for User entities.
 */
abstract class AbstractUser<T : AbstractUser<T>> : BaseEntity<T>() {
    private val userModel: UserModel<T> get() = getModel() as UserModel<T>

    @Suppress("UNCHECKED_CAST")
    private val self: T
        get() = this as T

    var id: Long?
        get() = userModel.id.getValue(self)
        set(value) = userModel.id.setValue(self, value)

    var username: String?
        get() = userModel.username.getValue(self)
        set(value) = userModel.username.setValue(self, value)

    var password: String?
        get() = userModel.password.getValue(self)
        set(value) = userModel.password.setValue(self, value)

    var email: String?
        get() = userModel.email.getValue(self)
        set(value) = userModel.email.setValue(self, value)

    var isActive: Boolean?
        get() = userModel.isActive.getValue(self)
        set(value) = userModel.isActive.setValue(self, value)

    var isStaff: Boolean?
        get() = userModel.isStaff.getValue(self)
        set(value) = userModel.isStaff.setValue(self, value)

    var isSuperuser: Boolean?
        get() = userModel.isSuperuser.getValue(self)
        set(value) = userModel.isSuperuser.setValue(self, value)

    var lastLogin: Long?
        get() = userModel.lastLogin.getValue(self)
        set(value) = userModel.lastLogin.setValue(self, value)

    var dateJoined: Long?
        get() = userModel.dateJoined.getValue(self)
        set(value) = userModel.dateJoined.setValue(self, value)

    /**
     * Check if user has a specific permission.
     * Checks both direct user permissions and permissions inherited from groups.
     */
    suspend fun hasPermission(codename: String): Boolean {
        if (isSuperuser == true) return true
        if (isActive != true) return false
        
        // Check direct permissions
        if (userModel.userPermissions.query(self).filter(Permissions.codename eq codename).exists()) {
            return true
        }
        
        // Check group permissions
        val groups = userModel.groups.query(self).toList()
        for (group in groups) {
            if (Groups.permissions.query(group).filter(Permissions.codename eq codename).exists()) {
                return true
            }
        }
        
        return false 
    }
}

/**
 * Base Model for Users.
 */
abstract class UserModel<T : AbstractUser<T>> : Model<T>() {
    val id = bigSerial("id")
    val username = varchar("username", length = 150, unique = true)
    val password = varchar("password", length = 128)
    val email = varchar("email", length = 254, nullable = true)
    val isActive = boolean("is_active", defaultValue = true)
    val isStaff = boolean("is_staff", defaultValue = false)
    val isSuperuser = boolean("is_superuser", defaultValue = false)
    val lastLogin = long("last_login", nullable = true)
    val dateJoined = long("date_joined", defaultValue = SystemClock.now())
    
    val groups = manyToMany(Groups, tableName = "auth_user_groups")
    val userPermissions = manyToMany(Permissions, tableName = "auth_user_user_permissions")
}
