package codes.yousef.aether.example

import codes.yousef.aether.auth.AbstractUser
import codes.yousef.aether.auth.UserModel
import codes.yousef.aether.auth.crypto.PasswordHasher
import codes.yousef.aether.db.*

/**
 * User Model defining the database table structure.
 */
object Users : UserModel<User>() {
    override val tableName: String = "users"
    
    // Inherited fields: id, username, password, email, isActive, isStaff, isSuperuser, lastLogin, dateJoined
    
    val age = integer("age", nullable = true)

    override fun createInstance(): User {
        return User()
    }
}

/**
 * User Entity with ActiveRecord pattern methods.
 */
class User : AbstractUser<User>() {
    
    var age: Int?
        get() = Users.age.getValue(this)
        set(value) = Users.age.setValue(this, value)

    override fun getModel(): Model<User> = Users

    companion object {
        /**
         * Find a user by ID.
         */
        suspend fun findById(id: Long): User? {
            return Users.get(id)
        }

        /**
         * Find a user by username.
         */
        suspend fun findByUsername(username: String): User? {
            val results = Users.filter(
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "username"),
                    operator = ComparisonOperator.EQUALS,
                    right = Expression.Literal(SqlValue.StringValue(username))
                )
            )
            return results.firstOrNull()
        }
        
        /**
         * Create a new user.
         */
        suspend fun create(username: String, email: String, age: Int? = null, password: String? = null): User {
            val user = User()
            user.username = username
            user.email = email
            user.age = age
            if (password != null) {
                user.password = PasswordHasher.hash(password)
            }
            user.save()
            return user
        }
        
        suspend fun all(): List<User> {
            return Users.all()
        }
    }
}
