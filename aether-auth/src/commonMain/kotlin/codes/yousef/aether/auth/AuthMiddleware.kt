package codes.yousef.aether.auth

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.db.eq

val UserKey = AttributeKey("User", AbstractUser::class)

fun authMiddleware(userModel: UserModel<*>): Middleware = { exchange, next ->
    val cookie = exchange.request.cookies["sessionid"]
    if (cookie != null) {
        val sessionId = cookie.value
        val session = Sessions.objects.filter(
            Sessions.sessionKey eq sessionId
        ).firstOrNull()

        if (session != null) {
            val now = SystemClock.now()
            val expire = session.expireDate
            
            if (expire != null && expire > now) {
                // Valid session
                // For now, assume sessionData is just userId string
                val userId = session.sessionData?.toLongOrNull()
                if (userId != null) {
                    val user = userModel.get(userId)
                    if (user != null) {
                        @Suppress("UNCHECKED_CAST")
                        exchange.attributes.put(UserKey as AttributeKey<Any>, user)
                    }
                }
            }
        }
    }
    next()
}

val Exchange.user: AbstractUser<*>?
    get() = attributes.get(UserKey as AttributeKey<AbstractUser<*>>)
