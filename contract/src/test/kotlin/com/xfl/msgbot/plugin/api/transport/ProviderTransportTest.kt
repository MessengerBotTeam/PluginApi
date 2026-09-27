/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.bridge.CapabilityException
import com.xfl.msgbot.plugin.api.provider.ProviderEventSink
import com.xfl.msgbot.plugin.api.provider.CapabilityProvider
import com.xfl.msgbot.plugin.api.provider.ProviderDescriptor
import com.xfl.msgbot.plugin.api.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ProviderTransportTest {
    /** Stands in for a real messenger: replies succeed, sending refuses, everything else breaks. */
    private class FakeSource(private val failStart: Boolean = false) : CapabilityProvider {
        override val descriptor = ProviderDescriptor("fake-source", "Fake", listOf("reply", "send"))

        override fun bindSink(sink: ProviderEventSink) = Unit

        override fun call(method: String, args: List<Value>): Value =
            when (method) {
                "reply" -> Value.VBool(true)
                "send" -> throw CapabilityException(CallResult.Code.BAD_ARGS, "no such room")
                else -> throw IllegalStateException("wire came loose")
            }

        override fun start() {
            if (failStart) error("account is not ready")
        }

        override fun stop() = Unit
    }

    private fun connect(failStart: Boolean = false): Pair<RemoteCapabilityProvider, ProviderHost> {
        val (hostSide, pluginSide) = LoopbackTransport.pair()
        val remote = RemoteCapabilityProvider(hostSide)
        val host = ProviderHost(pluginSide) { FakeSource(failStart) }
        assertNotNull(remote.awaitDescriptor(), "the source should describe itself on connect")
        return remote to host
    }

    @Test
    fun `a provider that cannot start reports the failure before it is used`() {
        val (remote, host) = connect(failStart = true)
        try {
            val error = assertFailsWith<IllegalStateException> { remote.start() }
            assertEquals("account is not ready", error.message)
        } finally {
            host.close()
        }
    }

    @Test
    fun `a source capability answers across the transport`() {
        val (remote, host) = connect()
        try {
            assertEquals(Value.VBool(true), remote.call("reply", listOf(Value.VString("t1"))))
        } finally {
            host.close()
        }
    }

    @Test
    fun `a refused source capability comes back as the same refusal`() {
        val (remote, host) = connect()
        try {
            val e = assertFailsWith<CapabilityException> { remote.call("send", listOf(Value.VString("nowhere"))) }
            // The code survives the wire, so a script can still tell "you called this wrong"
            // from "it broke". VNull here is the bug this frame's error half exists to end.
            assertEquals(CallResult.Code.BAD_ARGS, e.code)
        } finally {
            host.close()
        }
    }

    @Test
    fun `a source that throws still owes the host an answer`() {
        val (remote, host) = connect()
        try {
            val e = assertFailsWith<CapabilityException> { remote.call("unheardOf", emptyList()) }
            assertEquals(CallResult.Code.FAILED, e.code)
        } finally {
            host.close()
        }
    }
}
