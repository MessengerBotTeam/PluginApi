/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.engine

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The single thread an engine lives on, usable as its [EngineScheduler] and as a plain [Executor].
 * Work posted after [shutdown] is dropped rather than thrown back at the caller, and scheduled
 * work dies with the thread, so a closed engine never hears from a timer again.
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

    @Volatile private var thread: Thread? = null

    init {
        executor.execute { thread = Thread.currentThread() }
    }

    val isCurrent: Boolean get() = Thread.currentThread() === thread

    val isShutdown: Boolean get() = executor.isShutdown

    override fun execute(command: Runnable) {
        try {
            executor.execute(command)
        } catch (_: RejectedExecutionException) {
            // Shut down; nothing left to run it against.
        }
    }

    override fun post(task: () -> Unit) = execute { guarded("A posted task", task) }

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

    /** Interrupts whatever is running and drops the queue. */
    fun shutdownNow() {
        executor.shutdownNow()
    }

    fun awaitTermination(timeoutMs: Long): Boolean = executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)

    private inline fun guarded(
        what: String,
        task: () -> Unit,
    ) {
        try {
            task()
        } catch (e: Exception) {
            onError("$what failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
