package codes.yousef.aether.channels

import codes.yousef.aether.core.websocket.WebSocketCloseCode
import codes.yousef.aether.core.websocket.WebSocketSession
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Rechecks current application authority before an opaque wake hint is emitted. */
fun interface WakeHintAuthorizer {
    suspend fun isAuthorized(session: WebSocketSession, group: String): Boolean
}

/**
 * A one-slot, coalescing subscription for opaque durable-sync wake hints.
 *
 * Hints carry no application payload. Consumers recover authoritative state from their persisted
 * cursor, so duplicate, reordered, or dropped hints cannot become the source of truth.
 */
class AuthorizedWakeHintSubscription private constructor(
    private val session: WebSocketSession,
    private val group: String,
    private val layer: ChannelLayer,
    private val authorizer: WakeHintAuthorizer,
    parentScope: CoroutineScope
) {
    private val closed = atomic(false)
    private val hints = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val subscriptionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + subscriptionJob)
    private val sender = scope.launch {
        try {
            for (ignored in hints) {
                if (!authorizer.isAuthorized(session, group)) {
                    revoke()
                    break
                }
                session.sendText(OPAQUE_CHANGE_AVAILABLE_HINT)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Transport failure terminates this subscription; durable sync remains authoritative.
        } finally {
            if (detach()) subscriptionJob.cancel()
        }
    }

    /** Coalesces repeated notifications into at most one pending wake hint. */
    fun notifyChange(): Boolean = !closed.value && hints.trySend(Unit).isSuccess
    suspend fun close() {
        if (!detach()) return
        subscriptionJob.cancel()
        if (sender != kotlinx.coroutines.currentCoroutineContext()[Job]) sender.cancelAndJoin()
    }

    private suspend fun revoke() {
        if (!detach(WebSocketCloseCode.POLICY_VIOLATION, "authorization_revoked")) return
        subscriptionJob.cancel()
    }

    private suspend fun detach(closeCode: Int? = null, closeReason: String = ""): Boolean {
        if (!closed.compareAndSet(expect = false, update = true)) return false
        hints.close()
        layer.groupDiscard(group, session)
        if (closeCode != null) session.close(closeCode, closeReason)
        return true
    }

    companion object {
        const val OPAQUE_CHANGE_AVAILABLE_HINT: String = "{\"type\":\"changes_available\"}"

        /** Authorizes before joining, then returns an owned bounded subscription. */
        suspend fun open(
            session: WebSocketSession,
            group: String,
            layer: ChannelLayer = Channels.layer,
            authorizer: WakeHintAuthorizer,
            scope: CoroutineScope
        ): AuthorizedWakeHintSubscription? {
            if (!authorizer.isAuthorized(session, group)) {
                session.close(WebSocketCloseCode.POLICY_VIOLATION, "authorization_required")
                return null
            }
            layer.groupAdd(group, session)
            return AuthorizedWakeHintSubscription(session, group, layer, authorizer, scope)
        }
    }
}
