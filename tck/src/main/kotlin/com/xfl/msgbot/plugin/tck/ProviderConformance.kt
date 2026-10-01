/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.tck

import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.call.ErrorCode
import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.schema.Fit
import com.xfl.msgbot.plugin.api.schema.Names
import com.xfl.msgbot.plugin.api.standard.StandardApi
import com.xfl.msgbot.plugin.api.value.Value
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What every provider must do, as tests. Extend it in the provider's test sources and implement
 * [createProvider]; override [sampleCalls] and [provokeEvents] so the suite exercises its functions
 * and events, not only its declarations.
 */
abstract class ProviderConformance {
    /** One call the provider must answer, and an optional check of the answer beyond its schema. */
    data class SampleCall(
        val function: String,
        val args: Map<String, Value> = emptyMap(),
        val projectId: String = PROJECT,
        val check: (Value) -> Unit = {},
    )

    protected abstract fun createProvider(): Provider

    /** The projects and options the provider starts with. */
    protected open val projects: Map<String, Map<String, String>> = mapOf(PROJECT to emptyMap())

    protected open fun sampleCalls(): List<SampleCall> = emptyList()

    /** Makes the started provider emit its events, for example by feeding it input. */
    protected open fun provokeEvents(provider: Provider) = Unit

    /** Events (`namespace.name`) that [provokeEvents] must produce. */
    protected open val expectedEvents: Set<String> = emptySet()

    protected lateinit var harness: ProviderHarness

    @Before
    fun openHarness() {
        harness = ProviderHarness(::createProvider)
    }

    @After
    fun closeHarness() = harness.close()

    @Test
    fun everyNamespaceIsOneThatAProviderMayPublish() {
        assertTrue(harness.modules.isNotEmpty(), "A provider publishes at least one module")
        val namespaces = harness.modules.map { it.namespace }
        assertEquals(namespaces.distinct(), namespaces, "Each namespace is published once")
        namespaces.forEach { namespace ->
            assertTrue(namespace.matches(Names.NAMESPACE), "'$namespace' is one lowercase word")
            assertTrue(namespace !in StandardApi.HOST_NAMESPACES, "'$namespace' belongs to the host")
        }
    }

    @Test
    fun standardModulesFitThisEditionOfTheStandard() {
        harness.modules.forEach { module ->
            val standard = StandardApi.STANDARDS[module.namespace] ?: return@forEach
            assertIs<Fit.Accepted>(standard.fit(module), "${module.namespace} does not fit the standard: ${standard.fit(module)}")
        }
    }

    @Test
    fun theHostReadsTheModulesExactlyAsDeclared() {
        assertEquals(harness.provider.modules.map { it.spec }, harness.modules)
        assertTrue(harness.errors.isEmpty(), "Nothing was left out: ${harness.errors}")
    }

    @Test
    fun itStartsStopsAndStartsAgain() {
        harness.start(projects)
        harness.stop()
        harness.start(projects)
        harness.stop()
        assertTrue(harness.errors.isEmpty(), "Reported: ${harness.errors}")
    }

    @Test
    fun aFunctionItDoesNotDeclareIsUnknown() {
        harness.start(projects)
        val namespace = harness.modules.first().namespace
        val answer = harness.call(PROJECT, "$namespace.tckNoSuchFunction")
        assertEquals(ErrorCode.UNKNOWN_FUNCTION, (answer as? CallResult.Err)?.code, "Answered $answer")
    }

    @Test
    fun sampleCallsAnswerWithinTheirSchema() {
        harness.start(projects)
        sampleCalls().forEach { sample ->
            val (namespace, name) = Names.split(sample.function) ?: fail("'${sample.function}' is not namespace.name")
            val spec = harness.modules.first { it.namespace == namespace }.function(name) ?: fail("${sample.function} is not declared")
            assertNull(spec.checkArgs(sample.args), "The sample's own arguments are wrong")
            when (val answer = harness.call(sample.projectId, sample.function, sample.args)) {
                is CallResult.Ok -> {
                    assertNull(spec.checkResult(answer.value), "${sample.function} answered outside its schema")
                    sample.check(answer.value)
                }
                is CallResult.Err -> fail("${sample.function} failed: ${answer.code} ${answer.message}")
            }
        }
    }

    @Test
    fun theEventsItEmitsFitTheirSchema() {
        harness.start(projects)
        provokeEvents(harness.provider)
        val emitted = harness.drainEvents()
        val missing = expectedEvents - emitted.map { it.event }.toSet()
        assertTrue(missing.isEmpty(), "Never emitted: $missing")
        emitted.forEach { emitted ->
            val (namespace, name) = Names.split(emitted.event) ?: fail("'${emitted.event}' is not namespace.name")
            val spec = harness.modules.firstOrNull { it.namespace == namespace }?.event(name) ?: fail("${emitted.event} is not declared")
            assertNull(spec.checkPayload(emitted.payload), "${emitted.event} carried a payload outside its schema")
            assertTrue(emitted.projectId == null || emitted.projectId in projects, "${emitted.event} went to an unknown project")
        }
    }

    @Test
    fun nothingIsEmittedOnceStopped() {
        harness.start(projects)
        harness.stop()
        // A stopped provider may refuse to be provoked; it only must not emit.
        runCatching { provokeEvents(harness.provider) }
        assertEquals(emptyList(), harness.drainEvents(200))
        assertTrue(harness.errors.isEmpty(), "Reported: ${harness.errors}")
    }

    companion object {
        const val PROJECT = "tck"
    }
}
