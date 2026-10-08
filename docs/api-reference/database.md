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

`DatabaseDriver` executes `QueryAST` operations and fixed SQL with bound values. Values must never
be interpolated into SQL:

```kotlin
val rows = driver.executeQuery(
    "SELECT id, payload, created_at FROM objects WHERE owner_account_id = ${'$'}1",
    listOf(SqlValue.StringValue(ownerAccountId))
)
val id = rows.single().getUuid("id")
val payload = rows.single().getBytes("payload")
val createdAt = rows.single().getUtcTimestamp("created_at")
```

PostgreSQL binds `String`, `Int`, `Long`, `Double`, `Boolean`, UUID, `bytea`, UTC `timestamptz`, and
SQL `NULL` as native protocol values. `UuidValue` validates canonical UUID text when constructed;
`UtcTimestampValue` accepts a `kotlin.time.Instant`. Remote adapters fail with
`DatabaseFeatureUnsupportedException` when a native type has no lossless representation.

`Row.hasColumn(name)` and `Row.isNull(name)` distinguish an absent column from SQL `NULL`. Typed
accessors return `null` for either state and throw a data-free `DatabaseRowAccessException` for a
present value of the wrong type.

SQL identifiers cannot be parameters. `SqlTranslator` accepts only validated schema-defined
identifiers; table, column, account, queue, device, object, and envelope values belong in
`SqlValue` parameters. `executeQueryRaw` is deprecated because it cannot enforce that boundary.

The `DatabaseDriverRegistry` holds the global driver instance.

## Database Backends

### PostgreSQL (JVM)

The JVM adapter uses the Vert.x reactive PostgreSQL client:

```kotlin
val driver = VertxPgDriver.create(
    host = "localhost",
    port = 5432,
    database = "myapp",
    user = "postgres",
    password = environmentSecret
)
DatabaseDriverRegistry.initialize(driver)
```

In the default `PRIVATE_PRODUCTION` diagnostics profile, failures throw
`DatabaseOperationException` containing only a `DatabaseOperation` and a validated five-character
SQLSTATE when PostgreSQL supplied one. SQL text, parameters, connection URLs, provider detail and
the raw cause are absent. `DiagnosticsProfile.DEVELOPMENT` may retain the cause for a separately
controlled local diagnostic process; never enable it in private production.

### Transactions

`VertxPgDriver` implements the additive `TransactionalDatabaseDriver` capability. Code that accepts
a general `DatabaseDriver` can call `withTransaction`; an adapter without the capability throws a
typed `UNSUPPORTED` failure before entering the block.

```kotlin
driver.withTransaction(
    TransactionOptions(isolation = TransactionIsolation.SERIALIZABLE)
) { tx ->
    tx.execute(
        """
        UPDATE objects
        SET revision = revision + 1
        WHERE owner_account_id = ${'$'}1 AND id = ${'$'}2 AND revision = ${'$'}3
        """.trimIndent(),
        listOf(ownerId, objectId, expectedRevision)
    )
}
// Publish an outbox wake hint only after withTransaction returns.
```

The callback receives one connection-bound driver. Calls from sibling coroutines are serialized;
nested transactions, closing that driver, and using it after the callback returns are rejected.
Acquire, statement, whole-transaction, rollback, and release work have independent finite bounds.
Failures or cancellation before commit roll back. Cleanup runs in a short non-cancellable budget,
then the connection is released. A commit timeout or acknowledgement loss reports
`COMMIT_OUTCOME_UNKNOWN`; it is not proof of rollback. Resolve it through a persisted idempotency
receipt rather than rerunning an externally effectful callback.

`READ_COMMITTED` and `SERIALIZABLE` are explicit. PostgreSQL `40001` serialization conflicts and
`40P01` deadlocks become typed retryable failures, but Aether never retries the callback
automatically. `executeCompareAndSet` requires exactly one changed row: zero reports the
enumeration-safe `REVISION_CONFLICT`, while more than one reports `INVALID_ROW_COUNT`. Include both
tenant and revision predicates in caller-owned SQL.

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
