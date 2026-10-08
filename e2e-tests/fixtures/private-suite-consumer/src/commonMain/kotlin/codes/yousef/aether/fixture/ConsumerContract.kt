package codes.yousef.aether.fixture

import kotlinx.serialization.Serializable

@Serializable
data class HealthPayload(val status: String)

const val EXPECTED_HEALTH_STATUS: String = "healthy"
