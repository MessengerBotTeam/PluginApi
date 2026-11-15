/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import com.xfl.msgbot.plugin.api.engine.ClassAccess
import com.xfl.msgbot.plugin.api.engine.ClassAccess.Preset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassRulesTest {
    private val engine = listOf("com.caoccao.javet.")

    @Test
    fun `the safe preset keeps ordinary classes and refuses the ways around the guard`() {
        val rules = ClassRules(ClassAccess(), engine)
        assertFalse(rules.isDenied("java.util.ArrayList"))
        assertFalse(rules.isDenied("java.lang.reflect.Modifier"))
        assertTrue(rules.isDenied("java.lang.reflect.Proxy"))
        assertTrue(rules.isDenied("java.lang.invoke.MethodHandles"))
        assertTrue(rules.isDenied("dalvik.system.DexClassLoader"))
        assertTrue(rules.isDenied("com.caoccao.javet.interop.V8Runtime"))
        assertTrue(rules.isEngineInternal("com.caoccao.javet.interop.V8Runtime"))
        assertFalse(rules.isEngineInternal("com.xfl.msgbot.plugin.guard.GuardFile"))
        assertTrue(rules.isDenied("com.xfl.msgbot.plugin.ipc.PluginService"))
        assertFalse(rules.allows("java.lang.reflect.Method"))
    }

    @Test
    fun `the guard's own objects may be held`() {
        assertFalse(ClassRules(ClassAccess(), engine).isDenied("com.xfl.msgbot.plugin.guard.GuardFile"))
    }

    @Test
    fun `the wide preset lets real reflection and processes through`() {
        val rules = ClassRules(ClassAccess(Preset.WIDE), engine)
        assertTrue(rules.allows("java.lang.reflect.Method"))
        assertFalse(rules.isDenied("java.lang.Process"))
        assertTrue(rules.isDenied("java.lang.reflect.Proxy"))
    }

    @Test
    fun `custom lists add and take away, and deny wins`() {
        val rules = ClassRules(ClassAccess(Preset.CUSTOM, allow = listOf("java.lang.reflect.*", "dalvik.system.PathClassLoader"), deny = listOf("java.lang.reflect.Proxy", "java.net.*")), engine)
        assertFalse(rules.isDenied("java.lang.reflect.Method"))
        assertTrue(rules.isDenied("java.lang.reflect.Proxy"))
        assertFalse(rules.isDenied("dalvik.system.PathClassLoader"))
        assertTrue(rules.isDenied("java.net.Socket"))
    }

    @Test
    fun `lists accept commas, spaces and trailing stars`() {
        val rules = ClassRules(ClassAccess(Preset.CUSTOM, allow = listOf("java.lang.invoke*, dalvik.system.DexFile;sun.misc.Unsafe")), engine)
        assertFalse(rules.isDenied("java.lang.invoke.MethodHandles"))
        assertFalse(rules.isDenied("dalvik.system.DexFile"))
        assertFalse(rules.isDenied("sun.misc.Unsafe"))
    }

    @Test
    fun `without restrictions only the engine and the deny list are refused`() {
        val rules = ClassRules(ClassAccess(restrictions = false, deny = listOf("java.net.Socket")), engine)
        assertFalse(rules.isDenied("java.lang.reflect.Proxy"))
        assertTrue(rules.allows("java.lang.reflect.Method"))
        assertTrue(rules.isDenied("java.net.Socket"))
        assertTrue(rules.isDenied("com.caoccao.javet.interop.V8Runtime"))
    }

    @Test
    fun `values of JDK implementation classes may be held, ways around the guard may not`() {
        val rules = ClassRules(ClassAccess(), engine)
        assertFalse(rules.isDeniedValue("sun.nio.ch.FileChannelImpl"))
        assertFalse(rules.isDeniedValue("sun.nio.cs.UTF_8"))
        assertTrue(rules.isDenied("sun.nio.ch.FileChannelImpl"))
        assertTrue(rules.isDeniedValue("java.lang.invoke.MethodHandles${'$'}Lookup"))
        assertTrue(rules.isDeniedValue("dalvik.system.PathClassLoader"))
        assertTrue(rules.isDeniedValue("com.caoccao.javet.interop.V8Runtime"))
    }
}
