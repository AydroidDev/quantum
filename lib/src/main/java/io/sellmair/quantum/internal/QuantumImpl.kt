package io.sellmair.quantum.internal

import io.sellmair.quantum.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/*
################################################################################################
INTERNAL API
################################################################################################
*/

internal class QuantumImpl<T>(
    initial: T,
    private val subject: StateSubject<T>) :

    Quantum<T>,
    StateObservable<T> by subject {

    /*
    ################################################################################################
    API
    ################################################################################################
    */


    override fun setState(reducer: Reducer<T>) {
        if (!running || stopping) return

        lock.withLock {
            pendingReducers.add(reducer)
            condition.signalAll()
        }
    }

    override fun setStateIt(reducer: ItReducer<T>) {
        if (!running || stopping) return
        this.setState(reducer)
    }

    override fun withState(action: Action<T>) {
        if (!running || stopping) return
        lock.withLock {
            pendingActions.add(action)
            condition.signalAll()
        }
    }

    override fun withStateIt(action: ItAction<T>) {
        if (!running || stopping) return
        withState(action)
    }


    override fun quit(): Joinable {
        this.running = false
        lock.withLock {
            condition.signalAll()
        }

        return worker.asJoinable()
    }

    override fun quitSafely(): Joinable {
        lock.withLock {
            stopping = true
            condition.signalAll()
        }

        return worker.asJoinable()
    }

    override val history: MutableHistory<T> = SynchronizedHistory<T>().apply { enabled = false }

    /*
    ################################################################################################
    PRIVATE
    ################################################################################################
    */


    /* State */
    /**
     * Represents the current state.
     * This reference is only allowed to be altered by the [worker] thread.
     */
    private var internalState: T = initial

    /**
     * History of all states sequentially started by the initial state,
     * ended by the current [internalState]
     */
    private val internalHistory = mutableListOf<T>()


    /* CONCURRENCY MEMBERS */
    /**
     * Lock to synchronize communication between any other thread an [worker] thread.
     */
    private val lock = ReentrantLock()

    /**
     * Condition used to notify the state thread, that pending work arrived.
     */
    private val condition = lock.newCondition()

    /**
     * Flag indicating whether or not the [worker] thread should stay alive.
     * @see quit
     */
    private var running by AtomicBoolean(true)


    /**
     * Indicating that [quitSafely] was called.
     *
     *
     * It is possible that [running] is true while [stopping] is also true.
     * This state is indicating that the last cycle is running
     * while no more actions or reducers should be enqueued
     *
     */
    private var stopping by AtomicBoolean(false)


    /* JOBS */
    /**
     * All reducers that are currently in the pipeline.
     * Reducers that should be executed last will be last in this list
     */
    private val pendingReducers = mutableListOf<Reducer<T>>()

    /**
     * All pending actions that are currently in the pipeline.
     * Actions that should be executed last will be last in this list
     */
    private val pendingActions = mutableListOf<Action<T>>()


    /**
     * The internal thread for this state holder.
     * All reducers and actions will be invoked by this thread only.
     * This thread is the only thread allowed to update the [internalHistory]
     * or set the [internalState]
     */
    private val worker = object : Thread("StateStoreWorker") {
        override fun run() {
            /*
            Loop until close is called (which then will set this flag to false)
             */
            while (running) {
                /*
               Stopping indicates that all reducers / actions that are currently eqnueued are
               supposed to be executed (and no more shall be enqueued).
               This means, that the thread is supposed to perform exactly one more loop
               once this flag was set.
               */
                if (stopping) running = false

                /*
                Du a full cycle:
                Apply reducers, perform actions & notify listener
                 */
                cycle()


                /*
                Enter the lock to check weather or not there is anything left to do
                 */
                lock.withLock {
                    /*
                    Do not go to sleep if there are (new) reducers pending.
                    Do not go to sleep if not running anymore
                    Do not go to sleep if currently stopping
                     */
                    if (pendingReducers.isEmpty() && running && !stopping) {
                        condition.await()
                    }
                }
            }
        }

        private fun cycle() {
            /*
            Store instance of current state (before creating new states)
             */
            val previousState = internalState

            /*
            Apply all reducers (which will alter the internalState referennce)
             */
            applyReducers()

            /*
            Invoke all actions with the new internal state
             */
            invokeActions()

            /*
            Only publish the state if it actually changed.
            Reducers are allowed to return the current instance to
            signal that a NOOP was done!
             */
            if (previousState != internalState) {
                subject.publish(internalState)
            }
        }

        private fun applyReducers() {
            for (reducer in reducers()) {
                internalState = reducer(internalState)
                internalHistory.add(internalState)
            }
        }

        private fun invokeActions() {
            for (action in actions()) {
                action(internalState)
            }
        }

        private fun reducers(): List<Reducer<T>> {
            lock.withLock {
                val list = listOf(*pendingReducers.toTypedArray())
                pendingReducers.clear()
                return list
            }
        }

        private fun actions(): List<Action<T>> = lock.withLock {
            lock.withLock {
                val list = listOf(*pendingActions.toTypedArray())
                pendingActions.clear()
                return list
            }
        }


    }


    init {
        subject.publish(initial)
        worker.start()
    }

}