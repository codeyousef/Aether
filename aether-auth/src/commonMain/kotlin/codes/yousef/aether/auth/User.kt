package codes.yousef.aether.auth

import codes.yousef.aether.db.BaseEntity
import codes.yousef.aether.db.Model
import codes.yousef.aether.db.varchar
import codes.yousef.aether.db.boolean
import codes.yousef.aether.db.long
import codes.yousef.aether.db.bigSerial

/**
 * Base class for User entities.
 */
abstract class AbstractUser<T : AbstractUser<T>> : BaseEntity<T>() {
    private val userModel: UserModel<T> get() = getModel() as UserModel<T>

    var id: Long?
        get() = userModel.id.getValue(this as T)
        set(value) = userModel.id.setValue(this as T, value)

    var username: String?
        get() = userModel.username.getValue(this as T)
        set(value) = userModel.username.setValue(this as T, value)

    var password: String?
        get() = userModel.password.getValue(this as T)
        set(value) = userModel.password.setValue(this as T, value)

    var email: String?
        get() = userModel.email.getValue(this as T)
        set(value) = userModel.email.setValue(this as T, value)

    var isActive: Boolean?
        get() = userModel.isActive.getValue(this as T)
        set(value) = userModel.isActive.setValue(this as T, value)

    var isStaff: Boolean?
        get() = userModel.isStaff.getValue(this as T)
        set(value) = userModel.isStaff.setValue(this as T, value)

    var isSuperuser: Boolean?
        get() = userModel.isSuperuser.getValue(this as T)
        set(value) = userModel.isSuperuser.setValue(this as T, value)

    var lastLogin: Long?
        get() = userModel.lastLogin.getValue(this as T)
        set(value) = userModel.lastLogin.setValue(this as T, value)

    var dateJoined: Long?
        get() = userModel.dateJoined.getValue(this as T)
        set(value) = userModel.dateJoined.setValue(this as T, value)
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
}
