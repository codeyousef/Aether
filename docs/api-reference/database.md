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

## Database Backends

### PostgreSQL (JVM)

The default driver for JVM applications using JDBC:

```kotlin
val driver = PostgresDriver(
    host = "localhost",
    port = 5432,
    database = "myapp",
    username = "postgres",
    password = "password"
)
DatabaseDriverRegistry.setDriver(driver)
```

### Supabase

Use Supabase as a backend via PostgREST API. Works on all platforms including Wasm.

```kotlin
// Using environment variables (JVM)
val driver = SupabaseDriver.fromEnvironment()

// Or configure manually
val driver = SupabaseDriver(
    projectUrl = "https://yourproject.supabase.co",
    apiKey = "your-anon-key"
)
DatabaseDriverRegistry.setDriver(driver)
```

**Supported operations:**
- SELECT, INSERT, UPDATE, DELETE
- WHERE clauses (AND, OR, IN, IS_NULL, IS_NOT_NULL)
- ORDER BY, LIMIT, OFFSET pagination

**Environment variables:**
- `SUPABASE_URL` - Your Supabase project URL
- `SUPABASE_KEY` - Your Supabase anon/service key

### Firestore

Use Google Firestore as a document database backend. Works on all platforms.

```kotlin
// Using environment variables (JVM)
val driver = FirestoreDriver.fromEnvironment()

// Or configure manually
val driver = FirestoreDriver(
    projectId = "your-gcp-project",
    apiKey = "your-api-key"
)
DatabaseDriverRegistry.setDriver(driver)
```

**Supported operations:**
- SELECT, INSERT, UPDATE, DELETE
- WHERE clauses (AND, OR, IN)
- ORDER BY, LIMIT

**Limitations (NoSQL):**
- No JOINs
- No LIKE operator
- No DISTINCT
- No NOT operator

**Environment variables:**
- `FIRESTORE_PROJECT_ID` - Your GCP project ID
- `FIRESTORE_API_KEY` - Your Firebase API key

## Model Signals

The database module emits signals for model lifecycle events:

```kotlin
import codes.yousef.aether.db.signals.ModelSignals

// Before save (insert or update)
ModelSignals.preSave.connect { event ->
    println("Saving to ${event.model.tableName}")
}

// After save
ModelSignals.postSave.connect { event ->
    if (event.created) {
        println("Created new record")
    }
}

// Before/after delete
ModelSignals.preDelete.connect { event -> ... }
ModelSignals.postDelete.connect { event -> ... }
```

See the [Signals API](signals.md) for more details.
