package codes.yousef.aether.auth

import codes.yousef.aether.db.BaseEntity
import codes.yousef.aether.db.Model
import codes.yousef.aether.db.varchar
import codes.yousef.aether.db.text
import codes.yousef.aether.db.long

class Session : BaseEntity<Session>() {
    override fun getModel() = Sessions

    var sessionKey: String?
        get() = Sessions.sessionKey.getValue(this)
        set(value) = Sessions.sessionKey.setValue(this, value)

    var sessionData: String?
        get() = Sessions.sessionData.getValue(this)
        set(value) = Sessions.sessionData.setValue(this, value)

    var expireDate: Long?
        get() = Sessions.expireDate.getValue(this)
        set(value) = Sessions.expireDate.setValue(this, value)
}

object Sessions : Model<Session>() {
    override val tableName = "aether_sessions"
    
    val sessionKey = varchar("session_key", length = 40, unique = true, primaryKey = true)
    val sessionData = text("session_data")
    val expireDate = long("expire_date")
    
    override fun createInstance() = Session()
}
