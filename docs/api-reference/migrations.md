# Migrations API

Aether applies reviewed PostgreSQL migrations with an advisory lock, an applied-migration journal,
and one transaction for DDL plus the journal row. The identity module keeps its separate migration
history and runner.

## Reviewed transactional migrations

```kotlin
val runner = MigrationRunner(driver)
runner.register(
    SimpleMigration(
        version = 2026100801,
        description = "add media type",
        upSql = "ALTER TABLE private_objects ADD COLUMN media_type TEXT",
        downSql = "ALTER TABLE private_objects DROP COLUMN media_type"
    )
)
val result = runner.migrate()
check(result.success)
```

`Migration.checksum` is SHA-256 over the exact UTF-8 bytes returned by `up()`. The journal stores a
unique version, checksum, description, and UTC `timestamptz` application time. Registration rejects
duplicate versions. Under the database lock, the runner re-reads the journal and rejects a changed
checksum before executing application DDL. Failed PostgreSQL DDL and its journal insert roll back
together; a restart safely retries the unapplied version.

Legacy `_aether_migrations` tables without a checksum column are upgraded atomically under the
migration lock. The runner backfills SHA-256 only from matching registered, reviewed migration SQL
and then makes the column mandatory. An unknown historical version fails with
`JOURNAL_UPGRADE_REQUIRED`; restore/register the exact reviewed source rather than inventing a
checksum.

Pass a lowercase ASCII `stream` name of at most 27 characters when a module owns an independent
migration set. The default application stream uses `_aether_migrations`; `stream = "tasks"` uses
`_aether_migrations_tasks`. Streams keep unrelated version/checksum histories separate while all
DDL and prepared operator work share the global advisory lock and fence.

Do not edit an applied migration. Add a forward repair with a new version. Expansion is the default:
add nullable columns/tables/indexes first, deploy readers compatible with both schemas, backfill, and
only then author a contract migration with an explicit retirement gate:

```kotlin
val contract = migration(2026100802, "retire legacy column") {
    contract(retirementGate = "all readers >= 0.8.0")
    up("ALTER TABLE private_objects DROP COLUMN legacy_value")
}
```

## Generated candidates

`MigrationGenerator` and Aether KSP output **unreviewed candidates**. Production
`MigrationRunner` rejects `MigrationSource.GENERATED_CANDIDATE`. KSP emits only expand candidates;
drops, renames, type changes, and constraint/index removal fail generation and require a manually
authored, reviewed operator migration. Check the final SQL and schema snapshot into source control
before changing its source to `REVIEWED` and registering it. Generated output is never a production
startup instruction.

## Nontransactional PostgreSQL operations

Operations such as `CREATE INDEX CONCURRENTLY` cannot share the transactional path. Mark the
migration `transactional = false`; normal startup will reject it. Operator tooling must use
`MigrationExecutionProfile.OPERATOR`:

1. Call `prepareNonTransactional(version)`. This records or resumes a checksummed `prepared` state
   and returns the exact SQL plan without executing it.
2. Execute the SQL with the deployment role outside the migration transaction.
3. Inspect the resulting schema and workload health.
4. Call `completeNonTransactional(plan, schemaVerified = true)`. The runner verifies the resume
   record under lock, writes the applied journal row, and removes resume state atomically.

A crash before completion leaves resume state, not a false applied row. Re-running `prepare` returns
the same checksummed plan. A checksum mismatch or missing resume state fails closed.

While a nontransactional plan is prepared, normal startup migration and rollback fail with
`OPERATOR_WORK_PENDING`; this prevents later schema versions from racing operator SQL.

## Rollback, reset, backup, and repair

The default `PRODUCTION_STARTUP` profile refuses `rollback()` and `reset()`. Only explicit operator
profile tooling can invoke them. A missing `down()` fails without deleting journal state. Never use
reset as production recovery.

`reset()` holds one transaction and advisory lock across the full reverse sequence, so a missing or
failed `down()` restores every earlier table and journal row from that reset attempt.

Before migration, take a transactionally consistent PostgreSQL backup containing application data,
`_aether_migrations`, and `_aether_nontransactional_migrations`; verify restoration in an isolated
database. Preserve encrypted payload bytes exactly—schema migration does not decrypt or rewrite
ciphertext. Prefer forward repair after deployment. If rollback is reviewed and safe, rehearse it
against the restored backup, verify old-reader compatibility and checksums, then execute it through
the operator profile with destructive scope recorded in the change ticket.

## KSP candidate promotion

KSP writes two review artifacts without changing the active baseline:

- `migrations/V<version>__<description>.sql.candidate`
- `<configured-schema-file>.candidate`

Review the SQL, rehearse it against a restored backup, and verify that the candidate snapshot
matches the resulting schema. Promote both artifacts in one change: rename the SQL by removing
`.candidate`, change its marker to `-- Status: REVIEWED`, and replace the active schema snapshot
with its candidate. The CLI rejects candidate filenames and `UNREVIEWED` content, then runs reviewed
files through the same advisory-locked, checksummed transactional runner as application startup.
