package io.sellmair.quantum

import io.sellmair.quantum.internal.*
import java.util.concurrent.Executor

/*
################################################################################################
PUBLIC API
################################################################################################
*/

typealias Reducer<T> = T.() -> T
typealias Action<T> = T.() -> Unit
typealias ItReducer<T> = (T) -> T
typealias ItAction<T> = (T) -> Unit
typealias StateListener<T> = (T) -> Unit
typealias QuittedListener = () -> Unit
/*
################################################################################################
PUBLIC API
################################################################################################
*/

interface Quantum<T> :
    Owner<T>,
    Chronological<T>,
    Quitable, QuitedObservable, StateObservable<T> {


    /**
     * Will enqueue the [reducer].
     * The [reducer] will be run on the internal state thread and should not
     * contain long running / blocking operations.
     * Only one reducer will running at a time.
     *
     *
     * The internal state will be updated according to the reducer.
     * The reducer is not allowed to manipulate the object directly and rather
     * should provide a copy of the object.
     *
     *
     * The reducer, however, is allowed to return the same unmodified instance
     * to signal a NO-OP to the state.
     *
     */
    override fun setState(reducer: Reducer<T>) {
        setStateFuture(reducer)
    }


    /**
     * Same as [setState], but wont use a receiver function
     */
    override fun setStateIt(reducer: ItReducer<T>) = setState(reducer)


    /**
     * Same as [setStateIt] but additionally returns a future
     * @return [CycleFuture] object that indicates when the reducer was applied
     */
    fun setStateFuture(reducer: ItReducer<T>): CycleFuture


    /**
     * Will enqueue the [action]
     * The [action] will be executed on the internal state thread and should not
     * contain long running / blocking operations.
     * Only one action will be running at a time.
     *
     * The action will run at the end of the next cycle.
     * All pending reducers will be invoked and evaluated before.
     */
    override fun withState(action: Action<T>) {
        withStateFuture(action)
    }


    /**
     * Same as [withState] but wont use receiver function
     */
    override fun withStateIt(action: ItAction<T>) = withState(action)


    /**
     * Same as [withStateIt] but returns future
     * @return [CycleFuture] object that indicates when the action was performed
     */
    fun withStateFuture(action: ItAction<T>): CycleFuture


    /**
     *  History of all states (including intermediate states)
     *  A new state will be created by every reducer.
     *  Each of those states will be pushed to the history.
     *
     *  History will be disabled by default.
     *
     *
     *  ## WARNING
     * This field is for debugging purpose only.
     * DO NOT USE THIS TO CREATE A DIFF ON THE STATE, because
     * views WILL NOT be notified after each reducer.
     * This means, that the history contains states that were not sent to
     * the UI, therefore it is a stupid idea to use this to create a diff.
     * If you want to create a diff: Use live-data or rxJava to diff against
     * the actual last state!
     */
    override val history: History<T>


    /**
     * Configuration of the given instance.
     * This parameters will never change and can be used
     * to configure other instances.
     */
    val config: InstanceConfig

    /**
     * Quits the current Quantum.
     * All currently enqueued reducers and actions will be discarded.
     * Currently running reducers will be executed
     * It is necessary to quit this state store to
     * ensure that the internal thread is stopped and the resources can be garbage collected.
     */
    override fun quit(): Joinable


    /**
     * Quits the current Quantum.
     * All currently enqueued reducers and actions will eb safely executed.
     * It is necessary to quit a quantum to ensure that internal resources can be freed
     */
    override fun quitSafely(): Joinable

    companion object
}

/**
 * @param initial The initial state of the quantum.
 *
 * @param threading The threading option for this quantum.
 * - Default value can be configured using [Quantum.Companion.configure].
 * - Default configuration is [Threading.Multi.Pool]
 */
fun <T> Quantum.Companion.create(
    initial: T,
    threading: Threading = config { this.threading.default }): Quantum<T> {
    return when (threading) {
        is Threading.Multi -> create(initial, threading)
        is Threading.Single -> create(initial, threading)
    }

}

private fun <T> Quantum.Companion.create(
    initial: T,
    threading: Threading.Multi): Quantum<T> {
    val managedExecutor = managedExecutor(threading)

    val quantum = ExecutorQuantum(
        initial = initial,
        callbackExecutor = threading.callbackExecutor,
        executor = managedExecutor.executor)

    quantum.addQuittedListener { managedExecutor.quitable?.quitSafely() }
    return quantum
}


/**
 * @param initial The initial state of the quantum.
 *
 * @param threading The threading option for this quantum.
 * - Default value can be configured using [Quantum.Companion.configure].
 * - Default configuration is [Threading.Single.Post]
 */
private fun <T> Quantum.Companion.create(
    initial: T,
    threading: Threading.Single): Quantum<T> {
    return SingleThreadQuantum(
        initial = initial,
        threading = threading)
}


/**
 * Create a [ManagedExecutor] from a given threading option
 */
private fun managedExecutor(threading: Threading.Multi): ManagedExecutor {
    return when (threading) {
        is Threading.Multi.Sync -> ManagedExecutor.nonQuitable(Executor(Runnable::run))
        is Threading.Multi.Pool -> ManagedExecutor.nonQuitable(config { this.threading.pool })
        is Threading.Multi.Thread -> ManagedExecutor.quitable(SingleThreadExecutor())
        is Threading.Multi.Custom -> ManagedExecutor.nonQuitable(threading.executor)
    }
}

/**
 * Wrapper around [Executor] that indicates whether or not the executor
 * should be quitted by if the quantum quitted.
 *
 * This is especially necessary if a new thread or thread-pool was allocated just
 * for a quantum. This thread or thread pool needs to get quitted if the quantum died.
 */
private data class ManagedExecutor(
    val executor: Executor,
    val quitable: Quitable? = null) {
    companion object {
        fun quitable(executor: QuitableExecutor): ManagedExecutor {
            return ManagedExecutor(executor, executor)
        }

        fun nonQuitable(executor: Executor): ManagedExecutor {
            return ManagedExecutor(executor, null)
        }
    }
}




