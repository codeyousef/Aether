package codes.yousef.aether.example

import codes.yousef.aether.db.*

/**
 * User Model defining the database table structure.
 */
object Users : Model<User>() {
    override val tableName: String = "users"

    val id = serial("id")
    val username = varchar("username", nullable = false, unique = true)
    val email = varchar("email", nullable = false)
    val age = integer("age", nullable = true)

    override fun createInstance(): User {
        return User()
    }
}

/**
 * User Entity with ActiveRecord pattern methods.
 */
data class User(
    var id: Int? = null,
    var username: String? = null,
    var email: String? = null,
    var age: Int? = null
) : BaseEntity<User>() {

    override fun getModel(): Model<User> = Users

    companion object {
        /**
         * Find a user by ID.
         */
        suspend fun findById(id: Int): User? {
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
         * Get all users.
         */
        suspend fun all(): List<User> {
            return Users.all()
        }

        /**
         * Create a new user and save it to the database.
         */
        suspend fun create(username: String, email: String, age: Int? = null): User {
            val user = User(
                username = username,
                email = email,
                age = age
            )
            user.save()
            return user
        }
    }
}
