/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.engine

import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.schema.ModuleSpec
import com.xfl.msgbot.plugin.api.value.Value

/**
 * Runs one language for one project: binds the boundary globals (see
 * [com.xfl.msgbot.plugin.api.binding.Binding]), provides the language runtime, then runs the
 * profile and the entry file. The API shape comes from the profile, not the engine.
 *
 * Every method except [interrupt] runs on the engine thread ([EngineContext.scheduler]).
 */
interface ScriptEngine : AutoCloseable {
    /** Binds the API, runs the profile, then [LoadRequest.entry]. Throws [EngineException] if the script cannot load. */
    fun load(request: LoadRequest)

    /** Calls `__dispatch(name, payload)`. Returns after synchronous handlers finish; async work is not awaited. */
    fun dispatch(event: ScriptEvent)

    /**
     * Evaluates [source] in the global scope. For tests and a REPL.
     *
     * A result that cannot cross as a [Value], such as a function or an object holding functions, is
     * not an error: it comes back as a string that shows it, in the language's own notation.
     */
    fun eval(source: String): Value

    /**
     * Called from another thread to stop a running script (e.g. `while (true) {}`). The busy call
     * should then throw [EngineException] and the engine stays usable. No-op when idle.
     */
    fun interrupt()

    override fun close()
}

/** Creates an engine on its own thread, with everything it may use. */
fun interface ScriptEngineFactory {
    fun create(context: EngineContext): ScriptEngine
}

/** The host, thread and error sink given to an engine. */
interface EngineContext {
    val host: HostBridge

    val scheduler: EngineScheduler

    /** Reports failures no caller awaits, such as a timer callback or an unhandled rejection. */
    fun reportError(
        message: String,
        error: Throwable? = null,
    )
}

/** Script-to-host calls. [function] is qualified (`weather.forecast`); [args] are keyed by parameter name. */
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

    /**
     * Opens a file of this project through the host and returns a file descriptor of this process.
     * The caller owns it and must close it. [path] is absolute or relative to the project folder, and
     * must stay inside it. [mode] is a [HostFileMode]. Blocks the engine thread.
     *
     * @throws CallException failing with [com.xfl.msgbot.plugin.api.call.ErrorCode.FAILED] whose message starts
     * with the POSIX error name (`ENOENT: ...`), or with `unavailable` when the host serves no files.
     */
    fun openFile(
        path: String,
        mode: Int,
    ): Int = throw CallException.unavailable("This host serves no files")
}

/**
 * How [HostBridge.openFile] opens a file. Without a flag the file is opened for reading and must exist.
 * [WRITE] or [APPEND] opens it for writing, [READ_WRITE] adds reading to that, or on its own opens an
 * existing file for both. [EXCLUSIVE] goes with [WRITE] or [APPEND].
 */
object HostFileMode {
    const val READ = 0

    /** Creates the file or empties it. */
    const val WRITE = 1

    /** Creates the file or adds to its end. */
    const val APPEND = 2

    /** With [WRITE] or [APPEND]: fails with `EEXIST` when the file already exists. */
    const val EXCLUSIVE = 4

    /**
     * Reading and writing through one descriptor. Alone it opens an existing file without emptying it
     * (`r+`), with [WRITE] it creates or empties the file (`w+`), with [APPEND] it creates it or adds to
     * its end (`a+`). A host whose file system cannot give such a descriptor fails with `EACCES`.
     */
    const val READ_WRITE = 8
}

/** The engine thread. Tasks run serially, in order, and never after the engine closes. */
interface EngineScheduler {
    /** Queues [task] on the engine thread. Safe to call from any thread. */
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
 * @property api modules this project may use, host modules first; bound as `__api`.
 * @property profile the language facade, run after `__api` is bound and before [entry].
 * @property sources project files keyed by path relative to the project folder.
 * @property options per-project settings the engine declared.
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

/** A profile's facade source. [name] is the file name shown in stack traces. */
data class ProfileScript(val name: String, val source: String)

/** [name] is qualified (`bot.message`); [payload] matches the event's schema. */
data class ScriptEvent(
    val name: String,
    val payload: Map<String, Value> = emptyMap(),
)

/** A script error meant for its author, such as a syntax error or a throwing handler. */
class EngineException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
