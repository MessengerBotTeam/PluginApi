package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.bridge.HostBridge
import com.xfl.msgbot.plugin.api.engine.EngineDescriptor
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.value.Value
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import kotlin.test.*

/** Exercises a real second language without adding that runtime to the production API. */
class LuaProfileContractTest {
    private class LuaFixture : ScriptEngine {
        override val descriptor = EngineDescriptor("fixture-lua", "Lua contract fixture", listOf("lua"))
        private val globals = JsePlatform.standardGlobals()
        override fun bindHost(bridge: HostBridge) {
            globals.set("__host_call", object : TwoArgFunction() {
                override fun call(method: LuaValue, args: LuaValue): LuaValue {
                    val values = (1..args.length()).map { fromLua(args.get(it)) }
                    return when (val result = bridge.call(method.checkjstring(), values)) {
                        is CallResult.Ok -> toLua(result.value)
                        is CallResult.Err -> throw LuaError("${result.code}: ${result.message}")
                    }
                }
            })
        }
        override fun load(language: String, capabilities: List<String>, shim: String, userScript: String, options: Map<String, String>) {
            require(language == "lua")
            globals.set("__caps", toLua(Value.VArray(capabilities.map(Value::VString))))
            globals.load(shim, "profile.lua").call()
            globals.load(userScript, "script.lua").call()
        }
        override fun dispatch(event: Value.VObject) { globals.get("__dispatch").call(toLua(event)) }
        override fun eval(source: String): Value = fromLua(globals.load(source, "eval.lua").call())
        override fun close() = Unit

        private fun toLua(value: Value): LuaValue = when (value) {
            Value.VNull -> LuaValue.NIL
            is Value.VString -> LuaValue.valueOf(value.value)
            is Value.VBool -> LuaValue.valueOf(value.value)
            is Value.VInt -> LuaValue.valueOf(value.value.toDouble())
            is Value.VDouble -> LuaValue.valueOf(value.value)
            is Value.VArray -> LuaTable().apply { value.items.forEachIndexed { i, item -> set(i + 1, toLua(item)) } }
            is Value.VObject -> LuaTable().apply { value.entries.forEach { (key, item) -> set(key, toLua(item)) } }
            else -> error("Fixture only supports the data types exercised here")
        }
        private fun fromLua(value: LuaValue): Value = when {
            value.isnil() -> Value.VNull
            value.isboolean() -> Value.VBool(value.toboolean())
            value.isinttype() -> Value.VInt(value.tolong())
            value.isnumber() -> Value.VDouble(value.todouble())
            value.isstring() -> Value.VString(value.tojstring())
            else -> error("Unsupported fixture return type")
        }
    }

    @Test fun `Lua profile implements BotManager and forwards new provider APIs through IPC`() {
        val (hostT, pluginT) = LoopbackTransport.pair()
        val remote = RemoteScriptEngine(hostT, EngineDescriptor("fixture-lua", "Lua", listOf("lua")))
        val endpoint = EngineHost(pluginT, ::LuaFixture)
        val replies = mutableListOf<List<Value>>()
        remote.bindHost { method, args ->
            when (method) {
                "weather.forecast" -> CallResult.of(Value.VString("sunny"))
                "weather.fail" -> CallResult.failed("offline")
                "bot.reply" -> { replies += args; CallResult.of(Value.VBool(true)) }
                else -> CallResult.unknown(method)
            }
        }
        try {
            val shim = checkNotNull(javaClass.getResource("/profiles/minimal_api2.lua")).readText()
            remote.load("lua", listOf("bot.reply", "event.message", "weather.forecast", "weather.fail", "event.weather.alert"), shim, """
                local bot = BotManager.getCurrentBot()
                local weather = ExtensionApi.module('weather')
                bot.on('message', function(msg) msg.reply(weather.forecast() .. ':' .. msg.content) end)
                bot.on('weather.alert', function(event) received = event.text end)
                local ok, err = pcall(weather.fail)
                failureCaught = not ok and string.find(err, 'offline') ~= nil
            """.trimIndent())
            remote.dispatch(Value.VObject(mapOf("type" to Value.VString("message"), "content" to Value.VString("hi"), "replyToken" to Value.VString("token"))))
            remote.dispatch(Value.VObject(mapOf("type" to Value.VString("weather.alert"), "text" to Value.VString("rain"))))
            assertEquals(listOf<List<Value>>(listOf(Value.VString("token"), Value.VString("sunny:hi"))), replies.toList())
            assertEquals(Value.VString("rain"), remote.eval("return received"))
            assertEquals(Value.VBool(true), remote.eval("return failureCaught"))
        } finally { remote.close(); endpoint.close() }
    }
}
