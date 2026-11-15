/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.EngineContext
import com.xfl.msgbot.plugin.api.engine.EngineException
import com.xfl.msgbot.plugin.api.engine.LoadRequest
import com.xfl.msgbot.plugin.api.engine.ProfileScript
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Conformance tests for a JavaScript engine that runs ES modules itself (docs/bindings/javascript.md, "모듈"). Subclass
 * it next to [JavaScriptEngineConformance] in an engine that does; an engine that only runs CommonJS does not.
 */
abstract class JavaScriptModuleConformance {
    /** Called on the engine thread. */
    protected abstract fun createEngine(context: EngineContext): ScriptEngine

    /** Whether the engine runs Java, so `java:` modules can be imported. */
    protected open val runsJava: Boolean = false

    protected lateinit var harness: EngineHarness

    @Before
    fun openHarness() {
        harness = EngineHarness(::createEngine)
        harness.functions["sys.listen"] = { CallResult.ok() }
    }

    @After
    fun closeHarness() = harness.close()

    private fun load(
        entry: String,
        sources: Map<String, String> = emptyMap(),
    ) = harness.load(LoadRequest("javascript", listOf(StandardApi.Sys), ProfileScript("profile.js", ""), "main.js", sources + ("main.js" to entry)))

    private fun out(): Value = harness.eval("globalThis.out")

    private val lib = "export function twice(n) { return n * 2; }"

    @Test
    fun projectFilesImportEachOther() {
        load(
            "import { twice } from './lib.js';\nimport three from './three.mjs';\nglobalThis.out = twice(three);",
            mapOf("lib.js" to lib, "three.mjs" to "export default 3;"),
        )
        assertEquals(Value.VInt(6), out())
    }

    @Test
    fun aCommonJsFileGivesItsExportsAsDefaultAndByName() {
        load(
            "import c, { value } from './c.cjs';\nimport plain from './plain.js';\nglobalThis.out = value + ':' + c.other + ':' + plain;",
            mapOf("c.cjs" to "module.exports = { value: 5, other: 6 };", "plain.js" to "module.exports = 'plain';"),
        )
        assertEquals(Value.VString("5:6:plain"), out())
    }

    @Test
    fun theKitImports() {
        load("import kit, { api } from 'msgbot';\nglobalThis.out = typeof kit + ':' + (api === kit.api);")
        assertEquals(Value.VString("object:true"), out())
    }

    @Test
    fun dynamicImportGivesTheModule() {
        load("export {};\nglobalThis.out = 'pending';\nimport('./lib.js').then(function (m) { globalThis.out = m.twice(5); });", mapOf("lib.js" to lib))
        assertTrue(harness.awaitUntil { out() == Value.VInt(10) }, "out: ${out()}")
    }

    @Test
    fun requireOfAnEsModuleFails() {
        load(
            "var outcome;\ntry { require('./lib.mjs'); outcome = 'loaded'; } catch (e) { outcome = /ES module/.test(e.message); }\nglobalThis.out = outcome;",
            mapOf("lib.mjs" to lib),
        )
        assertEquals(Value.TRUE, out())
    }

    @Test
    fun supportCjsGivesAnEsModuleRequire() {
        load(
            "#!support cjs\nimport { twice } from './lib.js';\nconst c = require('./c.cjs');\nglobalThis.out = twice(c.value);",
            mapOf("lib.js" to lib, "c.cjs" to "module.exports = { value: 5 };"),
        )
        assertEquals(Value.VInt(10), out())
    }

    @Test
    fun supportEsmMakesAFileAnEsModule() {
        // No import or export, so only the directive makes top-level await legal.
        load("#!support esm\nglobalThis.out = await Promise.resolve(7);")
        assertEquals(Value.VInt(7), out())
    }

    @Test
    fun aRejectedTopLevelAwaitFailsTheLoad() {
        val e = assertFailsWith<EngineException> { load("export {};\nawait Promise.reject(new Error('top-level failure'));") }
        assertTrue(e.message!!.contains("top-level failure"), e.message)
    }

    @Test
    fun aModuleThatDoesNotParseFailsTheLoadNamingTheFile() {
        val e = assertFailsWith<EngineException> { load("import { x } from './broken.js';\nglobalThis.out = x;", mapOf("broken.js" to "export const = ;")) }
        assertTrue(e.message!!.contains("broken.js"), e.message)
    }

    @Test
    fun anImportOfAMissingFileFailsTheLoad() {
        val e = assertFailsWith<EngineException> { load("import { twice } from './lib';\nglobalThis.out = twice(1);", mapOf("lib.js" to lib)) }
        // Engines name it as written or as the path it resolved to.
        assertTrue(e.message!!.contains("lib"), e.message)
    }

    @Test
    fun javaModulesImportByPackageAndByClass() {
        if (!runsJava) return
        load(
            "import { StringBuilder } from 'java:java.lang';\nimport Builder from 'java:java.lang.StringBuilder';\n" +
                "globalThis.out = new StringBuilder('a').append('b').toString() + new Builder('c').toString();",
        )
        assertEquals(Value.VString("abc"), out())
    }
}
