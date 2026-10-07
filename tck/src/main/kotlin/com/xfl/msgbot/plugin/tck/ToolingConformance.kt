/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.tooling.ToolingCapability
import com.xfl.msgbot.plugin.api.tooling.ToolingChange
import com.xfl.msgbot.plugin.api.tooling.ToolingFactory
import com.xfl.msgbot.plugin.api.tooling.ToolingFile
import com.xfl.msgbot.plugin.api.tooling.ToolingWorkspace
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What every language tooling must do, as tests. Extend it in the plugin's test sources and
 * implement [factory]. Override [sample] and [completionOffset] so the suite asks about text the
 * tooling understands.
 */
abstract class ToolingConformance {
    protected abstract val factory: ToolingFactory

    protected open val language: String = "javascript"

    /** Declaration files the workspace starts with. */
    protected open val libs: List<ToolingFile> = emptyList()

    /** A document the tooling can analyse. */
    protected open val sample: ToolingFile = ToolingFile("/project/main.js", "const answer = 42\nanswer.")

    /** Where completion is asked in [sample]. */
    protected open val completionOffset: Int get() = sample.text.length

    protected lateinit var harness: ToolingHarness

    @Before
    fun openHarness() {
        harness = ToolingHarness(factory)
        harness.tools.configure(ToolingWorkspace(language, libs = libs))
        harness.tools.sync(ToolingChange(upserts = listOf(sample)))
    }

    @After
    fun closeHarness() = harness.close()

    @Test
    fun aToolingAnswersAtLeastOneKindOfQuestion() {
        assertTrue(harness.tools.capabilities.isNotEmpty(), "A tooling lists what it can answer in capabilities")
    }

    @Test
    fun diagnosticsStayInsideTheText() {
        if (ToolingCapability.DIAGNOSTICS !in harness.tools.capabilities) return
        harness.tools.diagnostics(sample.path).forEach {
            assertTrue(it.start in 0..it.end, "A diagnostic starts before it ends: $it")
            assertTrue(it.end <= sample.text.length, "A diagnostic ends inside the text: $it")
            assertTrue(it.message.isNotBlank(), "A diagnostic says what is wrong: $it")
        }
    }

    @Test
    fun completionsNameWhatTheyInsertAndReplaceTextBeforeTheCursor() {
        if (ToolingCapability.COMPLETION !in harness.tools.capabilities) return
        val offset = completionOffset
        harness.tools.complete(sample.path, offset).forEach {
            assertTrue(it.label.isNotBlank(), "A completion has a label: $it")
            val start = it.replaceStart ?: offset
            val end = it.replaceEnd ?: offset
            assertTrue(start in 0..end && end <= sample.text.length, "A completion replaces text inside the file: $it")
        }
    }

    @Test
    fun aFileCanBeRemovedAndSentAgain() {
        harness.tools.sync(ToolingChange(removes = listOf(sample.path)))
        harness.tools.sync(ToolingChange(upserts = listOf(sample)))
        if (ToolingCapability.DIAGNOSTICS in harness.tools.capabilities) harness.tools.diagnostics(sample.path)
    }

    @Test
    fun aQuestionAboutAnUnknownFileDoesNotEndTheSession() {
        try {
            if (ToolingCapability.DIAGNOSTICS in harness.tools.capabilities) harness.tools.diagnostics("/project/unknown.js")
            if (ToolingCapability.COMPLETION in harness.tools.capabilities) harness.tools.complete("/project/unknown.js", 0)
        } catch (_: CallException) {
            // Refusing is allowed; the next question must still be answered.
        }
        if (ToolingCapability.DIAGNOSTICS in harness.tools.capabilities) harness.tools.diagnostics(sample.path)
    }

    @Test
    fun startingOverKeepsTheSessionWorking() {
        harness.tools.configure(ToolingWorkspace(language, libs = libs))
        harness.tools.sync(ToolingChange(upserts = listOf(sample)))
        if (ToolingCapability.DIAGNOSTICS in harness.tools.capabilities) harness.tools.diagnostics(sample.path)
        assertEquals(emptyList(), harness.errors, "Nothing is reported as a failure no request waits on")
    }
}
