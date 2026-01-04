package codes.yousef.aether.signals

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SignalTest {

    @Test
    fun testBasicSignal() = runTest {
        val signal = Signal<String>()
        var received: String? = null

        signal.connect { received = it }
        signal.send("hello")

        assertEquals("hello", received)
    }

    @Test
    fun testMultipleReceivers() = runTest {
        val signal = Signal<Int>()
        val results = mutableListOf<Int>()

        signal.connect { results.add(it * 1) }
        signal.connect { results.add(it * 2) }
        signal.connect { results.add(it * 3) }

        signal.send(10)

        assertEquals(listOf(10, 20, 30), results)
    }

    @Test
    fun testDisconnect() = runTest {
        val signal = Signal<String>()
        var callCount = 0

        val disposable = signal.connect { callCount++ }

        signal.send("first")
        assertEquals(1, callCount)

        disposable.dispose()

        signal.send("second")
        assertEquals(1, callCount) // Should not increase
    }

    @Test
    fun testConnectOnce() = runTest {
        val signal = Signal<String>()
        var callCount = 0

        signal.connectOnce { callCount++ }

        signal.send("first")
        signal.send("second")
        signal.send("third")

        assertEquals(1, callCount) // Only called once
    }

    @Test
    fun testParallelDispatch() = runTest {
        val signal = Signal<Int>(SignalConfig(parallel = true))
        val results = mutableListOf<Int>()
        val mutex = Mutex()

        repeat(100) { i ->
            signal.connect {
                mutex.withLock { results.add(i) }
            }
        }

        signal.send(0)

        assertEquals(100, results.size)
    }

    @Test
    fun testContinueOnError() = runTest {
        val signal = Signal<String>(SignalConfig(continueOnError = true))
        val results = mutableListOf<String>()

        signal.connect { results.add("first") }
        signal.connect { throw RuntimeException("error") }
        signal.connect { results.add("third") }

        assertFailsWith<SignalDispatchException> {
            signal.send("test")
        }

        assertEquals(listOf("first", "third"), results)
    }

    @Test
    fun testStopOnError() = runTest {
        val signal = Signal<String>(SignalConfig(continueOnError = false))
        val results = mutableListOf<String>()

        signal.connect { results.add("first") }
        signal.connect { throw RuntimeException("error") }
        signal.connect { results.add("third") }

        assertFailsWith<RuntimeException> {
            signal.send("test")
        }

        assertEquals(listOf("first"), results)
    }

    @Test
    fun testDisconnectAll() = runTest {
        val signal = Signal<String>()
        var callCount = 0

        signal.connect { callCount++ }
        signal.connect { callCount++ }
        signal.connect { callCount++ }

        assertEquals(3, signal.receiverCount)

        signal.disconnectAll()

        assertEquals(0, signal.receiverCount)

        signal.send("test")
        assertEquals(0, callCount)
    }

    @Test
    fun testReceiverCount() {
        val signal = Signal<String>()

        assertFalse(signal.hasReceivers)
        assertEquals(0, signal.receiverCount)

        val d1 = signal.connect { }
        assertEquals(1, signal.receiverCount)
        assertTrue(signal.hasReceivers)

        val d2 = signal.connect { }
        assertEquals(2, signal.receiverCount)

        d1.dispose()
        assertEquals(1, signal.receiverCount)

        d2.dispose()
        assertEquals(0, signal.receiverCount)
        assertFalse(signal.hasReceivers)
    }

    @Test
    fun testNoReceiversDoesNotThrow() = runTest {
        val signal = Signal<String>()
        signal.send("test") // Should not throw
    }

    @Test
    fun testTypedSignal() = runTest {
        data class User(val id: Int, val name: String)

        val userSignal = TypedSignal<User>()
        var receivedUser: User? = null

        userSignal.connect { user ->
            receivedUser = user
        }

        val testUser = User(1, "Alice")
        userSignal.send(testUser, isNew = true)

        assertEquals(testUser, receivedUser)
    }
}
