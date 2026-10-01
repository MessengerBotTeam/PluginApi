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
}
