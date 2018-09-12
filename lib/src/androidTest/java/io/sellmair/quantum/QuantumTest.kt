package io.sellmair.quantum

import android.os.HandlerThread
import android.support.test.runner.AndroidJUnit4
import io.sellmair.quantum.internal.QuantumImpl
import io.sellmair.quantum.internal.StateSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

@RunWith(AndroidJUnit4::class)
class QuantumTest {


    companion object {
        /**
         * Tests in this class are executed multiple times to ensure coverage for
         * race conditions and other strange behaviour.
         */
        const val REPETITIONS = 100
    }

    /**
     * The state used to test the quantum against.
     */
    data class TestState(val revision: Int = 0, val payload: Any? = null)

    /**
     * Quantum instance to test
     */
    private lateinit var quantum: Quantum<TestState>

    /**
     * Can be used to receive events and test against.
     */
    private lateinit var listener: TestListener

    /**
     * Thread that invokes the listener.
     * Needs to be closed within the test.
     */
    private lateinit var listenerThread: HandlerThread

    fun setup() {
        listener = TestListener()
        listenerThread = HandlerThread("Listener-Thread").also(Thread::start)
        quantum = QuantumImpl(TestState(), StateSubject(listenerThread.looper))
    }


    /**
     * Will test the the behaviour of the quantum for a single reducer.
     * It will assert that the listener is called exactly twice (initial state + reduced)
     * Also the initial and reduced state are asserted for their integrity.
     */
    @Test
    fun singleReducer() = repeat(REPETITIONS) {
        setup()
        quantum.addListener(listener)

        quantum.setState { copy(revision = 1) }
        quantum.quitSafely().join()
        listenerThread.quitSafely()
        listenerThread.join()

        assertEquals(2, listener.states.size)
        assertEquals(TestState(), listener.states[0])
        assertEquals(TestState(1), listener.states[1])
    }


    /**
     * Will enqueue multiple reducers and assert the integrity
     * of the initial and end state.
     */
    @Test
    fun multipleReducers() = repeat(REPETITIONS) {
        setup()
        quantum.addListener(listener)

        quantum.setState { copy(revision = 1) }
        quantum.setState { copy(revision = 2) }
        quantum.setState { copy(revision = 3) }
        quantum.setState { copy(revision = 4) }
        quantum.setState { copy(revision = 5) }
        quantum.setState { copy(revision = 6) }
        quantum.setState { copy(revision = 7) }

        quantum.quitSafely().join()
        listenerThread.quitSafely()
        listenerThread.join()

        assertEquals(TestState(), listener.states.first())
        assertEquals(TestState(7), listener.states.last())
    }

    /**
     * Will enqueue many reducers from many threads.
     * The integrity of the first as well as the last state are asserted.
     * The order of published states is asserted.
     */
    @Test
    fun multipleReducers_fromRandomThreads() = repeat(REPETITIONS) {

        /*
        Defines how many threads are created at once
         */
        val nThreads = 15

        /*
        Defines how many increments one thread should perform
         */
        val nIncrementsPerThread = 10000


        setup()
        quantum.addListener(listener)


        /*
        Hold a reference to all created threads to join them later
         */
        val threads = mutableListOf<Thread>()

        /*
        Dispatch all those reducers at once
         */
        repeat(nThreads) {
            val thread = thread {
                repeat(nIncrementsPerThread) {
                    quantum.setState { copy(revision = revision + 1) }
                }
            }

            threads.add(thread)
        }

        /*
        Now join on all of those threads to
        wait for all reducers to be enqueued
         */
        for (thread in threads) {
            thread.join()
        }


        /*
        Wait for shutdown
         */
        quantum.quitSafely().join()
        listenerThread.quitSafely()
        listenerThread.join()


        /*
        At least the initial and the final reduced state are expected.
         */
        assertTrue(listener.states.size >= 2)


        /*
        We expect each published state to have a higher revision number than the
        previous one. Otherwise the order would be bad!
         */
        listener.states.asSequence()
            .zipWithNext()
            .forEach { adjacentStates ->
                assertTrue(adjacentStates.first.revision < adjacentStates.second.revision)
            }


        /*
        Finally assert the initial and the final reduced state to
        be what we expect
         */
        assertEquals(TestState(), listener.states.first())
        assertEquals(
            TestState(revision = nThreads * nIncrementsPerThread),
            listener.states.last())
    }


    @Test
    fun quit_doesNotExecutePendingReducers() = repeat(REPETITIONS) {

        /*
        Lock and condition used to halt the first reducer.
        This is necessary to have pending reducers stacking up.
         */
        val lock = ReentrantLock()
        val condition = lock.newCondition()

        /* SETUP */
        setup()
        quantum.addListener(listener)


        /*
        Test thread entering the lock
         */
        lock.withLock {

            /*
            Dispatch first reducer
             */
            quantum.setState {
                lock.withLock {
                    /*
                    Wakeup test thread
                     */
                    condition.signalAll()

                    /*
                    Wait for test thread to signal this reducer to finish
                     */
                    condition.await()
                    copy(revision = 1)
                }
            }

            /*
            Wait for first reducer to start executing
             */
            condition.await()

            /*
            Add pending reducers
             */
            quantum.setState { throw AssertionError("Pending reducer called") }
            quantum.setState { throw AssertionError("Pending reducer called") }
            quantum.setState { throw AssertionError("Pending reducer called") }
            quantum.setState { throw AssertionError("Pending reducer called") }
            quantum.setState { throw AssertionError("Pending reducer called") }
            quantum.setState { throw AssertionError("Pending reducer called") }

            /*
            Quit the quantum now
             */
            quantum.quit().also {
                /*
                Let the first reducer finish now
                 */
                condition.signalAll()
            }
        } /*
        Finally join the quantum to die
        */.join()

        listenerThread.quitSafely()
        listenerThread.join()

        /* Expect initial state and reducer */
        assertEquals(2, listener.states.size)
        assertEquals(TestState(), listener.states.first())
        assertEquals(TestState(1), listener.states.last())
    }
}


class TestListener : (QuantumTest.TestState) -> Unit {
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    private val internalStates = mutableListOf<QuantumTest.TestState>()
    val states: List<QuantumTest.TestState>
        get() = lock.withLock {
            mutableListOf(*internalStates.toTypedArray())
        }

    override fun invoke(state: QuantumTest.TestState): Unit = lock.withLock {
        internalStates.add(state)
        condition.signalAll()
    }
}

