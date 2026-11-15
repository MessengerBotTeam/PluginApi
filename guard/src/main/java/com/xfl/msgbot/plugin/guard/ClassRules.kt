/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import com.xfl.msgbot.plugin.api.engine.ClassAccess

/**
 * A project's [ClassAccess] as answers about class names.
 *
 * @param engineInternals name prefixes of the engine itself (its runtime, its own classes). No setting reaches them:
 * a script that holds the engine can switch the guard off.
 */
class ClassRules(
    val access: ClassAccess,
    engineInternals: List<String> = emptyList(),
) {
    private val internals = engineInternals + OWN
    private val allow = Names(access.allow)
    private val deny = Names(access.deny)

    val restricted: Boolean get() = access.restrictions

    /** Whether [className] belongs to the engine itself (its runtime, its own classes). */
    fun isEngineInternal(className: String): Boolean = !className.startsWith(GUARD) && internals.any { className.startsWith(it) }

    /** Whether a script may not look this class up or use an object of it. */
    fun isDenied(className: String): Boolean {
        // Objects of the guard's own classes are what scripts are meant to hold; looking them up by name is refused
        // separately, in GuardPolicy.lookup.
        if (className.startsWith(GUARD)) return false
        if (internals.any { className.startsWith(it) }) return true
        if (deny.matches(className)) return true
        if (!restricted || className in ALWAYS_ALLOWED) return false
        if (allows(className)) return false
        return DENIED_UNLESS_LISTED.any { className.startsWith(it) }
    }

    /**
     * Whether a script may not hold an object whose class is [className]. Narrower than [isDenied]: a value of a JDK
     * implementation class (a `sun.nio.ch.FileChannelImpl` behind a `FileChannel`, a `sun.nio.cs` charset) is used through
     * its public API and is fine, while objects that give a way around the guard are not.
     */
    fun isDeniedValue(className: String): Boolean {
        if (className.startsWith(GUARD)) return false
        if (internals.any { className.startsWith(it) }) return true
        if (deny.matches(className)) return true
        if (!restricted || allows(className)) return false
        return DENIED_VALUES.any { className.startsWith(it) }
    }

    /** Whether the project lets the real object of this class reach a script, instead of the guard's stand-in. */
    fun allows(className: String): Boolean {
        if (deny.matches(className)) return false
        if (!restricted || allow.matches(className)) return true
        return access.preset == ClassAccess.Preset.WIDE && className in WIDE
    }

    /** Names written as `a.b.C`, `a.b.*` or `a.b*`. */
    private class Names(list: List<String>) {
        private val exact = HashSet<String>()
        private val prefixes = ArrayList<String>()

        init {
            list.flatMap { it.split(',', '\n', ' ', ';') }.map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                if (it.endsWith("*")) prefixes += it.dropLast(1) else exact += it
            }
        }

        fun matches(name: String) = name in exact || prefixes.any { name.startsWith(it) }
    }

    companion object {
        private val OWN = listOf("com.xfl.msgbot.plugin.")
        private const val GUARD = "com.xfl.msgbot.plugin.guard."

        private val ALWAYS_ALLOWED = setOf("java.lang.reflect.Modifier", "java.lang.reflect.Array")

        private val WIDE =
            setOf(
                "java.lang.reflect.Method",
                "java.lang.reflect.Field",
                "java.lang.reflect.Constructor",
                "java.lang.reflect.Executable",
                "java.lang.reflect.AccessibleObject",
                "java.lang.Process",
            )

        // Ways around the guard. A project may still list them, at its own risk.
        private val DENIED_UNLESS_LISTED =
            listOf(
                "java.lang.Process",
                "java.lang.Compiler",
                "java.lang.reflect.",
                "java.lang.invoke.",
                "java.net.URLClassLoader",
                "java.nio.channels.AsynchronousFileChannel",
                "java.nio.file.spi.",
                "dalvik.",
                "libcore.",
                "sun.",
                "jdk.internal.",
                "android.system.",
                "android.app.ActivityThread",
            )

        private val DENIED_VALUES =
            listOf(
                "java.lang.invoke.",
                "sun.misc.Unsafe",
                "jdk.internal.misc.",
                "dalvik.system.",
                "libcore.io.",
                "android.app.ActivityThread",
            )

        // Last: it needs the lists above.
        val DEFAULT = ClassRules(ClassAccess.DEFAULT)
    }
}
