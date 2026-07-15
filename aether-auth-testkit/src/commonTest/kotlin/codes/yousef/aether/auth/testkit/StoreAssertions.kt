package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.IdentityStoreErrorCode
import codes.yousef.aether.auth.StoreResult
import kotlin.test.assertEquals
import kotlin.test.fail

internal fun <T> StoreResult<T>.expectSuccess(): T = when (this) {
    is StoreResult.Success -> value
    is StoreResult.Failure -> fail("Expected store success, got ${error.code}")
}

internal fun StoreResult<*>.expectFailure(code: IdentityStoreErrorCode): StoreResult.Failure = when (this) {
    is StoreResult.Success -> fail("Expected store failure $code, got success")
    is StoreResult.Failure -> also { assertEquals(code, error.code) }
}
