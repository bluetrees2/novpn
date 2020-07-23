package com.github.bluetrees2.novpn

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

fun <E, K: Comparable<K>> MutableList<E>.addSortedBy(key: K, element: E, selector: (E) -> K?): Int {
    var j = this.binarySearchBy(key) { selector(it) }
    if (j < 0) j = -j - 1
    this.add(j, element)
    return j
}

fun <E, K: Comparable<K>> List<E>.indexOfBy(key: K, selector: (E) -> K?): Int {
    val iterator = listIterator()
    while (iterator.hasNext()) {
        if (selector(iterator.next()) == key)
            return iterator.previousIndex()
    }
    return -1
}

fun <E, K: Comparable<K>> Sequence<E>.lastIndexOfBy(key: K, selector: (E) -> K?): Int {
    var lastMatch = -1
    var i = 0
    for (item in this) {
        if (selector(item) == key)
            lastMatch = i
        ++i
    }
    return lastMatch
}

fun Process.hasExited() : Boolean {
    return try {
        exitValue()
        true
    } catch (e: IllegalThreadStateException) {
        false
    }
}

class RingBuffer<T>(size: Int): Sequence<T> {
    @Suppress("UNCHECKED_CAST")
    private val buffer: Array<T?> = arrayOfNulls<Any?>(size) as Array<T?>
    private var end = 0
    private var isFull = false
    private val iterators = mutableListOf<WeakReference<RBIterator>>()
    private val lock = object {
        val buffer = ReentrantReadWriteLock()
        val iterators = ReentrantReadWriteLock()
        val observers = ReentrantReadWriteLock()
    }
    private data class ObserverData<T>(val observer: MutationObserver<T>, val lock: Lock, var remove: Boolean = false)
    private val observers = mutableListOf<ObserverData<T>>()

    fun add(element: T) {
        lock.buffer.write {
            val oldVal = buffer[end]
            val index = end
            buffer[index] = element
            end += 1
            if (end == buffer.size) {
                end = 0
                isFull = true
            }
            updateIterators()
            oldVal to index
        }.let { (oldVal, index) ->
            notifyObservers(mutableListOf<Mutation<T>>().apply {
                if (oldVal != null)
                    add(Mutation(MutationType.REMOVE, index, oldVal))
                add(Mutation(MutationType.ADD, index, element))
            })
        }
    }

    val size: Int
        get() = lock.buffer.read { if (!isFull) end else buffer.size }

    operator fun get(i: Int): T = lock.buffer.read {
        return if (!isFull) {
            if (i >= end)
                throw IndexOutOfBoundsException()
            buffer[i]!!
        } else {
            if (i >= buffer.size)
                throw IndexOutOfBoundsException()
            val j = (i + end) % buffer.size
            buffer[j]!!
        }
    }

    fun clear() {
        lock.buffer.write {
            buffer.fill(null)
            isFull = false
            end = 0
            invalidateIterators()
        }
    }

    override fun iterator(): Iterator<T> {
        val iterator = RBIterator()
        lock.iterators.write {
            iterators.removeAll { it.get() == null }
            iterators.add(WeakReference(iterator))
        }
        return iterator
    }

    private fun updateIterators() {
        lock.iterators.read {
            iterators
                .mapNotNull { it.get() }
                .forEach { iterator ->
                    if (iterator.pos == end)
                        iterator.pos += 1
                }
        }
    }

    private fun invalidateIterators() {
        lock.iterators.read {
            iterators
                .mapNotNull { it.get() }
                .forEach { iterator ->
                    iterator.invalid = true
                }
        }
    }

    private inner class RBIterator : Iterator<T> {
        var pos = if (isFull) (end + 1) % buffer.size else 0
        var invalid = false
        var count = 0
        override fun hasNext(): Boolean = lock.buffer.read {
            if (invalid) return false
            if (count++ > buffer.size) return false
            return pos != end
        }
        override fun next(): T = lock.buffer.read {
            val element = buffer[pos]
            pos += 1
            if (pos == buffer.size)
                pos = 0
            return element!!
        }
    }

    fun observe(observer: MutationObserver<T>) {
        lock.observers.write {
            if (observers.find { it.observer == observer} == null)
                observers.add(ObserverData(observer, ReentrantLock()))
        }
    }

    private fun notifyObservers(mutations: List<Mutation<T>>) {
        val unregister = mutableListOf<ObserverData<T>>()
        lock.observers.read {
            observers.forEach {
                it.lock.withLock {
                    if (!it.remove) {
                        if (!it.observer.onChange(mutations))
                            it.remove = true
                    }
                    if (it.remove)
                        unregister.add(it)
                }
            }
        }
        if (unregister.isNotEmpty())
            lock.observers.write { observers.removeAll(unregister) }
    }

    enum class MutationType {
        ADD,
        REMOVE,
    }

    data class Mutation<T>(val type: MutationType, val index: Int, val element:T)

    interface MutationObserver<T> {
        fun onChange(mutations: List<Mutation<T>>): Boolean
    }
}

enum class Ternary {
    YES,
    NO,
    UNKNOWN
}

class SequentialScope {
    private val context = Dispatchers.IO
    private var scope = CoroutineScope(context + Job())
    private val mutex = Mutex()
    private var lastJob: Job? = null

    suspend fun launch(block: suspend () -> Unit) {
        mutex.withLock {
            lastJob = lastJob.let { job ->
                scope.launch {
                    job?.join()
                    block()
                }
            }
            lastJob!!.let { job ->
                job.invokeOnCompletion {
                    GlobalScope.launch { dispatchOnCompletion(job, it) }
                }
            }
        }
    }

    var onCompletionListener: (suspend (cause: Throwable?) -> Unit)? = null

    private suspend fun dispatchOnCompletion(job: Job?, cause: Throwable?) {
        mutex.withLock {
            if (lastJob != job && lastJob != null) {
                lastJob!!.let { j ->
                    j.invokeOnCompletion {
                        GlobalScope.launch { dispatchOnCompletion(j, it) }
                    }
                }
                return
            }
        }
        onCompletionListener?.invoke(cause)
    }

    suspend fun cancel() {
        mutex.withLock {
            scope.cancel()
            scope = CoroutineScope(context + Job())
        }
    }
}
