package com.xfl.msgbot.plugin.api.rpc

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.call.ErrorCode
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RpcPeerTest {
    private val transports = LoopbackTransport.pair()
    private val hostSide = transports.first
    private val pluginSide = transports.second
    private val notes = CompletableFuture<Pair<String, Value>>()

    private val echo =
        object : RpcHandler {
            override fun onRequest(
                method: String,
                params: Value,
                reply: (CallResult) -> Unit,
            ) = when (method) {
                "echo" -> reply(CallResult.ok(params))
                "fail" -> reply(CallResult.badArgs("no"))
                "later" -> Thread { Thread.sleep(50); reply(CallResult.ok(Value.VString("done"))) }.start()
                "never" -> Unit
                "throw" -> throw IllegalStateException("boom")
                else -> reply(CallResult.failed("unknown"))
            }

            override fun onNotify(
                method: String,
                params: Value,
            ) {
                notes.complete(method to params)
            }
        }

    private val host = RpcPeer(hostSide, echo)
    private val plugin = RpcPeer(pluginSide, echo)

    @AfterTest
    fun close() {
        host.close()
        plugin.close()
    }

    @Test
    fun `both sides ask and answer`() {
        assertEquals(CallResult.ok(Value.VInt(1)), host.request("echo", Value.VInt(1), 5_000))
        assertEquals(CallResult.ok(Value.VInt(2)), plugin.request("echo", Value.VInt(2), 5_000))
    }

    @Test
    fun `errors keep their code`() {
        assertEquals(CallResult.badArgs("no"), host.request("fail", Value.VNull, 5_000))
        assertEquals(CallResult.failed("boom"), host.request("throw", Value.VNull, 5_000))
    }

    @Test
    fun `an answer may come later from another thread`() {
        val answer = CompletableFuture<CallResult>()
        host.requestAsync("later", Value.VNull, 5_000, answer::complete)
        assertEquals(CallResult.ok(Value.VString("done")), answer.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `silence becomes unavailable after the timeout`() {
        val result = host.request("never", Value.VNull, 100)
        assertIs<CallResult.Err>(result)
        assertEquals(ErrorCode.UNAVAILABLE, result.code)
    }

    @Test
    fun `closing fails whatever is still waiting`() {
        val answer = CompletableFuture<CallResult>()
        host.requestAsync("never", Value.VNull, 0, answer::complete)
        host.close()
        assertEquals(ErrorCode.UNAVAILABLE, (answer.get(5, TimeUnit.SECONDS) as CallResult.Err).code)
    }

    @Test
    fun `notifications arrive without an answer`() {
        plugin.notify("hi", Value.VString("there"))
        assertEquals("hi" to Value.VString("there"), notes.get(5, TimeUnit.SECONDS))
    }
}
