/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.tooling.LanguageTools
import com.xfl.msgbot.plugin.api.tooling.ToolCompletion
import com.xfl.msgbot.plugin.api.tooling.ToolDiagnostic
import com.xfl.msgbot.plugin.api.tooling.ToolSeverity
import com.xfl.msgbot.plugin.api.tooling.ToolingCapability
import com.xfl.msgbot.plugin.api.tooling.ToolingChange
import com.xfl.msgbot.plugin.api.tooling.ToolingFactory
import com.xfl.msgbot.plugin.api.tooling.ToolingWorkspace

class ExampleToolingConformanceTest : ToolingConformance() {
    private class Todos : LanguageTools {
        private val texts = linkedMapOf<String, String>()

        override val capabilities = setOf(ToolingCapability.DIAGNOSTICS, ToolingCapability.COMPLETION)

        override fun configure(workspace: ToolingWorkspace) = texts.clear()

        override fun sync(change: ToolingChange) {
            change.removes.forEach { texts.remove(it) }
            change.upserts.forEach { texts[it.path] = it.text }
        }

        override fun diagnostics(path: String): List<ToolDiagnostic> {
            val text = texts[path] ?: return emptyList()
            val at = text.indexOf("TODO")
            return if (at < 0) emptyList() else listOf(ToolDiagnostic(at, at + 4, ToolSeverity.HINT, "A todo"))
        }

        override fun complete(
            path: String,
            offset: Int,
        ): List<ToolCompletion> = listOf(ToolCompletion("answer", "variable", replaceStart = offset, replaceEnd = offset))
    }

    override val factory = ToolingFactory { Todos() }
}
