/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import java.io.File
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * What an engine needs to guard Java for scripts: which class a name stands for ([lookup]) and what a Java value
 * becomes before a script sees it ([replace]). An engine calls these from its class lookup and from where Java values
 * enter the script; everything else is in the guard classes themselves.
 *
 * The answers follow the [GuardSession] of the current thread ([GuardContext]).
 */
object GuardPolicy {
    /**
     * Classes that open files, start programs or describe classes, by the name a script asks for. The replacements
     * have the same constructors, methods and fields, and extend the real class wherever it is not final.
     */
    val substitutes: Map<String, Class<*>> =
        mapOf(
            "java.io.File" to GuardFile::class.java,
            "java.io.FileInputStream" to GuardFileInputStream::class.java,
            "java.io.FileOutputStream" to GuardFileOutputStream::class.java,
            "java.io.FileReader" to GuardFileReader::class.java,
            "java.io.FileWriter" to GuardFileWriter::class.java,
            "java.io.PrintWriter" to GuardPrintWriter::class.java,
            "java.io.PrintStream" to GuardPrintStream::class.java,
            "java.io.RandomAccessFile" to GuardRandomAccessFile::class.java,
            "java.util.Scanner" to GuardScanner::class.java,
            "java.util.zip.ZipFile" to GuardZipFile::class.java,
            "java.util.jar.JarFile" to GuardJarFile::class.java,
            "java.nio.file.Path" to GuardPath::class.java,
            "java.nio.file.Paths" to GuardPaths::class.java,
            "java.nio.file.Files" to GuardFiles::class.java,
            "java.nio.file.FileSystems" to GuardFileSystems::class.java,
            "android.os.Environment" to GuardEnvironment::class.java,
            "java.lang.Class" to GuardClass::class.java,
            "java.lang.ClassLoader" to GuardClassLoader::class.java,
            "java.lang.Runtime" to GuardRuntime::class.java,
            "java.lang.ProcessBuilder" to GuardProcessBuilder::class.java,
            "java.lang.System" to GuardSystem::class.java,
        )

    // Replacements that only matter while the class rules are on; files are guarded either way.
    private val classStandIns = setOf("java.lang.Class", "java.lang.ClassLoader", "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.System")

    private val originals: Map<Class<*>, String> = substitutes.entries.associate { (name, type) -> type to name }

    /** The replacement for [className] under the current rules, or null when the real class is used. */
    fun substituteOf(className: String): Class<*>? {
        if (className in classStandIns && !GuardContext.rules().restricted) return null
        return substitutes[className]
    }

    /** The class a replacement stands for, or [type] itself. */
    fun originalOf(type: Class<*>): Class<*> = originals[type]?.let { runCatching { Class.forName(it) }.getOrNull() } ?: type

    sealed interface Lookup {
        /** Give the script this class. */
        data class Found(val type: Class<*>) : Lookup

        /** No such class for this project: show nothing there (a package in Javet, undefined, or not found). */
        data object Denied : Lookup

        /** Not a guarded name: look it up as the engine always does. */
        data object Ordinary : Lookup
    }

    fun lookup(className: String): Lookup {
        substituteOf(className)?.let { return Lookup.Found(it) }
        val rules = GuardContext.rules()
        if (isGuardClass(className) || rules.isDenied(className)) {
            // Engines look up their own classes through the same path while they start; that is not the script.
            if (!rules.isEngineInternal(className) && !isGuardClass(className)) GuardContext.reportClass(className)
            return Lookup.Denied
        }
        return Lookup.Ordinary
    }

    /** True when [className] is one of the guard's own classes, which a script may hold. */
    fun isGuardClass(className: String) = className.startsWith(OWN_PACKAGE)

    /**
     * What a Java value becomes before a script sees it: a real file or path becomes the guard's, and under the class
     * rules a class, class loader or reflective member becomes a checked stand-in. A value of a class the project may
     * not use is refused with [SecurityException]. Arrays and collections are left to the engine, which converts
     * their elements one by one.
     */
    fun replace(value: Any?): Any? {
        if (value == null) return null
        val type = value.javaClass
        if (isGuardClass(type.name)) return value
        when (value) {
            is File -> return GuardFile(value.path)
            is Path -> if (value.fileSystem === realDefault) return GuardPath(GuardContext.host(), value.toString())
        }
        val rules = GuardContext.rules()
        if (!rules.restricted) {
            if (rules.isDeniedValue(type.name)) refuse(type.name)
            return value
        }
        return when (value) {
            is Class<*> -> GuardClass.of(value)
            is ClassLoader -> GuardClassLoader.INSTANCE
            is Method -> if (rules.allows("java.lang.reflect.Method")) value else GuardMethod(value)
            is Constructor<*> -> if (rules.allows("java.lang.reflect.Constructor")) value else GuardConstructor(value)
            is Field -> if (rules.allows("java.lang.reflect.Field")) value else GuardField(value)
            is Runtime -> GuardRuntime.getRuntime()
            is AccessibleObject -> refuse(type.name)
            is Process -> value
            else -> if (rules.isDeniedValue(type.name)) refuse(type.name) else value
        }
    }

    private fun refuse(name: String): Nothing {
        GuardContext.reportClass(name)
        throw SecurityException("$name is not available to this project")
    }

    private const val OWN_PACKAGE = "com.xfl.msgbot.plugin.guard."

    private val realDefault = FileSystems.getDefault()
}
