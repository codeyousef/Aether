package codes.yousef.aether.auth

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.db.eq
import kotlin.random.Random

import codes.yousef.aether.auth.crypto.PasswordHasher

object Auth {
    private const val SESSION_COOKIE_NAME = "sessionid"
    private const val SESSION_AGE = 1209600L // 2 weeks in seconds

    suspend fun authenticate(username: String, password: String, userModel: UserModel<*>): AbstractUser<*>? {
        // We need to cast userModel to access generic fields if needed, 
        // but here we just need to query by username.
        // However, userModel.username is ColumnProperty<T, String>.
        // We can use it directly.
        
        val user = userModel.objects.filter(
            userModel.username eq username
        ).firstOrNull()

        if (user != null) {
            val hashedPassword = user.password
            if (hashedPassword != null && PasswordHasher.verify(password, hashedPassword)) {
                return user
            }
        }
        return null
    }

    suspend fun login(exchange: Exchange, user: AbstractUser<*>) {
        // 1. Create session key
        val sessionKey = generateSessionKey()
        
        // 2. Create session record
        val session = Session()
        session.sessionKey = sessionKey
        session.sessionData = user.id.toString() // Store user ID in session data
        session.expireDate = SystemClock.now() + (SESSION_AGE * 1000)
        session.save()
        
        // 3. Set cookie
        exchange.response.cookies.add(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = sessionKey,
                maxAge = SESSION_AGE,
                path = "/",
                httpOnly = true,
                secure = false // Should be true in production
            )
        )
        
        // 4. Attach user to exchange
        // We need to cast UserKey to AttributeKey<Any> because of generics variance issues
        // or ensure user matches exactly.
        // UserKey is AttributeKey<AbstractUser<*>>. user is AbstractUser<*>.
        // This should match.
        exchange.attributes.put(UserKey, user)
    }

    suspend fun logout(exchange: Exchange) {
        val sessionKey = exchange.request.cookies[SESSION_COOKIE_NAME]?.value
        if (sessionKey != null) {
            // Delete session
            val session = Sessions.objects.filter(
                Sessions.sessionKey eq sessionKey 
            ).firstOrNull()
            session?.delete()
        }
        
        // Clear cookie
        exchange.response.cookies.add(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = "",
                maxAge = 0,
                path = "/",
                httpOnly = true
            )
        )
        
        exchange.attributes.remove(UserKey)
    }
    
    private fun generateSessionKey(): String {
        val charPool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..32)
            .map { Random.nextInt(0, charPool.length) }
            .map(charPool::get)
            .joinToString("")
    }
}
