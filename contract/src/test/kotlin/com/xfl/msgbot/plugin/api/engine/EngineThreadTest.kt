/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Nothing handed to an engine thread disappears without an answer. */
class EngineThreadTest {
    @Test
    fun `submitted work answers with what it returned or threw`() {
        val thread = EngineThread("test")
        assertEquals(42, thread.submit { 42 }.get(5, TimeUnit.SECONDS))
        val e = assertFailsWith<ExecutionException> { thread.submit { throw StackOverflowError() }.get(5, TimeUnit.SECONDS) }
        assertTrue(e.cause is StackOverflowError)
        thread.shutdownNow()
    }

    @Test
    fun `work dropped by shutdownNow fails instead of waiting forever`() {
        val thread = EngineThread("test")
        val busy = CountDownLatch(1)
        thread.submit {
            busy.countDown()
            // A script that ignores Java interrupts.
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
            while (System.nanoTime() < until) Thread.onSpinWait()
        }
        busy.await()
        val queued = thread.submit { "never" }
        thread.shutdownNow()
        val e = assertFailsWith<ExecutionException> { queued.get(1, TimeUnit.SECONDS) }
        assertTrue(e.cause is RejectedExecutionException)
    }

    @Test
    fun `a shut down thread refuses work out loud, except what the engine posts`() {
        val thread = EngineThread("test")
        thread.shutdown()
        assertFailsWith<RejectedExecutionException> { thread.execute {} }
        val e = assertFailsWith<ExecutionException> { thread.submit { 1 }.get(1, TimeUnit.SECONDS) }
        assertTrue(e.cause is RejectedExecutionException)
        thread.post { error("never runs") }
        thread.schedule(0) { error("never runs") }
    }
}
