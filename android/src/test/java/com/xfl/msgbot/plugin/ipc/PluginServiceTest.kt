/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.ipc

import android.os.Process
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.call.ErrorCode
import com.xfl.msgbot.plugin.api.discovery.PluginManifestSchema
import com.xfl.msgbot.plugin.api.discovery.PluginRole
import com.xfl.msgbot.plugin.api.engine.LoadRequest
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.engine.ScriptEngineFactory
import com.xfl.msgbot.plugin.api.engine.ScriptEvent
import com.xfl.msgbot.plugin.api.protocol.ProtocolVersion
import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.rpc.RpcHandler
import com.xfl.msgbot.plugin.api.rpc.RpcPeer
import com.xfl.msgbot.plugin.api.value.Value
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PluginServiceTest {
    class Plugin : PluginService() {
        override val providers: Map<String, () -> Provider> =
            mapOf(
                "empty" to {
                    object : Provider {
                        override val modules = emptyList<com.xfl.msgbot.plugin.api.provider.ProviderModule>()
                    }
                },
            )
    }

    /** An engine whose events block in "native code": no interrupt reaches them. */
    class StuckPlugin : PluginService() {
        override val engines =
            mapOf(
                "stuck" to
                    ScriptEngineFactory {
                        object : ScriptEngine {
                            override fun load(request: LoadRequest) = Unit

                            override fun dispatch(event: ScriptEvent) {
                                blocked.countDown()
                                while (true) {
                                    try {
                                        if (release.await(30, TimeUnit.SECONDS)) return
                                    } catch (_: InterruptedException) {
                                        // Native code does not notice a Java interrupt either.
                                    }
                                }
                            }

                            override fun eval(source: String): Value = Value.VNull

                            override fun interrupt() = Unit

                            override fun close() = Unit
                        }
                    },
                "quiet" to ScriptEngineFactory { QuietEngine },
            )
    }

    object QuietEngine : ScriptEngine {
        override fun load(request: LoadRequest) = Unit

        override fun dispatch(event: ScriptEvent) = Unit

        override fun eval(source: String): Value = Value.VNull

        override fun interrupt() = Unit

        override fun close() = Unit
    }

    class BrokenPlugin : PluginService() {
        override val providers: Map<String, () -> Provider>
            get() = throw NoClassDefFoundError("com/example/Missing")
    }

    private val hello = Value.of(mapOf("protocol" to ProtocolVersion.CURRENT, "minProtocol" to ProtocolVersion.MIN_SUPPORTED))
    private val silent = object : RpcHandler {
        override fun onRequest(
            method: String,
            params: Value,
            reply: (CallResult) -> Unit,
        ) = reply(CallResult.failed("no"))
    }

    @Before
    fun grant() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(PluginManifestSchema.PERMISSION)
    }

    @After
    fun reset() {
        ShadowBinder.reset()
    }

    @Test
    fun onlyTheAppThatOpenedASessionMayUseOrCloseIt() {
        val service = IPluginService.Stub.asInterface(Robolectric.setupService(Plugin::class.java).onBind(null))
        val transport = HostSessionTransport(service)
        transport.open(PluginRole.PROVIDER, "empty")
        val peer = RpcPeer(transport, silent)
        try {
            assertTrue(peer.request("hello", hello, 5_000) is CallResult.Ok)

            ShadowBinder.setCallingUid(Process.myUid() + 1)
            assertEquals(ErrorCode.UNAVAILABLE, (peer.request("hello", hello, 300) as CallResult.Err).code)
            service.close(1)

            ShadowBinder.reset()
            assertTrue("the session is still open", peer.request("hello", hello, 5_000) is CallResult.Ok)
        } finally {
            peer.close()
        }
    }

    @Test
    fun anErrorWhileOpeningComesBackAsAnException() {
        val service = IPluginService.Stub.asInterface(Robolectric.setupService(BrokenPlugin::class.java).onBind(null))
        val error = runCatching { HostSessionTransport(service).open(PluginRole.PROVIDER, "empty") }.exceptionOrNull()
        assertTrue("$error", error is IllegalStateException && error.cause is NoClassDefFoundError)
    }

    private fun stuckService(): Pair<StuckPlugin, IPluginService> {
        val plugin = Robolectric.setupService(StuckPlugin::class.java)
        plugin.stuckAfterMs = 200
        plugin.restartProcess = { restarts.incrementAndGet() }
        return plugin to IPluginService.Stub.asInterface(plugin.onBind(null))
    }

    private fun blockAnEvent(service: IPluginService): HostSessionTransport {
        val transport = HostSessionTransport(service)
        transport.open(PluginRole.ENGINE, "stuck")
        RpcPeer(transport, silent).requestAsync(
            "engine.dispatch",
            Value.of(mapOf("event" to "bot.message", "payload" to emptyMap<String, Any>())),
            timeoutMs = 0,
        ) {}
        assertTrue("the event started", blocked.await(5, TimeUnit.SECONDS))
        return transport
    }

    @Test
    fun aStuckSessionWithNothingElseOpenRestartsThePlugin() {
        val (_, service) = stuckService()
        blockAnEvent(service).close()
        await { restarts.get() == 1 }
    }

    @Test
    fun aStuckSessionWaitsWhileOthersAreOpen() {
        val (_, service) = stuckService()
        val other = HostSessionTransport(service).apply { open(PluginRole.ENGINE, "quiet") }
        blockAnEvent(service).close()
        Thread.sleep(600)
        assertEquals(0, restarts.get())
        other.close()
    }

    @Test
    fun aSessionThatStopsIsNotStuck() {
        val (_, service) = stuckService()
        HostSessionTransport(service).apply { open(PluginRole.ENGINE, "quiet") }.close()
        Thread.sleep(600)
        assertEquals(0, restarts.get())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("timed out", condition())
    }

    private val restarts = AtomicInteger()

    @Before
    fun freshLatches() {
        blocked = CountDownLatch(1)
        release = CountDownLatch(1)
    }

    @After
    fun releaseStuckEvents() {
        release.countDown()
    }

    companion object {
        /** Shared with the service the framework creates; fresh for each test. */
        @Volatile var blocked = CountDownLatch(1)

        @Volatile var release = CountDownLatch(1)
    }
}
