# Migrations API

Aether DB supports database migrations to manage schema changes over time.

## Migration Class

Define migrations by extending the `Migration` class.

```kotlin
class M20251222_CreateUsers : Migration(version = 1) {
    override suspend fun up() {
        schema.create(Users)
    }

    override suspend fun down() {
        schema.drop(Users)
    }
}
```

## Running Migrations

You can run migrations programmatically at application startup.

```kotlin
val migrator = Migrator(driver)
migrator.add(M20251222_CreateUsers())
// ... add other migrations

migrator.migrate() // Applies pending migrations
```

## Schema Operations

The `schema` object within a migration provides DDL helpers:

*   `create(model: Model<*>)`: Creates the table for the model.
*   `drop(model: Model<*>)`: Drops the table.
*   `alter(model: Model<*>) { ... }`: Alters the table (add/remove columns).
