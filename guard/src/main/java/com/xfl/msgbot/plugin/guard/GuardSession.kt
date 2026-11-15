/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * What the scripts of one project may use: the files the host shows them and the classes the project allows.
 * One per engine session; [close] it when the session ends.
 */
class GuardSession(
    val host: GuardHost,
    val rules: ClassRules = ClassRules.DEFAULT,
) : Closeable {
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val deleteOnExit = ConcurrentHashMap.newKeySet<String>()

    /** Tells the project log, once per name, that the guard stopped something. */
    fun report(
        kind: String,
        name: String,
    ) {
        if (reported.add("$kind:$name")) host.log("warn", "Java: $kind '$name' is not available to this project")
    }

    internal fun deleteOnExit(path: String) {
        deleteOnExit += path
    }

    /** Deletes what `File.deleteOnExit` asked for, deepest first. */
    override fun close() {
        deleteOnExit.sortedByDescending { it.length }.forEach {
            try {
                host.remove(it, false)
            } catch (_: Exception) {
            }
        }
        deleteOnExit.clear()
    }
}

/**
 * The session of the project whose script runs on this thread. Script code makes guard objects through reflection,
 * so they cannot be handed their project; they read it from here when they are made. The engine sets it around every
 * entry into script code (load, events, timers, eval).
 */
object GuardContext {
    private val current = ThreadLocal<GuardSession?>()

    inline fun <T> with(
        session: GuardSession?,
        block: () -> T,
    ): T {
        val previous = swap(session)
        try {
            return block()
        } finally {
            swap(previous)
        }
    }

    fun swap(session: GuardSession?): GuardSession? = current.get().also { current.set(session) }

    fun currentOrNull(): GuardSession? = current.get()

    fun current(): GuardSession = current.get() ?: throw SecurityException("Java files can only be used from a script of this project, on its own thread")

    fun host(): GuardHost = current().host

    fun rules(): ClassRules = current.get()?.rules ?: ClassRules.DEFAULT

    internal fun reportPath(path: String) {
        current.get()?.report("path", path)
    }

    internal fun reportClass(name: String) {
        current.get()?.report("class", name)
    }
}
