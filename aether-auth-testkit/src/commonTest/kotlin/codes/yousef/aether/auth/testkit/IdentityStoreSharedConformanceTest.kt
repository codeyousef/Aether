package codes.yousef.aether.auth.testkit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IdentityStoreSharedConformanceTest {
    @Test
    fun `in-memory store satisfies the reusable adapter contract`() = runTest {
        val report = IdentityStoreConformanceSuite(
            store = InMemoryIdentityStore(),
            namespace = "in-memory"
        ).runAll()

        assertEquals(IdentityStoreConformanceCase.entries.toSet(), report.cases)
    }
}
