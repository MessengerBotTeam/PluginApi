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
import com.xfl.msgbot.plugin.api.provider.ProviderCall
import com.xfl.msgbot.plugin.api.provider.ProviderEvent
import com.xfl.msgbot.plugin.api.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ProviderTransportTest {
    /** Stands in for a real messenger: replies succeed, sending refuses, everything else breaks. */
    private class FakeSource(private val failStart: Boolean = false) : CapabilityProvider {
        override val descriptor = ProviderDescriptor("fake-source", "Fake", listOf("reply", "send"))
        lateinit var sink: ProviderEventSink
        var configuredProjects: Map<String, Map<String, String>> = emptyMap()
        var projectsAtStart: Map<String, Map<String, String>> = emptyMap()

        override fun bindSink(sink: ProviderEventSink) { this.sink = sink }

        override fun configure(projects: Map<String, Map<String, String>>) {
            configuredProjects = projects
        }

        override fun call(call: ProviderCall): Value =
            when (call.method) {
                "reply" -> Value.VBool(true)
                "send" -> throw CapabilityException(CallResult.Code.BAD_ARGS, "no such room")
                else -> throw IllegalStateException("wire came loose")
            }

        override fun start() {
            projectsAtStart = configuredProjects
            if (failStart) error("account is not ready")
        }

        override fun stop() = Unit
    }

    @Test
    fun `provider receives isolated project options before it starts`() {
        val (hostSide, pluginSide) = LoopbackTransport.pair()
        val provider = FakeSource()
        val remote = RemoteCapabilityProvider(hostSide)
        val host = ProviderHost(pluginSide) { provider }
        try {
            assertNotNull(remote.awaitDescriptor())
            val projects = mapOf("A" to mapOf("token" to "one"), "B" to mapOf("token" to "two"))
            remote.configure(projects)
            remote.start()
            assertEquals(projects, provider.configuredProjects)
            assertEquals(projects, provider.projectsAtStart)
        } finally {
            host.close()
        }
    }

    @Test
    fun `a targeted event keeps its project id across the transport`() {
        val (hostSide, pluginSide) = LoopbackTransport.pair()
        val provider = FakeSource()
        val remote = RemoteCapabilityProvider(hostSide)
        val host = ProviderHost(pluginSide) { provider }
        try {
            assertNotNull(remote.awaitDescriptor())
            val received = CompletableFuture<ProviderEvent>()
            remote.bindSink { received.complete(it) }
            val payload = Value.VObject(mapOf("type" to Value.VString("message")))
            provider.sink.emit(ProviderEvent("Project A", payload))
            assertEquals(ProviderEvent("Project A", payload), received.get(5, TimeUnit.SECONDS))
        } finally {
            host.close()
        }
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
            assertEquals(Value.VBool(true), remote.call(ProviderCall("A", "reply", listOf(Value.VString("t1")))))
        } finally {
            host.close()
        }
    }

    @Test
    fun `a refused source capability comes back as the same refusal`() {
        val (remote, host) = connect()
        try {
            val e = assertFailsWith<CapabilityException> { remote.call(ProviderCall("A", "send", listOf(Value.VString("nowhere")))) }
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
            val e = assertFailsWith<CapabilityException> { remote.call(ProviderCall("A", "unheardOf", emptyList())) }
            assertEquals(CallResult.Code.FAILED, e.code)
        } finally {
            host.close()
        }
    }
}
