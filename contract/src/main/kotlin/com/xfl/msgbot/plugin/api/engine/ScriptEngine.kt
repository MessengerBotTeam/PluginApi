/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.engine

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.schema.ModuleSpec
import com.xfl.msgbot.plugin.api.value.Value

/**
 * Runs one language for one project. An engine does three things and nothing else:
 *
 * 1. binds the language's side of the boundary (`__api`, `__host_call`, `__host_call_async`,
 *    `__dispatch`, see [com.xfl.msgbot.plugin.api.binding.Binding]) using its language binding,
 * 2. provides the language runtime a script expects (timers, promises, module loading),
 * 3. runs the profile, then the project's entry file.
 *
 * It never decides what the API looks like: that is the profile's job, driven by [LoadRequest.api].
 *
 * Every method runs on the engine's single thread, the one [EngineContext.scheduler] posts to.
 */
interface ScriptEngine : AutoCloseable {
    /**
     * Binds the API, runs the profile and then [LoadRequest.entry]. Throws [EngineException] when
     * the script cannot load; the compile that asked is waiting to say so.
     */
    fun load(request: LoadRequest)

    /**
     * Calls the language's `__dispatch(name, payload)`. Returns once synchronous handlers, and the
     * host calls they make, have finished; work they start asynchronously is not waited for.
     */
    fun dispatch(event: ScriptEvent)

    /** Evaluates [source] in the script's global scope. For tests and a REPL. */
    fun eval(source: String): Value

    /**
     * Stops the script running on the engine thread now, the one exception to that thread: it is
     * called from another one, while [load], [dispatch] or [eval] may be busy with a script that
     * never returns (`while (true) {}`). That call should throw soon after, with an
     * [EngineException]; the engine stays usable. When nothing runs, it does nothing.
     *
     * The host calls it when a script overruns its time and before it closes an engine that is
     * still busy. Without it such a script keeps a thread and a core until the process dies.
     */
    fun interrupt()

    override fun close()
}

/** Creates an engine on its own thread, with everything it may use. */
fun interface ScriptEngineFactory {
    fun create(context: EngineContext): ScriptEngine
}

/** What an engine is given: the host, its thread, and somewhere to report what goes wrong later. */
interface EngineContext {
    val host: HostBridge

    val scheduler: EngineScheduler

    /** For failures of work nobody is waiting for: a timer callback, a rejected promise. */
    fun reportError(
        message: String,
        error: Throwable? = null,
    )
}

/**
 * The one way down from a script to the host. [function] is qualified (`weather.forecast`);
 * [args] are named, as the schema names the parameters.
 */
interface HostBridge {
    /** Blocks the engine thread until the host answers. */
    fun call(
        function: String,
        args: Map<String, Value>,
    ): CallResult

    /** Returns at once. [onResult] runs later, on the engine thread. */
    fun callAsync(
        function: String,
        args: Map<String, Value>,
        onResult: (CallResult) -> Unit,
    )
}

/** The engine thread. Tasks run one at a time, in order, and never after the engine closes. */
interface EngineScheduler {
    /** Runs [task] on the engine thread soon. Safe to call from any thread. */
    fun post(task: () -> Unit)

    /** Runs [task] after [delayMs], and every [delayMs] after that when [repeat]. */
    fun schedule(
        delayMs: Long,
        repeat: Boolean = false,
        task: () -> Unit,
    ): Cancellable
}

fun interface Cancellable {
    fun cancel()
}

/**
 * Everything one load needs.
 *
 * @property language what [sources] are written in; a polyglot engine cannot guess it.
 * @property api every module this project may use, host modules first. Bound as `__api` data.
 * @property profile the language facade, run after `__api` is bound and before [entry].
 * @property entry the path in [sources] that runs as the project's main script.
 * @property sources the project's source files by path relative to its folder, for the language's
 *   own module loading (`require('./util')`).
 * @property options this engine's per-project settings, as the engine declared them.
 */
data class LoadRequest(
    val language: String,
    val api: List<ModuleSpec>,
    val profile: ProfileScript,
    val entry: String,
    val sources: Map<String, String>,
    val options: Map<String, String> = emptyMap(),
) {
    init {
        require(entry in sources) { "The entry '$entry' is not among the sources" }
    }

    val entrySource: String get() = sources.getValue(entry)

    /** [api] as the value bound to `__api`. */
    fun apiValue(): Value.VArray = Value.VArray(api.map { it.toValue() })
}

/** A profile's facade source. [name] is what stack traces should call it. */
data class ProfileScript(val name: String, val source: String)

/** An event for the script: [name] is qualified (`bot.message`); [payload] matches its schema. */
data class ScriptEvent(
    val name: String,
    val payload: Map<String, Value> = emptyMap(),
)

/** A script failed in a way its author should read: a syntax error, a handler that threw. */
class EngineException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
