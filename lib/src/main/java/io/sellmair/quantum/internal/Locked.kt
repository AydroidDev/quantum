package io.sellmair.quantum.internal

import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/*
################################################################################################
INTERNAL API
################################################################################################
*/

internal class Locked<T : Any>(private val value: T,
                               private val lock: ReentrantLock = ReentrantLock()) : Lock by lock {

    inline operator fun <R> invoke(operation: T.() -> R): R = withLock {
        operation(value)
    }

    val isHeldByCurrentThread get() = lock.isHeldByCurrentThread

    val isNotHeldByCurrentThread get() = !isHeldByCurrentThread
}


