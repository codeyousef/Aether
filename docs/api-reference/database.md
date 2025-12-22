# Database API

The `aether-db` module provides a Django-inspired ORM (Object-Relational Mapping) system and a platform-agnostic Query AST. This allows you to define data models in Kotlin and interact with the database using high-level methods.

## Model

The `Model` class is the base for all data entities. It uses the ActiveRecord pattern.

### Defining a Model

```kotlin
object Users : Model<User>() {
    override val tableName = "users"

    val id = integer("id", primaryKey = true, autoIncrement = true)
    val username = varchar("username", maxLength = 100)
    val email = varchar("email", maxLength = 255, unique = true)
}
```

### Field Types

*   `integer(name: String, ...)`
*   `varchar(name: String, maxLength: Int, ...)`
*   `boolean(name: String, ...)`
*   `text(name: String, ...)`
*   // ... and more

### Querying

#### `all()`
Retrieves all records from the table.

```kotlin
val allUsers = Users.all()
```

#### `get(id: Any)`
Retrieves a single record by its primary key.

```kotlin
val user = Users.get(1)
```

#### `filter(where: WhereClause)`
Retrieves records matching a specific condition.

```kotlin
val activeUsers = Users.filter(
    Users.isActive eq true
)
```

### Query AST

Aether DB does not generate SQL strings directly in the common code. Instead, it builds an Abstract Syntax Tree (AST) representing the query. This AST is then translated into the appropriate SQL dialect by the specific `DatabaseDriver` (e.g., PostgreSQL, SQLite, or an HTTP-based driver for Wasm).

Key AST components:
*   `SelectQuery`
*   `InsertQuery`
*   `UpdateQuery`
*   `DeleteQuery`
*   `WhereClause`
*   `Expression`

## DatabaseDriver

The `DatabaseDriver` interface defines the contract for executing queries.

*   `executeQuery(query: Query): List<Row>`
*   `execute(query: Query): Int` (for INSERT/UPDATE/DELETE)

The `DatabaseDriverRegistry` holds the global driver instance.
