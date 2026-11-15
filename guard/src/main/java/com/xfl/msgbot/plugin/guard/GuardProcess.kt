/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import java.io.File
import java.util.StringTokenizer

/**
 * What a script may start. A process runs with the rights of this app and the engine cannot look inside it, so this is a
 * check of what is written, not a fence: a command that names a path must name one the project may see, and a program
 * that reads its arguments as commands (a shell) is refused. Anything that builds a path inside the program gets past it.
 */
internal object ProcessRules {
    private val interpreters = setOf("sh", "bash", "ash", "dash", "zsh", "su", "busybox", "toybox", "env", "run-as", "app_process", "app_process32", "app_process64", "cmd", "am", "pm", "xargs", "nohup", "setsid")

    fun check(
        host: GuardHost,
        command: List<String>,
    ) {
        if (command.isEmpty()) throw IndexOutOfBoundsException("Empty command")
        val program = command[0].substringAfterLast('/')
        if (program in interpreters) GuardContext.currentOrNull()?.report("program", program)
        if (program in interpreters) throw SecurityException("'$program' reads its arguments as commands, so a script may not start it")
        for (token in command) {
            if (!token.contains('/') && token != ".." && !token.startsWith("~")) continue
            checkPath(host, token, "in the command")
        }
    }

    fun checkDirectory(
        host: GuardHost,
        directory: File?,
    ) {
        if (directory == null) return
        checkPath(host, directory.path, "as the working folder")
    }

    private fun checkPath(
        host: GuardHost,
        token: String,
        where: String,
    ) {
        val seen = !token.startsWith("~") && !token.split('/').contains("..") && runCatching { host.stat(token) }.getOrNull() != null
        if (!seen) GuardContext.reportPath(token)
        if (!seen) throw SecurityException("The path '$token' $where is outside what this project may use")
    }

    fun split(command: String): List<String> {
        val tokens = StringTokenizer(command)
        return List(tokens.countTokens()) { tokens.nextToken() }
    }
}

/** `java.lang.Runtime`: memory and processor facts as usual, programs only through [ProcessRules]. */
class GuardRuntime private constructor() {
    private fun host() = GuardContext.host()

    fun availableProcessors() = Runtime.getRuntime().availableProcessors()

    fun freeMemory() = Runtime.getRuntime().freeMemory()

    fun totalMemory() = Runtime.getRuntime().totalMemory()

    fun maxMemory() = Runtime.getRuntime().maxMemory()

    fun gc() = Runtime.getRuntime().gc()

    fun exec(command: String): Process = exec(ProcessRules.split(command).toTypedArray(), null, null)

    fun exec(command: Array<String>): Process = exec(command, null, null)

    fun exec(
        command: String,
        env: Array<String>?,
        dir: File?,
    ): Process = exec(ProcessRules.split(command).toTypedArray(), env, dir)

    fun exec(
        command: Array<String>,
        env: Array<String>?,
        dir: File?,
    ): Process {
        ProcessRules.check(host(), command.toList())
        ProcessRules.checkDirectory(host(), dir)
        val builder = ProcessBuilder(*command)
        if (dir != null) builder.directory(File(dir.path))
        if (env != null) {
            builder.environment().clear()
            env.forEach { entry -> entry.indexOf('=').takeIf { it > 0 }?.let { builder.environment()[entry.substring(0, it)] = entry.substring(it + 1) } }
        }
        return builder.start()
    }

    fun exit(status: Int): Unit = throw SecurityException("A script may not end the process")

    fun halt(status: Int): Unit = throw SecurityException("A script may not end the process")

    fun load(path: String): Unit = throw SecurityException("A script may not load native code")

    fun loadLibrary(name: String): Unit = throw SecurityException("A script may not load native code")

    override fun toString() = "GuardRuntime"

    companion object {
        private val INSTANCE = GuardRuntime()

        @JvmStatic
        fun getRuntime() = INSTANCE
    }
}

/** `java.lang.ProcessBuilder`, which is final, rebuilt with the same checks at [start]. */
class GuardProcessBuilder(command: List<String>) {
    private val host = GuardContext.host()
    private var command: List<String> = command.toList()
    private var directory: File? = null
    private var mergeErrors = false
    private val environment = HashMap<String, String>(System.getenv())

    constructor(vararg command: String) : this(command.toList())

    fun command(): List<String> = command

    fun command(command: List<String>): GuardProcessBuilder = apply { this.command = command.toList() }

    fun command(vararg command: String): GuardProcessBuilder = apply { this.command = command.toList() }

    fun directory(): File? = directory

    fun directory(directory: File?): GuardProcessBuilder =
        apply {
            ProcessRules.checkDirectory(host, directory)
            this.directory = directory?.let { File(it.path) }
        }

    fun redirectErrorStream(): Boolean = mergeErrors

    fun redirectErrorStream(merge: Boolean): GuardProcessBuilder = apply { mergeErrors = merge }

    fun environment(): MutableMap<String, String> = environment

    fun start(): Process {
        ProcessRules.check(host, command)
        ProcessRules.checkDirectory(host, directory)
        val builder = ProcessBuilder(command)
        builder.directory(directory)
        builder.redirectErrorStream(mergeErrors)
        builder.environment().apply {
            clear()
            putAll(environment)
        }
        return builder.start()
    }
}

/** `java.lang.System` without what ends the process or loads native code. */
class GuardSystem private constructor() {
    companion object {
        @JvmField val `in` = System.`in`

        @JvmField val out = System.out

        @JvmField val err = System.err

        @JvmStatic fun currentTimeMillis() = System.currentTimeMillis()

        @JvmStatic fun nanoTime() = System.nanoTime()

        @JvmStatic fun getProperty(key: String): String? = System.getProperty(key)

        @JvmStatic fun getProperty(
            key: String,
            default: String?,
        ): String? = System.getProperty(key, default)

        @JvmStatic fun lineSeparator(): String = System.lineSeparator()

        @JvmStatic fun identityHashCode(value: Any?) = System.identityHashCode(value)

        @JvmStatic fun arraycopy(
            source: Any,
            sourcePosition: Int,
            target: Any,
            targetPosition: Int,
            length: Int,
        ) = System.arraycopy(source, sourcePosition, target, targetPosition, length)

        @JvmStatic fun gc() = System.gc()

        @JvmStatic fun getenv(name: String): String? = System.getenv(name)

        @JvmStatic fun exit(status: Int): Unit = throw SecurityException("A script may not end the process")

        @JvmStatic fun load(path: String): Unit = throw SecurityException("A script may not load native code")

        @JvmStatic fun loadLibrary(name: String): Unit = throw SecurityException("A script may not load native code")
    }
}
