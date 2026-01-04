package codes.yousef.aether.example

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.signals.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the Aether Signals system.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignalsE2ETest {

    @BeforeEach
    fun clearSignals() {
        // Disconnect all handlers before each test
        Signals.preSave.disconnectAll()
        Signals.postSave.disconnectAll()
        Signals.preDelete.disconnectAll()
        Signals.postDelete.disconnectAll()
    }

    @Test
    fun `test basic signal connect and send`() = runBlocking(AetherDispatcher.dispatcher) {
        val signal = Signal<String>()
        val receivedValues = CopyOnWriteArrayList<String>()
        
        signal.connect { value ->
            receivedValues.add(value)
        }
        
        signal.send("hello")
        signal.send("world")
        
        assertEquals(2, receivedValues.size)
        assertEquals("hello", receivedValues[0])
        assertEquals("world", receivedValues[1])
    }

    @Test
    fun `test signal with multiple receivers`() = runBlocking(AetherDispatcher.dispatcher) {
        val signal = Signal<Int>()
        val counter = AtomicInteger(0)
        
        repeat(5) {
            signal.connect { value ->
                counter.addAndGet(value)
            }
        }
        
        signal.send(10)
        
        assertEquals(50, counter.get())
    }

    @Test
    fun `test signal disconnect via disposable`() = runBlocking(AetherDispatcher.dispatcher) {
        val signal = Signal<String>()
        val receivedValues = CopyOnWriteArrayList<String>()
        
        val disposable = signal.connect { value ->
            receivedValues.add(value)
        }
        
        signal.send("first")
        disposable.dispose()
        signal.send("second")
        
        assertEquals(1, receivedValues.size)
        assertEquals("first", receivedValues[0])
    }

    @Test
    fun `test parallel signal dispatch`() = runBlocking(AetherDispatcher.dispatcher) {
        val signal = Signal<Int>(SignalConfig(parallel = true))
        val receivedValues = CopyOnWriteArrayList<Int>()
        
        repeat(3) { index ->
            signal.connect { value ->
                delay(10)
                receivedValues.add(value * (index + 1))
            }
        }
        
        signal.send(5)
        
        delay(100)
        
        assertEquals(3, receivedValues.size)
        assertTrue(receivedValues.contains(5))
        assertTrue(receivedValues.contains(10))
        assertTrue(receivedValues.contains(15))
    }

    @Test
    fun `test disconnect all receivers`() = runBlocking(AetherDispatcher.dispatcher) {
        val signal = Signal<String>()
        val counter = AtomicInteger(0)
        
        repeat(5) {
            signal.connect { counter.incrementAndGet() }
        }
        
        signal.send("first")
        assertEquals(5, counter.get())
        
        signal.disconnectAll()
        signal.send("second")
        
        assertEquals(5, counter.get())
    }

    @Test
    fun `test built-in signals exist`() {
        kotlin.test.assertNotNull(Signals.preSave)
        kotlin.test.assertNotNull(Signals.postSave)
        kotlin.test.assertNotNull(Signals.preDelete)
        kotlin.test.assertNotNull(Signals.postDelete)
        kotlin.test.assertNotNull(Signals.requestStarted)
        kotlin.test.assertNotNull(Signals.requestFinished)
    }

    @Test
    fun `test signal config options`() {
        val parallelConfig = SignalConfig(parallel = true)
        assertTrue(parallelConfig.parallel)
        
        val continueConfig = SignalConfig(continueOnError = false)
        kotlin.test.assertFalse(continueConfig.continueOnError)
        
        val defaultConfig = SignalConfig()
        kotlin.test.assertFalse(defaultConfig.parallel)
        assertTrue(defaultConfig.continueOnError)
    }

    @Test
    fun `test typed signal`() = runBlocking(AetherDispatcher.dispatcher) {
        data class UserEvent(val userId: Int, val action: String)
        
        val signal = TypedSignal<UserEvent>()
        val captured = CopyOnWriteArrayList<UserEvent>()
        
        signal.connect { event ->
            captured.add(event)
        }
        
        signal.send(UserEvent(1, "login"))
        signal.send(UserEvent(2, "logout"))
        
        assertEquals(2, captured.size)
        assertEquals(1, captured[0].userId)
        assertEquals("logout", captured[1].action)
    }

    @Test
    fun `test signal with exception continues`() = runBlocking(AetherDispatcher.dispatcher) {
        val signal = Signal<String>(SignalConfig(continueOnError = true))
        val successfulCalls = AtomicInteger(0)
        
        signal.connect { _ ->
            throw RuntimeException("Intentional error")
        }
        
        signal.connect { _ ->
            successfulCalls.incrementAndGet()
        }
        
        try {
            signal.send("test")
        } catch (e: Exception) {
            // Expected
        }
        
        // Second receiver should still be called with continueOnError=true
        assertEquals(1, successfulCalls.get())
    }
}
