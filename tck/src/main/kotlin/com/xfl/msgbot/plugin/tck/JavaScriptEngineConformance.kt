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
import com.xfl.msgbot.plugin.api.engine.ScriptEvent
import com.xfl.msgbot.plugin.api.schema.Type
import com.xfl.msgbot.plugin.api.schema.moduleSpec
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.vArray
import com.xfl.msgbot.plugin.api.value.vObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What every JavaScript engine must do, as tests. Extend it in the engine's own test sources:
 *
 * ```kotlin
 * class MyEngineConformanceTest : JavaScriptEngineConformance() {
 *     override fun createEngine(context: EngineContext) = MyEngine(context)
 * }
 * ```
 *
 * Passing it is what lets any JavaScript profile run on the engine unchanged: the same values,
 * errors, events, timers, promises and modules, whoever wrote the runtime underneath. The binding
 * it checks is written out in PluginApi's docs/bindings/javascript.md.
 */
abstract class JavaScriptEngineConformance {
    /** The engine under test. Called on the engine thread. */
    protected abstract fun createEngine(context: EngineContext): ScriptEngine

    protected lateinit var harness: EngineHarness

    protected val api =
        listOf(
            moduleSpec("tck") {
                function("echo", returns = Type.ANY) { param("value", Type.ANY) }
                function("values", returns = Type.ANY)
                function("fail") {
                    param("code", Type.STRING)
                    param("message", Type.STRING)
                }
                function("mark") { param("label", Type.STRING) }
                event("ping") { field("n", Type.INT) }
            },
        )

    @Before
    fun openHarness() {
        harness = EngineHarness(::createEngine)
        harness.functions["tck.echo"] = { args -> CallResult.ok(args["value"] ?: Value.VNull) }
        harness.functions["tck.values"] = { CallResult.ok(HOST_VALUES) }
        harness.functions["tck.fail"] = { args -> CallResult.Err((args["code"] as Value.VString).value, (args["message"] as Value.VString).value) }
        harness.functions["tck.mark"] = { CallResult.ok() }
    }

    @After
    fun closeHarness() = harness.close()

    protected fun load(
        entry: String,
        profile: String = "",
        sources: Map<String, String> = emptyMap(),
    ) = harness.load(LoadRequest("javascript", api, ProfileScript("profile.js", profile), "main.js", sources + ("main.js" to entry)))

    protected fun eval(source: String): Value = harness.eval(source)

    private fun marks(): List<EngineHarness.Call> = harness.calls.filter { it.function == "tck.mark" }

    @Test
    fun apiIsBoundAsDataBeforeTheProfileRuns() {
        load(
            profile =
                """
                var profileSaw = __api.map(function (m) {
                    return m.namespace + ':' + m.functions.map(function (f) {
                        return f.name + '(' + f.params.map(function (p) { return p.name + ' ' + p.type; }).join(',') + ')' + f.returns;
                    }).join(',') + '|' + m.events.map(function (e) { return e.name; }).join(',');
                }).join(';');
                """.trimIndent(),
            entry = "var entrySawProfile = typeof profileSaw;",
        )
        assertEquals(Value.VString("tck:echo(value any)any,values()any,fail(code string,message string)void,mark(label string)void|ping"), eval("profileSaw"))
        assertEquals(Value.VString("string"), eval("entrySawProfile"))
    }

    @Test
    fun valuesFromTheScriptReachTheHostAsTheBindingSays() {
        load(
            """
            [null, true, 42, 1.5, 's', BigInt('9007199254740993'), new Uint8Array([1, 2, 255]), [1, 'a', undefined],
             { a: { b: 1 }, gone: undefined }, -0.5, 9007199254740991].forEach(function (v) { __host_call('tck.echo', { value: v }); });
            __host_call('tck.echo', { value: undefined });
            """.trimIndent(),
        )
        val seen = harness.calls.filter { it.function == "tck.echo" }.map { it.args }
        val expected =
            listOf(
                Value.VNull,
                Value.VBool(true),
                Value.VInt(42),
                Value.VDouble(1.5),
                Value.VString("s"),
                Value.VInt(9_007_199_254_740_993),
                Value.VBytes(byteArrayOf(1, 2, -1)),
                vArray(1L, "a", null),
                vObject("a" to vObject("b" to 1L)),
                Value.VDouble(-0.5),
                Value.VInt(9_007_199_254_740_991),
            ).map { mapOf("value" to it) } + listOf(emptyMap())
        assertEquals(expected, seen)
    }

    @Test
    fun valuesFromTheHostReachTheScriptAsTheBindingSays() {
        load(
            """
            var v = __host_call('tck.values', {});
            var report = [
                v['null'] === null,
                v.bool === true,
                typeof v.small === 'number' && v.small === 42,
                typeof v.big === 'bigint' && String(v.big) === '9223372036854775807',
                v['double'] === 1.5,
                v.string === 's',
                v.bytes instanceof Uint8Array && v.bytes.length === 3 && v.bytes[2] === 255,
                Array.isArray(v.list) && v.list.length === 2 && v.list[0] === 1 && v.list[1] === 'a',
                v.map.k === 'v' && Object.getPrototypeOf(v.map) === Object.prototype
            ].join(',');
            """.trimIndent(),
        )
        assertEquals(Value.VString(List(9) { "true" }.joinToString(",")), eval("report"))
    }

    @Test
    fun hostErrorsAreThrownAsErrorsCarryingTheirCode() {
        load(
            """
            var caught;
            try { __host_call('tck.fail', { code: 'bad_args', message: 'nope' }); }
            catch (e) { caught = (e instanceof Error) + '|' + e.code + '|' + (e.message.indexOf('nope') >= 0); }
            var unknown;
            try { __host_call('tck.missing', {}); } catch (e) { unknown = e.code; }
            """.trimIndent(),
        )
        assertEquals(Value.VString("true|bad_args|true"), eval("caught"))
        assertEquals(Value.VString("unknown_function"), eval("unknown"))
    }

    @Test
    fun dispatchCallsDispatchWithNameAndPayloadAndWaitsForItsHostCalls() {
        load(
            profile = "var __dispatch = function (name, payload) { __host_call('tck.mark', { label: name + ':' + payload.n }); };",
            entry = "",
        )
        harness.dispatch(ScriptEvent("tck.ping", mapOf("n" to Value.VInt(3))))
        assertEquals(listOf(mapOf("label" to Value.VString("tck.ping:3"))), marks().map { it.args })
        assertEquals(harness.threadName, marks().single().thread, "host calls are made on the engine thread")
    }

    @Test
    fun dispatchWithoutAProfileHandlerIsHarmless() {
        load("")
        harness.dispatch(ScriptEvent("tck.ping", mapOf("n" to Value.VInt(1))))
    }

    @Test
    fun asynchronousHostCallsAnswerWithAPromise() {
        load(
            """
            var asyncResult = 'pending', asyncError = 'pending';
            __host_call_async('tck.echo', { value: 7 }).then(function (v) { asyncResult = v; });
            __host_call_async('tck.fail', { code: 'unavailable', message: 'later' }).then(null, function (e) { asyncError = e.code; });
            """.trimIndent(),
        )
        assertTrue(harness.awaitUntil { eval("asyncResult === 7 && asyncError === 'unavailable'") == Value.VBool(true) }, "promises settled: ${eval("asyncResult + '/' + asyncError")}")
    }

    @Test
    fun timersRunOnTheEngineThreadAndCanBeCleared() {
        load(
            """
            var fired = [];
            setTimeout(function () { fired.push('timeout'); __host_call('tck.mark', { label: 'timer' }); }, 10);
            var cancelled = setTimeout(function () { fired.push('cancelled'); }, 10);
            clearTimeout(cancelled);
            var ticks = 0;
            var interval = setInterval(function () { if (++ticks === 3) clearInterval(interval); }, 5);
            """.trimIndent(),
        )
        assertTrue(harness.awaitUntil { eval("fired.join(',') === 'timeout' && ticks === 3") == Value.VBool(true) }, "timers ran")
        Thread.sleep(100)
        assertEquals(Value.VString("timeout/3"), eval("fired.join(',') + '/' + ticks"))
        assertEquals(harness.threadName, marks().single().thread)
    }

    @Test
    fun anErrorNobodyCatchesIsReported() {
        load("setTimeout(function () { throw new Error('late failure'); }, 1);")
        assertTrue(harness.awaitUntil { harness.errors.any { "late failure" in it } }, "reported: ${harness.errors}")
    }

    @Test
    fun requireLoadsTheProjectsOwnFiles() {
        load(
            """
            var util = require('./lib/util');
            var data = require('./data.json');
            var result = util.twice(data.n) + ':' + (require('./lib/util.js') === util) + ':' + util.dir;
            var missing;
            try { require('./nope'); } catch (e) { missing = e.message; }
            """.trimIndent(),
            sources =
                mapOf(
                    "lib/util.js" to "exports.twice = function (n) { return n * 2; }; exports.dir = __dirname;",
                    "data.json" to """{"n": 21}""",
                ),
        )
        assertEquals(Value.VString("42:true:lib"), eval("result"))
        assertTrue((eval("missing") as Value.VString).value.contains("nope"))
    }

    @Test
    fun aScriptThatDoesNotParseFailsTheLoadNamingTheFile() {
        val e = assertFailsWith<EngineException> { load("var = ;") }
        assertTrue(e.message!!.contains("main.js"), e.message)
    }

    @Test
    fun evalAnswersInTheGlobalScope() {
        load("var answer = 41;")
        assertEquals(Value.VInt(42), eval("answer + 1"))
    }

    @Test
    fun closingTheEngineStopsItsTimers() {
        load("setInterval(function () { __host_call('tck.mark', { label: 'tick' }); }, 5);")
        assertTrue(harness.awaitUntil { marks().isNotEmpty() })
        harness.closeEngineOnly()
        val count = marks().size
        Thread.sleep(100)
        assertEquals(count, marks().size)
    }

    private companion object {
        val HOST_VALUES =
            vObject(
                "null" to null,
                "bool" to true,
                "small" to 42L,
                "big" to Long.MAX_VALUE,
                "double" to 1.5,
                "string" to "s",
                "bytes" to byteArrayOf(1, 2, -1),
                "list" to vArray(1L, "a"),
                "map" to vObject("k" to "v"),
            )
    }
}
