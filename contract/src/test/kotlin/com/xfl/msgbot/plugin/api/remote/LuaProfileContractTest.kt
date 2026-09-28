package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.binding.Binding
import com.xfl.msgbot.plugin.api.binding.LuaBinding
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.EngineContext
import com.xfl.msgbot.plugin.api.engine.EngineException
import com.xfl.msgbot.plugin.api.engine.EngineThread
import com.xfl.msgbot.plugin.api.engine.HostBridge
import com.xfl.msgbot.plugin.api.engine.LoadRequest
import com.xfl.msgbot.plugin.api.engine.ProfileScript
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.engine.ScriptEvent
import com.xfl.msgbot.plugin.api.rpc.LoopbackTransport
import com.xfl.msgbot.plugin.api.schema.Type
import com.xfl.msgbot.plugin.api.schema.moduleSpec
import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A second, real language over the same IPC contract. Nothing here is JavaScript-shaped: the Lua
 * profile builds its API from `__api` and calls with named arguments like any other.
 */
class LuaProfileContractTest {
    /** A Lua engine keeping the Lua binding (docs/bindings/lua.md), minus timers and modules. */
    private class LuaFixture(private val context: EngineContext) : ScriptEngine {
        private val globals = JsePlatform.standardGlobals()

        init {
            globals.set(
                Binding.HOST_CALL,
                object : TwoArgFunction() {
                    override fun call(
                        name: LuaValue,
                        args: LuaValue,
                    ): LuaValue =
                        when (val result = context.host.call(name.checkjstring(), namedArgs(args))) {
                            is CallResult.Ok -> toLua(result.value)
                            is CallResult.Err -> throw LuaError(errorOf(name.checkjstring(), result))
                        }
                },
            )
            globals.set(
                Binding.HOST_CALL_ASYNC,
                object : ThreeArgFunction() {
                    override fun call(
                        name: LuaValue,
                        args: LuaValue,
                        callback: LuaValue,
                    ): LuaValue {
                        val function = name.checkjstring()
                        context.host.callAsync(function, namedArgs(args)) { result ->
                            when (result) {
                                is CallResult.Ok -> callback.call(toLua(result.value), LuaValue.NIL)
                                is CallResult.Err -> callback.call(LuaValue.NIL, errorOf(function, result))
                            }
                        }
                        return LuaValue.NIL
                    }
                },
            )
            val kit = globals.load(LuaBinding.KIT, "msgbot")
            globals.get("package").get("preload").set(LuaBinding.KIT_NAME, object : ZeroArgFunction() {
                override fun call(): LuaValue = kit.call()
            })
        }

        override fun load(request: LoadRequest) {
            globals.set(Binding.API, toLua(request.apiValue()))
            try {
                globals.load(request.profile.source, request.profile.name).call()
                globals.load(request.entrySource, request.entry).call()
            } catch (e: LuaError) {
                throw EngineException(e.message ?: "Lua error", e)
            }
        }

        override fun dispatch(event: ScriptEvent) {
            val dispatch = globals.get(Binding.DISPATCH)
            if (!dispatch.isnil()) dispatch.call(LuaValue.valueOf(event.name), toLua(Value.VObject(event.payload)))
        }

        override fun eval(source: String): Value = fromLua(globals.load(source, "eval").call())

        override fun close() = Unit

        /** The binding's error: a table with code and message that prints as "code: message". */
        private fun errorOf(
            function: String,
            err: CallResult.Err,
        ): LuaTable {
            val message = "$function: ${err.message}"
            val error = LuaTable()
            error.set("code", err.code)
            error.set("message", message)
            error.setmetatable(LuaTable().apply { set("__tostring", object : OneArgFunction() {
                override fun call(self: LuaValue): LuaValue = LuaValue.valueOf("${err.code}: $message")
            }) })
            return error
        }

        private fun namedArgs(args: LuaValue): Map<String, Value> =
            if (args.istable()) args.checktable().entries().associate { (k, v) -> k.checkjstring() to fromLua(v) } else emptyMap()

        private fun LuaTable.entries(): List<Pair<LuaValue, LuaValue>> {
            val out = mutableListOf<Pair<LuaValue, LuaValue>>()
            var key = LuaValue.NIL
            while (true) {
                val next: Varargs = next(key)
                key = next.arg1()
                if (key.isnil()) return out
                out += key to next.arg(2)
            }
        }

        private fun toLua(value: Value): LuaValue =
            when (value) {
                Value.VNull -> LuaValue.NIL
                is Value.VBool -> LuaValue.valueOf(value.value)
                is Value.VInt -> LuaValue.valueOf(value.value.toDouble())
                is Value.VDouble -> LuaValue.valueOf(value.value)
                is Value.VString -> LuaValue.valueOf(value.value)
                is Value.VBytes -> LuaValue.valueOf(value.value)
                is Value.VArray -> LuaTable().apply { value.items.forEachIndexed { i, item -> set(i + 1, toLua(item)) } }
                is Value.VObject -> LuaTable().apply { value.entries.forEach { (k, item) -> set(k, toLua(item)) } }
            }

        private fun fromLua(value: LuaValue): Value =
            when {
                value.isnil() -> Value.VNull
                value.isboolean() -> Value.VBool(value.toboolean())
                value.isinttype() -> Value.VInt(value.tolong())
                value.isnumber() -> Value.VDouble(value.todouble())
                value.isstring() -> Value.VString(value.tojstring())
                value.istable() -> {
                    val table = value.checktable()
                    val entries = table.entries()
                    val markedList = table.getmetatable()?.get("__msgbot_list")?.toboolean() == true
                    if (entries.isNotEmpty() && entries.all { it.first.isinttype() } || markedList && entries.isEmpty()) {
                        Value.VArray(entries.sortedBy { it.first.toint() }.map { fromLua(it.second) })
                    } else {
                        Value.VObject(entries.associate { (k, v) -> k.tojstring() to fromLua(v) })
                    }
                }
                else -> throw LuaError("A ${value.typename()} cannot cross to the host")
            }
    }

    private val weather =
        moduleSpec("weather") {
            function("forecast", returns = Type.STRING) { param("city", Type.STRING) }
            function("fail")
            event("alert") { field("text", Type.STRING) }
            event("storm") { field("text", Type.STRING) }
        }

    @Test
    fun `a Lua profile reaches the standard bot and a new provider through the same contract`() {
        val (hostSide, pluginSide) = LoopbackTransport.pair()
        val replies = CopyOnWriteArrayList<Map<String, Value>>()
        val listened = CopyOnWriteArrayList<Value>()
        val thread = EngineThread("host-engine")
        val pool = Executors.newCachedThreadPool()
        val context =
            object : EngineContext {
                override val host =
                    object : HostBridge {
                        override fun call(
                            function: String,
                            args: Map<String, Value>,
                        ): CallResult =
                            when (function) {
                                "weather.forecast" -> CallResult.ok(Value.VString("sunny in ${(args["city"] as Value.VString).value}"))
                                "weather.fail" -> CallResult.unavailable("offline")
                                "bot.reply" -> {
                                    replies += args
                                    CallResult.ok(Value.TRUE)
                                }
                                "sys.listen" -> {
                                    listened += args.getValue("events")
                                    CallResult.ok()
                                }
                                else -> CallResult.unknownFunction(function)
                            }

                        override fun callAsync(
                            function: String,
                            args: Map<String, Value>,
                            onResult: (CallResult) -> Unit,
                        ) = onResult(call(function, args))
                    }
                override val scheduler = thread

                override fun reportError(
                    message: String,
                    error: Throwable?,
                ) = Unit
            }
        val endpoint = EngineEndpoint(pluginSide, ::LuaFixture)
        val engine = RemoteScriptEngine.connect(hostSide, context, pool)
        try {
            engine.load(
                LoadRequest(
                    language = "lua",
                    api = listOf(StandardApi.Bot.restrictTo(listOf("reply"), listOf("message")), weather, StandardApi.Sys),
                    profile = ProfileScript("minimal_api2.lua", javaClass.getResource("/profiles/minimal_api2.lua")!!.readText()),
                    entry = "main.lua",
                    sources =
                        mapOf(
                            "main.lua" to
                                """
                                local bot = BotManager.getCurrentBot()
                                bot.on('message', function(msg) msg.reply(Api.weather.forecast(msg.room) .. ' / ' .. msg.content) end)
                                bot.on('weather.alert', function(event) received = event.text end)
                                local ok, err = pcall(Api.weather.fail)
                                failure = (not ok) and (err.code .. ' / ' .. tostring(err)) or 'no error'
                                Api.weather.forecast.async(function(value, err) later = value end, 'Busan')
                                """.trimIndent(),
                        ),
                ),
            )
            engine.dispatch(ScriptEvent("bot.message", mapOf("room" to Value.VString("Seoul"), "content" to Value.VString("hi"), "replyToken" to Value.VString("t1"))))
            engine.dispatch(ScriptEvent("weather.alert", mapOf("text" to Value.VString("rain"))))

            assertEquals(listOf(mapOf("token" to Value.VString("t1"), "text" to Value.VString("sunny in Seoul / hi"))), replies.toList())
            assertEquals(Value.VString("rain"), engine.eval("return received"))
            assertEquals(Value.VString("unavailable / unavailable: weather.fail: offline"), engine.eval("return failure"))
            assertEquals(Value.VString("sunny in Busan"), engine.eval("return later"))
            // The storm nobody listens to is never asked for: the kit told the host what to deliver.
            assertEquals(Value.VArray(listOf(Value.VString("bot.message"), Value.VString("weather.alert"))), listened.last())
        } finally {
            engine.close()
            endpoint.close()
            thread.shutdownNow()
            pool.shutdownNow()
        }
    }
}
