/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.EngineContext
import com.xfl.msgbot.plugin.api.engine.EngineScheduler
import com.xfl.msgbot.plugin.api.engine.EngineThread
import com.xfl.msgbot.plugin.api.engine.HostBridge
import com.xfl.msgbot.plugin.api.engine.LoadRequest
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.engine.ScriptEngineFactory
import com.xfl.msgbot.plugin.api.engine.ScriptEvent
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs one engine the way the host does: on its own [EngineThread], with a scripted host behind
 * it. Useful on its own for an engine's tests beyond the conformance suite.
 */
class EngineHarness(factory: ScriptEngineFactory) : AutoCloseable {
    /** One host call as the host saw it, synchronous or not, and which thread made it. */
    data class Call(val function: String, val args: Map<String, Value>, val thread: String)

    val threadName = "tck-engine"
    val calls = CopyOnWriteArrayList<Call>()
    val errors = CopyOnWriteArrayList<String>()

    /** What the host answers, by qualified function name. Anything else is an unknown function. */
    val functions = ConcurrentHashMap<String, (Map<String, Value>) -> CallResult>()

    private val thread = EngineThread(threadName) { message, _ -> errors += message }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tck-host").apply { isDaemon = true } }

    val context: EngineContext =
        object : EngineContext {
            override val host: HostBridge =
                object : HostBridge {
                    override fun call(
                        function: String,
                        args: Map<String, Value>,
                    ): CallResult {
                        calls += Call(function, args, Thread.currentThread().name)
                        return functions[function]?.invoke(args) ?: CallResult.unknownFunction(function)
                    }

                    override fun callAsync(
                        function: String,
                        args: Map<String, Value>,
                        onResult: (CallResult) -> Unit,
                    ) {
                        calls += Call(function, args, Thread.currentThread().name)
                        worker.execute {
                            val result = functions[function]?.invoke(args) ?: CallResult.unknownFunction(function)
                            thread.post { onResult(result) }
                        }
                    }
                }

            override val scheduler: EngineScheduler = thread

            override fun reportError(
                message: String,
                error: Throwable?,
            ) {
                errors += message
            }
        }

    val engine: ScriptEngine = onEngine { factory.create(context) }

    fun load(request: LoadRequest) = onEngine { engine.load(request) }

    fun dispatch(event: ScriptEvent) = onEngine { engine.dispatch(event) }

    fun eval(source: String): Value = onEngine { engine.eval(source) }

    /** Runs [block] on the engine thread and waits for it, rethrowing what it threw. */
    fun <T> onEngine(block: () -> T): T =
        try {
            onEngineAsync(block).get(30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    /** Runs [block] on the engine thread without waiting, for a test that stops it from outside. */
    fun <T> onEngineAsync(block: () -> T): CompletableFuture<T> = thread.submit(block)

    /** What the host does to a script that overran its time: [ScriptEngine.interrupt], from this thread. */
    fun interrupt() = engine.interrupt()

    /** Polls [condition] until it holds or [timeoutMs] passes; whether it held. */
    fun awaitUntil(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Volatile private var engineClosed = false

    /** Closes the engine on its thread, then the thread. */
    override fun close() {
        runCatching { closeEngineOnly() }
        thread.shutdownNow()
        worker.shutdownNow()
    }

    /** Closes only the engine, leaving its thread running, to see what the engine itself stops. */
    fun closeEngineOnly() {
        if (engineClosed) return
        engineClosed = true
        onEngine { engine.close() }
    }
}
