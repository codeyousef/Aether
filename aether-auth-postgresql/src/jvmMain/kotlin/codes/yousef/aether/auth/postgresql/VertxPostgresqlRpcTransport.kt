package codes.yousef.aether.auth.postgresql

import io.vertx.pgclient.PgException
import io.vertx.core.json.JsonObject as VertxJsonObject
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JVM transport that invokes the same versioned functions directly through Vert.x SQL client. */
class VertxPostgresqlRpcTransport(
    private val config: PostgresqlIdentityConfig,
    private val client: SqlClient,
    private val json: Json = defaultPostgresqlJson()
) : PostgresqlRpcTransport {
    override suspend fun execute(request: PostgresqlRpcRequestEnvelope): PostgresqlRpcResponseEnvelope {
        val operation = operationFor(request)
        val encoded = json.encodeToString(request)
        if (encoded.encodeToByteArray().size > config.maximumRequestBytes) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
        }

        val responseText = try {
            val rows = client.preparedQuery(
                "SELECT ${config.schema}.${operation.functionName}(\$1::jsonb)::text AS response"
            ).execute(Tuple.of(VertxJsonObject(encoded))).coAwait()
            val iterator = rows.iterator()
            if (!iterator.hasNext()) throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
            iterator.next().getString("response")
                ?: throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PostgresqlStoreException) {
            throw failure
        } catch (failure: PgException) {
            throw PostgresqlStoreException(
                PostgresqlFailureMapper.fromProviderCode(failure.sqlState)
            )
        } catch (_: Throwable) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.unavailable())
        }

        if (responseText.encodeToByteArray().size > config.maximumResponseBytes) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
        }
        val decoded = try {
            json.decodeFromString<PostgresqlRpcResponseEnvelope>(responseText)
        } catch (_: SerializationException) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
        } catch (_: IllegalArgumentException) {
            throw PostgresqlStoreException(PostgresqlFailureMapper.internal())
        }
        validateResponse(request, decoded)
        return decoded
    }
}
