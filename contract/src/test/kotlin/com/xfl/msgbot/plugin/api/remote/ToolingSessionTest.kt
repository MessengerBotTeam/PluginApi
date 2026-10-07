/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.ErrorCode
import com.xfl.msgbot.plugin.api.rpc.LoopbackTransport
import com.xfl.msgbot.plugin.api.tooling.LanguageTools
import com.xfl.msgbot.plugin.api.tooling.ToolCompletion
import com.xfl.msgbot.plugin.api.tooling.ToolDiagnostic
import com.xfl.msgbot.plugin.api.tooling.ToolHover
import com.xfl.msgbot.plugin.api.tooling.ToolSeverity
import com.xfl.msgbot.plugin.api.tooling.ToolSignature
import com.xfl.msgbot.plugin.api.tooling.ToolSignatureHelp
import com.xfl.msgbot.plugin.api.tooling.ToolingCapability
import com.xfl.msgbot.plugin.api.tooling.ToolingChange
import com.xfl.msgbot.plugin.api.tooling.ToolingContext
import com.xfl.msgbot.plugin.api.tooling.ToolingFactory
import com.xfl.msgbot.plugin.api.tooling.ToolingFile
import com.xfl.msgbot.plugin.api.tooling.ToolingHost
import com.xfl.msgbot.plugin.api.tooling.ToolingWorkspace
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolingSessionTest {
    private class Tools(val context: ToolingContext) : LanguageTools {
        override val capabilities = setOf(ToolingCapability.DIAGNOSTICS, ToolingCapability.COMPLETION, ToolingCapability.HOVER)
        var workspace: ToolingWorkspace? = null
        val texts = linkedMapOf<String, String>()
        val threads = CopyOnWriteArrayList<Thread>()
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val diagnosed = CopyOnWriteArrayList<String>()

        override fun configure(workspace: ToolingWorkspace) {
            threads += Thread.currentThread()
            this.workspace = workspace
            texts.clear()
            workspace.libs.forEach { texts[it.path] = it.text }
        }

        override fun sync(change: ToolingChange) {
            threads += Thread.currentThread()
            change.removes.forEach { texts.remove(it) }
            change.upserts.forEach { texts[it.path] = it.text }
        }

        override fun diagnostics(path: String): List<ToolDiagnostic> {
            threads += Thread.currentThread()
            diagnosed += path
            if (path == "/slow.js") {
                holding.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            if (path == "/broken.js") throw CallException.unavailable("checker crashed")
            val text = texts[path] ?: return emptyList()
            val at = text.indexOf("oops")
            return if (at < 0) emptyList() else listOf(ToolDiagnostic(at, at + 4, ToolSeverity.WARNING, "no oops", "oops"))
        }

        override fun complete(
            path: String,
            offset: Int,
        ): List<ToolCompletion> {
            val imported = context.host.read("/project/dep.d.ts")
            val names = context.host.list("/project")
            return listOf(
                ToolCompletion("reply", "function", "(text: string)", insert = "reply", replaceStart = offset - 2, replaceEnd = offset),
                ToolCompletion("dep", "variable", detail = imported, doc = names?.joinToString()),
            )
        }

        override fun hover(
            path: String,
            offset: Int,
        ) = if (offset == 3) ToolHover(0, 5, "const bot: Bot") else null
    }

    private val transports = LoopbackTransport.pair()
    private val files = linkedMapOf("/project/dep.d.ts" to "declare const dep: number")
    private val errors = CopyOnWriteArrayList<String>()
    private lateinit var tools: Tools
    private val endpoint = ToolingEndpoint(transports.second, ToolingFactory { Tools(it).also { created -> tools = created } })
    private val callExecutor = Executors.newCachedThreadPool { r -> Thread(r, "tooling-host-reads").apply { isDaemon = true } }
    private val host =
        object : ToolingHost {
            override fun read(path: String): String? = files[path]

            override fun list(path: String): List<String>? = if (path == "/project") listOf("dep.d.ts", "src/") else null
        }
    private val remote = RemoteLanguageTools.connect(transports.first, host, callExecutor, timeoutMs = 2_000, onError = { errors += it })

    @AfterTest
    fun tearDown() {
        remote.close()
        endpoint.close()
        callExecutor.shutdownNow()
    }

    @Test
    fun `the host learns what the tooling can answer from hello`() {
        assertEquals(setOf(ToolingCapability.DIAGNOSTICS, ToolingCapability.COMPLETION, ToolingCapability.HOVER), remote.capabilities)
    }

    @Test
    fun `a workspace and its changes reach the tooling`() {
        remote.configure(ToolingWorkspace("javascript", mapOf("target" to "es5"), listOf(ToolingFile("/lib/api.d.ts", "declare const Api: any"))))
        remote.sync(ToolingChange(upserts = listOf(ToolingFile("/project/main.js", "bot.oops()"))))

        val workspace = tools.workspace!!
        assertEquals("javascript", workspace.language)
        assertEquals(mapOf("target" to "es5"), workspace.options)
        assertEquals(setOf("/lib/api.d.ts", "/project/main.js"), tools.texts.keys)

        remote.sync(ToolingChange(removes = listOf("/project/main.js")))
        assertEquals(setOf("/lib/api.d.ts"), tools.texts.keys)
    }

    @Test
    fun `diagnostics carry offsets, severity and code`() {
        remote.sync(ToolingChange(upserts = listOf(ToolingFile("/project/main.js", "bot.oops()"))))
        assertEquals(
            listOf(ToolDiagnostic(4, 8, ToolSeverity.WARNING, "no oops", "oops")),
            remote.diagnostics("/project/main.js"),
        )
        assertEquals(emptyList(), remote.diagnostics("/project/other.js"))
    }

    @Test
    fun `completions are read with the host files the tool asked for`() {
        val items = remote.complete("/project/main.js", 10)

        assertEquals(ToolCompletion("reply", "function", "(text: string)", insert = "reply", replaceStart = 8, replaceEnd = 10), items[0])
        assertEquals("declare const dep: number", items[1].detail)
        assertEquals("dep.d.ts, src/", items[1].doc)
    }

    @Test
    fun `a file the host does not have reads as missing`() {
        files.clear()
        val items = remote.complete("/project/main.js", 10)
        assertNull(items[1].detail)
    }

    @Test
    fun `hover is optional`() {
        assertEquals(ToolHover(0, 5, "const bot: Bot"), remote.hover("/project/main.js", 3))
        assertNull(remote.hover("/project/main.js", 4))
        assertNull(remote.signatureHelp("/project/main.js", 3))
    }

    @Test
    fun `every call runs on one thread`() {
        remote.configure(ToolingWorkspace("javascript"))
        remote.sync(ToolingChange(upserts = listOf(ToolingFile("/a.js", "x"))))
        remote.diagnostics("/a.js")
        assertEquals(1, tools.threads.distinct().size)
    }

    @Test
    fun `a failure keeps its code across the boundary`() {
        val e = assertFailsWith<CallException> { remote.diagnostics("/broken.js") }
        assertEquals(ErrorCode.UNAVAILABLE, e.code)
        assertEquals("checker crashed", e.message)
        assertEquals(emptyList(), remote.diagnostics("/project/main.js"), "the session survives a failed question")
    }

    @Test
    fun `a question the host gave up on is skipped`() {
        val slow = Executors.newSingleThreadExecutor().submit<Any?> { runCatching { remote.diagnostics("/slow.js") }.exceptionOrNull() }
        assertTrue(tools.holding.await(5, TimeUnit.SECONDS))
        val stale = Executors.newSingleThreadExecutor().submit<Any?> { runCatching { remote.diagnostics("/stale.js") }.exceptionOrNull() }
        assertTrue(stale.get(5, TimeUnit.SECONDS) is CallException, "the host stops waiting while the tooling is busy")
        val pastTheDeadlineMs = 100L
        Thread.sleep(pastTheDeadlineMs)
        tools.release.countDown()
        slow.get(5, TimeUnit.SECONDS)

        remote.diagnostics("/project/main.js")
        assertTrue("/stale.js" !in tools.diagnosed, "picked up after the deadline, it was not run: ${tools.diagnosed}")
    }

    @Test
    fun `a tooling that cannot start fails every call with its reason`() {
        val pair = LoopbackTransport.pair()
        val broken = ToolingEndpoint(pair.second, ToolingFactory { throw IllegalStateException("no runtime") })
        try {
            val e = assertFailsWith<Exception> { RemoteLanguageTools.connect(pair.first, host, callExecutor, timeoutMs = 2_000) }
            assertTrue(e.message!!.contains("no runtime"), e.message)
        } finally {
            broken.close()
        }
    }

    @Test
    fun `configure may take longer than a question`() {
        val pair = LoopbackTransport.pair()
        val slow =
            object : LanguageTools {
                override val capabilities = setOf(ToolingCapability.DIAGNOSTICS)

                override fun configure(workspace: ToolingWorkspace) = Thread.sleep(400)

                override fun sync(change: ToolingChange) = Unit
            }
        val endpoint = ToolingEndpoint(pair.second, ToolingFactory { slow })
        val patient = RemoteLanguageTools.connect(pair.first, host, callExecutor, timeoutMs = 100, setupTimeoutMs = 5_000)
        try {
            patient.configure(ToolingWorkspace("javascript"))
        } finally {
            patient.close()
            endpoint.close()
        }
    }
}
