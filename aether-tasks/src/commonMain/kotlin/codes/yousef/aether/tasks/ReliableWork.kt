package codes.yousef.aether.tasks

import codes.yousef.aether.db.DatabaseDriver
import codes.yousef.aether.db.DatabaseDriverRegistry
import codes.yousef.aether.db.Migration
import codes.yousef.aether.db.MigrationRunner
import codes.yousef.aether.db.SqlValue
import codes.yousef.aether.db.withTransaction

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/** Caller-defined idempotency namespace. No private data belongs in these fields. */
data class IdempotencyScope(
    val actorOrAccount: String,
    val operationKind: String,
    val clientOperationId: String
) {
    init {
        requireReliableToken(actorOrAccount, "actor or account", 128)
        requireReliableToken(operationKind, "operation kind", 128)
        requireReliableToken(clientOperationId, "client operation ID", 128)
    }
}

data class OutboxEvent(
    val id: String,
    val kind: String,
    val encryptedPayloadReference: String
) {
    init {
        requireReliableToken(id, "outbox event ID", 64)
        requireReliableToken(kind, "outbox event kind", 128)
        requireReliableToken(encryptedPayloadReference, "encrypted outbox reference", 512)
    }
}

data class IdempotentMutationResult(
    val encryptedResultReference: String,
    val outboxEvents: List<OutboxEvent> = emptyList()
) {
    init {
        requireReliableToken(encryptedResultReference, "encrypted result reference", 512)
        require(outboxEvents.size <= 1_000) { "Too many outbox events" }
    }
}

sealed interface IdempotentExecution {
    data class Executed(val encryptedResultReference: String) : IdempotentExecution
    data class Replay(val encryptedResultReference: String) : IdempotentExecution
    data object Conflict : IdempotentExecution
}

data class OutboxLease(
    val id: String,
    val kind: String,
    val encryptedPayloadReference: String,
    val owner: String,
    val generation: Long
)

data class EffectAttemptToken(val effectKey: String, val generation: Long)

sealed interface ExternalEffectClaim {
    data class Claimed(val token: EffectAttemptToken) : ExternalEffectClaim
    data class AlreadyAcknowledged(val providerAcknowledgementReference: String) : ExternalEffectClaim
    data object InFlight : ExternalEffectClaim
    data object Conflict : ExternalEffectClaim
}

/**
 * Atomic idempotency receipts, transactional outbox, and provider-effect reconciliation.
 * Digests are SHA-256 hex; payloads and results are opaque encrypted object references only.
 */
class DatabaseReliableWorkStore(
    private val driver: DatabaseDriver = DatabaseDriverRegistry.driver
) {
    suspend fun migrate() {
        val runner = MigrationRunner(driver, stream = "tasks")
        runner.register(TaskTableMigration)
        runner.register(LeasedTaskTableMigration)
        runner.register(ReliableWorkTableMigration)
        val result = runner.migrate()
        if (!result.success) throw result.errors.first().exception
    }

    suspend fun executeIdempotent(
        scope: IdempotencyScope,
        canonicalDigest: String,
        retentionSeconds: Long,
        mutation: suspend (DatabaseDriver) -> IdempotentMutationResult
    ): IdempotentExecution {
        require(SHA256_HEX.matches(canonicalDigest)) { "Digest must be lowercase SHA-256 hex" }
        require(retentionSeconds in TASK_RETRY_BUDGET_SECONDS..31_536_000L) {
            "Receipt retention must cover retries and be at most one year"
        }
        return driver.withTransaction { tx ->
            tx.execute(
                """
                DELETE FROM _aether_task_receipts
                WHERE actor_key = $1 AND operation_kind = $2 AND client_operation_id = $3
                  AND expires_at <= clock_timestamp()
                """.trimIndent(),
                scope.parameters()
            )
            val inserted = tx.execute(
                """
                INSERT INTO _aether_task_receipts(
                    actor_key, operation_kind, client_operation_id, canonical_digest,
                    state, created_at, updated_at, expires_at
                ) VALUES ($1, $2, $3, $4, 'IN_FLIGHT', clock_timestamp(), clock_timestamp(),
                    clock_timestamp() + $5 * INTERVAL '1 second')
                ON CONFLICT (actor_key, operation_kind, client_operation_id) DO NOTHING
                """.trimIndent(),
                scope.parameters(canonicalDigest) + SqlValue.LongValue(retentionSeconds)
            ) == 1

            if (!inserted) {
                val existing = tx.executeQuery(
                    """
                    SELECT canonical_digest, state, result_ref
                    FROM _aether_task_receipts
                    WHERE actor_key = $1 AND operation_kind = $2 AND client_operation_id = $3
                    FOR UPDATE
                    """.trimIndent(),
                    scope.parameters()
                ).single()
                if (existing.getString("canonical_digest") != canonicalDigest) {
                    return@withTransaction IdempotentExecution.Conflict
                }
                val result = existing.getString("result_ref")
                check(existing.getString("state") == "COMPLETED" && result != null) {
                    "Receipt is not replayable"
                }
                return@withTransaction IdempotentExecution.Replay(result)
            }

            val result = mutation(tx)
            result.outboxEvents.forEach { event ->
                val count = tx.execute(
                    """
                    INSERT INTO _aether_task_outbox(
                        id, event_kind, payload_ref, state, attempts, lease_generation, created_at
                    ) VALUES ($1, $2, $3, 'READY', 0, 0, clock_timestamp())
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                    listOf(
                        SqlValue.StringValue(event.id), SqlValue.StringValue(event.kind),
                        SqlValue.StringValue(event.encryptedPayloadReference)
                    )
                )
                check(count == 1) { "Outbox event ID already exists" }
            }
            val completed = tx.execute(
                """
                UPDATE _aether_task_receipts
                SET state = 'COMPLETED', result_ref = $5, updated_at = clock_timestamp()
                WHERE actor_key = $1 AND operation_kind = $2 AND client_operation_id = $3
                  AND canonical_digest = $4 AND state = 'IN_FLIGHT'
                """.trimIndent(),
                scope.parameters(canonicalDigest) + SqlValue.StringValue(result.encryptedResultReference)
            )
            check(completed == 1) { "Receipt ownership lost" }
            IdempotentExecution.Executed(result.encryptedResultReference)
        }
    }

    suspend fun claimOutbox(owner: String): OutboxLease? {
        requireReliableToken(owner, "outbox owner", 64)
        val row = driver.executeQuery(
            """
            WITH candidate AS (
                SELECT id FROM _aether_task_outbox
                WHERE state IN ('READY', 'UNKNOWN_OUTCOME')
                   OR (state = 'SENDING' AND lease_until <= clock_timestamp())
                ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            UPDATE _aether_task_outbox AS event
            SET state = 'SENDING', owner = $1,
                lease_until = clock_timestamp() + INTERVAL '60 seconds',
                lease_generation = event.lease_generation + 1,
                attempts = event.attempts + 1
            FROM candidate WHERE event.id = candidate.id
            RETURNING event.id, event.event_kind, event.payload_ref, event.lease_generation
            """.trimIndent(),
            listOf(SqlValue.StringValue(owner))
        ).singleOrNull() ?: return null
        return OutboxLease(
            id = requireNotNull(row.getString("id")),
            kind = requireNotNull(row.getString("event_kind")),
            encryptedPayloadReference = requireNotNull(row.getString("payload_ref")),
            owner = owner,
            generation = requireNotNull(row.getLong("lease_generation"))
        )
    }

    suspend fun acknowledgeOutbox(lease: OutboxLease, providerReference: String): Boolean {
        validateOutboxLease(lease)
        requireReliableToken(providerReference, "provider acknowledgement reference", 512)
        return driver.execute(
            """
            UPDATE _aether_task_outbox
            SET state = 'ACKNOWLEDGED', provider_ack_ref = $4, owner = NULL, lease_until = NULL
            WHERE id = $1 AND owner = $2 AND lease_generation = $3
              AND state = 'SENDING' AND lease_until > clock_timestamp()
            """.trimIndent(),
            lease.parameters() + SqlValue.StringValue(providerReference)
        ) == 1
    }

    suspend fun markOutboxUnknown(lease: OutboxLease): Boolean {
        validateOutboxLease(lease)
        return driver.execute(
            """
            UPDATE _aether_task_outbox
            SET state = 'UNKNOWN_OUTCOME', owner = NULL, lease_until = NULL
            WHERE id = $1 AND owner = $2 AND lease_generation = $3 AND state = 'SENDING'
            """.trimIndent(),
            lease.parameters()
        ) == 1
    }

    suspend fun claimExternalEffect(effectKey: String, canonicalDigest: String): ExternalEffectClaim {
        requireReliableToken(effectKey, "effect key", 256)
        require(SHA256_HEX.matches(canonicalDigest)) { "Digest must be lowercase SHA-256 hex" }
        return driver.withTransaction { tx ->
            tx.execute(
                """
                INSERT INTO _aether_task_effects(
                    effect_key, canonical_digest, state, generation, created_at, updated_at
                ) VALUES ($1, $2, 'READY', 0, clock_timestamp(), clock_timestamp())
                ON CONFLICT (effect_key) DO NOTHING
                """.trimIndent(),
                listOf(SqlValue.StringValue(effectKey), SqlValue.StringValue(canonicalDigest))
            )
            val existing = tx.executeQuery(
                """
                SELECT canonical_digest, state, generation, provider_ack_ref
                FROM _aether_task_effects WHERE effect_key = $1 FOR UPDATE
                """.trimIndent(),
                listOf(SqlValue.StringValue(effectKey))
            ).single()
            if (existing.getString("canonical_digest") != canonicalDigest) {
                return@withTransaction ExternalEffectClaim.Conflict
            }
            when (existing.getString("state")) {
                "ACKNOWLEDGED" -> ExternalEffectClaim.AlreadyAcknowledged(
                    requireNotNull(existing.getString("provider_ack_ref"))
                )
                "SENDING" -> ExternalEffectClaim.InFlight
                else -> {
                    val nextGeneration = requireNotNull(existing.getLong("generation")) + 1
                    val changed = tx.execute(
                        """
                        UPDATE _aether_task_effects
                        SET state = 'SENDING', generation = $2,
                            attempt_until = clock_timestamp() + INTERVAL '60 seconds',
                            updated_at = clock_timestamp()
                        WHERE effect_key = $1 AND state IN ('READY', 'UNKNOWN_OUTCOME')
                        """.trimIndent(),
                        listOf(SqlValue.StringValue(effectKey), SqlValue.LongValue(nextGeneration))
                    )
                    check(changed == 1) { "Effect claim lost" }
                    ExternalEffectClaim.Claimed(EffectAttemptToken(effectKey, nextGeneration))
                }
            }
        }
    }

    suspend fun acknowledgeExternalEffect(
        token: EffectAttemptToken,
        providerAcknowledgementReference: String
    ): Boolean {
        validateEffectToken(token)
        requireReliableToken(providerAcknowledgementReference, "provider acknowledgement reference", 512)
        return driver.execute(
            """
            UPDATE _aether_task_effects
            SET state = 'ACKNOWLEDGED', provider_ack_ref = $3, attempt_until = NULL,
                updated_at = clock_timestamp()
            WHERE effect_key = $1 AND generation = $2 AND state = 'SENDING'
            """.trimIndent(),
            listOf(
                SqlValue.StringValue(token.effectKey), SqlValue.LongValue(token.generation),
                SqlValue.StringValue(providerAcknowledgementReference)
            )
        ) == 1
    }

    suspend fun markExternalEffectUnknown(token: EffectAttemptToken): Boolean {
        validateEffectToken(token)
        return driver.execute(
            """
            UPDATE _aether_task_effects
            SET state = 'UNKNOWN_OUTCOME', attempt_until = NULL, updated_at = clock_timestamp()
            WHERE effect_key = $1 AND generation = $2 AND state = 'SENDING'
            """.trimIndent(),
            listOf(SqlValue.StringValue(token.effectKey), SqlValue.LongValue(token.generation))
        ) == 1
    }

    /** Moves abandoned sends to reconciliation state; this operation never repeats the effect. */
    suspend fun recoverExpiredExternalEffects(limit: Int = 1_000): Int {
        require(limit in 1..10_000) { "Recovery limit must be 1..10000" }
        return driver.execute(
            """
            WITH expired AS (
                SELECT effect_key FROM _aether_task_effects
                WHERE state = 'SENDING' AND attempt_until <= clock_timestamp()
                ORDER BY attempt_until, effect_key
                LIMIT $1 FOR UPDATE SKIP LOCKED
            )
            UPDATE _aether_task_effects AS effect
            SET state = 'UNKNOWN_OUTCOME', attempt_until = NULL, updated_at = clock_timestamp()
            FROM expired WHERE effect.effect_key = expired.effect_key
            """.trimIndent(),
            listOf(SqlValue.IntValue(limit))
        )
    }

    private fun validateOutboxLease(lease: OutboxLease) {
        requireReliableToken(lease.id, "outbox event ID", 64)
        requireReliableToken(lease.owner, "outbox owner", 64)
        require(lease.generation > 0) { "Outbox generation must be positive" }
    }

    private fun validateEffectToken(token: EffectAttemptToken) {
        requireReliableToken(token.effectKey, "effect key", 256)
        require(token.generation > 0) { "Effect generation must be positive" }
    }

    private fun IdempotencyScope.parameters(digest: String? = null): List<SqlValue> = buildList {
        add(SqlValue.StringValue(actorOrAccount))
        add(SqlValue.StringValue(operationKind))
        add(SqlValue.StringValue(clientOperationId))
        digest?.let { add(SqlValue.StringValue(it)) }
    }

    private fun OutboxLease.parameters(): List<SqlValue> = listOf(
        SqlValue.StringValue(id), SqlValue.StringValue(owner), SqlValue.LongValue(generation)
    )
}

private fun requireReliableToken(value: String, label: String, maxLength: Int) {
    require(value.isNotBlank() && value.length <= maxLength && value.none(Char::isISOControl)) {
        "Invalid $label"
    }
}

object ReliableWorkTableMigration : Migration {
    override val version: Long = 20261008000003L
    override val description: String = "Create idempotency receipts and effect ledgers"

    override fun up(): String = """
        CREATE TABLE IF NOT EXISTS _aether_task_receipts (
            actor_key VARCHAR(128) NOT NULL,
            operation_kind VARCHAR(128) NOT NULL,
            client_operation_id VARCHAR(128) NOT NULL,
            canonical_digest CHAR(64) NOT NULL,
            state VARCHAR(16) NOT NULL,
            result_ref VARCHAR(512),
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY(actor_key, operation_kind, client_operation_id),
            CHECK (canonical_digest ~ '^[0-9a-f]{64}$'),
            CHECK (state IN ('IN_FLIGHT', 'COMPLETED')),
            CHECK (expires_at > created_at),
            CHECK ((state = 'COMPLETED') = (result_ref IS NOT NULL))
        );
        CREATE INDEX IF NOT EXISTS idx_aether_task_receipts_expiry
            ON _aether_task_receipts(expires_at);

        CREATE TABLE IF NOT EXISTS _aether_task_outbox (
            id VARCHAR(64) PRIMARY KEY,
            event_kind VARCHAR(128) NOT NULL,
            payload_ref VARCHAR(512) NOT NULL,
            state VARCHAR(32) NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
            owner VARCHAR(64),
            lease_until TIMESTAMPTZ,
            lease_generation BIGINT NOT NULL DEFAULT 0 CHECK (lease_generation >= 0),
            provider_ack_ref VARCHAR(512),
            created_at TIMESTAMPTZ NOT NULL,
            CHECK (state IN ('READY', 'SENDING', 'UNKNOWN_OUTCOME', 'ACKNOWLEDGED')),
            CHECK ((state = 'SENDING') = (owner IS NOT NULL AND lease_until IS NOT NULL)),
            CHECK ((state = 'ACKNOWLEDGED') = (provider_ack_ref IS NOT NULL))
        );
        CREATE INDEX IF NOT EXISTS idx_aether_task_outbox_claim
            ON _aether_task_outbox(created_at, id)
            WHERE state IN ('READY', 'UNKNOWN_OUTCOME', 'SENDING');

        CREATE TABLE IF NOT EXISTS _aether_task_effects (
            effect_key VARCHAR(256) PRIMARY KEY,
            canonical_digest CHAR(64) NOT NULL,
            state VARCHAR(32) NOT NULL,
            generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
            attempt_until TIMESTAMPTZ,
            provider_ack_ref VARCHAR(512),
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CHECK (canonical_digest ~ '^[0-9a-f]{64}$'),
            CHECK (state IN ('READY', 'SENDING', 'UNKNOWN_OUTCOME', 'ACKNOWLEDGED')),
            CHECK ((state = 'ACKNOWLEDGED') = (provider_ack_ref IS NOT NULL)),
            CHECK ((state = 'SENDING') = (attempt_until IS NOT NULL))
        );
    """.trimIndent()

    override fun down(): String = """
        DROP TABLE IF EXISTS _aether_task_effects;
        DROP TABLE IF EXISTS _aether_task_outbox;
        DROP TABLE IF EXISTS _aether_task_receipts;
    """.trimIndent()
}
