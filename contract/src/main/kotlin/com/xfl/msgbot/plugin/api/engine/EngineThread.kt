/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The single thread an engine lives on, usable as its [EngineScheduler] and as a plain [Executor].
 *
 * Nothing handed to it is lost without a word: [execute] after [shutdown] throws, as an
 * [Executor] must, so a coroutine dispatcher over it fails instead of waiting forever; [submit]
 * answers every task, failing the ones [shutdownNow] drops. [post] and [schedule], the
 * [EngineScheduler] an engine sees, simply stop once the thread does, so a closed engine never
 * hears from a timer again.
 */
class EngineThread(
    name: String,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : EngineScheduler, Executor {
    private val executor =
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, name).apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }

    /** What [submit] promised and has not run yet; [shutdownNow] fails these. */
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    @Volatile private var thread: Thread? = null

    init {
        executor.execute { thread = Thread.currentThread() }
    }

    val isCurrent: Boolean get() = Thread.currentThread() === thread

    val isShutdown: Boolean get() = executor.isShutdown

    /** Throws [RejectedExecutionException] once the thread is shut down. */
    override fun execute(command: Runnable) = executor.execute(command)

    /**
     * Runs [block] on this thread; the future answers with what it returned or threw. It fails with
     * [RejectedExecutionException] when the thread is shut down before the block runs, and
     * cancelling it before then skips the block.
     */
    fun <T> submit(block: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        pending += result
        try {
            executor.execute {
                if (pending.remove(result) && !result.isDone) {
                    try {
                        result.complete(block())
                    } catch (e: Throwable) {
                        result.completeExceptionally(e)
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            pending -= result
            result.completeExceptionally(e)
        }
        result.whenComplete { _, _ -> pending -= result }
        return result
    }

    override fun post(task: () -> Unit) {
        try {
            executor.execute { guarded("A posted task", task) }
        } catch (_: RejectedExecutionException) {
            // Shut down; nothing left to run it against.
        }
    }

    override fun schedule(
        delayMs: Long,
        repeat: Boolean,
        task: () -> Unit,
    ): Cancellable {
        val delay = delayMs.coerceAtLeast(0)
        val body = Runnable { guarded("A timer", task) }
        return try {
            val future =
                if (repeat) {
                    executor.scheduleWithFixedDelay(body, delay, delay.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                } else {
                    executor.schedule(body, delay, TimeUnit.MILLISECONDS)
                }
            Cancellable { future.cancel(false) }
        } catch (_: RejectedExecutionException) {
            Cancellable {}
        }
    }

    /** Stops accepting work; what is queued still runs, timers do not. */
    fun shutdown() = executor.shutdown()

    /**
     * Interrupts whatever is running, drops the queue, and fails what [submit] still owed. An
     * engine stuck in a script does not notice a Java interrupt; stop it with
     * [ScriptEngine.interrupt] first.
     */
    fun shutdownNow() {
        executor.shutdownNow()
        pending.toList().forEach { it.completeExceptionally(RejectedExecutionException("The engine thread stopped before this ran")) }
    }

    fun awaitTermination(timeoutMs: Long): Boolean = executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)

    private inline fun guarded(
        what: String,
        task: () -> Unit,
    ) {
        try {
            task()
        } catch (e: Throwable) {
            onError("$what failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
