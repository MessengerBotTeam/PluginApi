/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.provider

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.ErrorCode
import com.xfl.msgbot.plugin.api.schema.EventSpec
import com.xfl.msgbot.plugin.api.schema.FieldsBuilder
import com.xfl.msgbot.plugin.api.schema.Fit
import com.xfl.msgbot.plugin.api.schema.FunctionSpec
import com.xfl.msgbot.plugin.api.schema.FunctionSpecBuilder
import com.xfl.msgbot.plugin.api.schema.ModuleSpec
import com.xfl.msgbot.plugin.api.schema.SchemaDsl
import com.xfl.msgbot.plugin.api.schema.Type
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/**
 * A module spec and its implementation. Build with [provide] or [implement].
 *
 * A call fails with [CallException] for a specific [ErrorCode]; any other exception means
 * [ErrorCode.FAILED].
 */
class ProviderModule(
    val spec: ModuleSpec,
    private val dispatch: Dispatch,
) {
    /** Answers a call now or later; the stage may complete on any thread. */
    fun interface Dispatch {
        fun call(call: ProviderCall): CompletionStage<Value>
    }

    constructor(spec: ModuleSpec, handler: (ProviderCall) -> Value) : this(spec, Dispatch { call -> answerNow { handler(call) } })

    /** Never throws, not even an [Error] such as `TODO()`: the failure is in the future, as it is for a remote provider. */
    fun callAsync(call: ProviderCall): CompletableFuture<Value> =
        try {
            dispatch.call(call).toCompletableFuture()
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }

    /** [callAsync], waiting for the answer. */
    fun call(call: ProviderCall): Value =
        try {
            callAsync(call).join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
}

/** Returns a [Value] or anything [Value.of] converts. Runs on the provider's thread. */
typealias Handler = (ProviderCall) -> Any?

/**
 * Starts on the provider's thread and returns at once; the stage completes later from any thread
 * with a [Value] or anything [Value.of] converts. The provider's thread is free meanwhile.
 */
typealias AsyncHandler = (ProviderCall) -> CompletionStage<*>

internal sealed interface Implementation {
    class Now(val handler: Handler) : Implementation

    class Later(val handler: AsyncHandler) : Implementation
}

private inline fun answerNow(block: () -> Value): CompletableFuture<Value> =
    try {
        CompletableFuture.completedFuture(block())
    } catch (e: Throwable) {
        CompletableFuture.failedFuture(e)
    }

/** Declares and implements a module. Every function needs a [ProviderFunctionBuilder.handle]. */
fun provide(
    namespace: String,
    version: Int = 1,
    block: ProviderModuleBuilder.() -> Unit,
): ProviderModule {
    val builder = ProviderModuleBuilder().apply(block)
    val spec = ModuleSpec(namespace, version, builder.functions.map { it.first }, builder.events.toList(), builder.doc)
    return ProviderModule(spec, dispatcher(spec, builder.functions.associate { (function, implementation) -> function.name to implementation }))
}

/**
 * Implements part of an existing spec, such as [com.xfl.msgbot.plugin.api.standard.StandardApi.Bot].
 * Publishes only the handled functions and the events listed in [ImplementationBuilder.emits].
 */
fun implement(
    spec: ModuleSpec,
    block: ImplementationBuilder.() -> Unit,
): ProviderModule {
    val builder = ImplementationBuilder(spec).apply(block)
    val published = spec.restrictTo(builder.handlers.keys, builder.emitted)
    return ProviderModule(published, dispatcher(published, builder.handlers.toMap()))
}

private fun dispatcher(
    spec: ModuleSpec,
    implementations: Map<String, Implementation>,
): ProviderModule.Dispatch =
    ProviderModule.Dispatch { call ->
        when (val implementation = implementations[call.function]) {
            null -> CompletableFuture.failedFuture(CallException(ErrorCode.UNKNOWN_FUNCTION, "${spec.namespace} has no function '${call.function}'"))
            is Implementation.Now -> answerNow { Value.of(implementation.handler(call)) }
            is Implementation.Later -> implementation.handler(call).thenApply { Value.of(it) }
        }
    }

@SchemaDsl
class ProviderModuleBuilder internal constructor() {
    var doc: String = ""
    internal val functions = mutableListOf<Pair<FunctionSpec, Implementation>>()
    internal val events = mutableListOf<EventSpec>()

    fun function(
        name: String,
        returns: Type = Type.VOID,
        doc: String = "",
        block: ProviderFunctionBuilder.() -> Unit,
    ) {
        val builder = ProviderFunctionBuilder(name, returns, doc).apply(block)
        val implementation = builder.implementation ?: throw IllegalArgumentException("Function '$name' has no handle { } block")
        functions += builder.buildSpec() to implementation
    }

    fun event(
        name: String,
        doc: String = "",
        block: FieldsBuilder.() -> Unit = {},
    ) {
        events += EventSpec(name, FieldsBuilder().apply(block).fields.toList(), doc)
    }
}

class ProviderFunctionBuilder internal constructor(
    name: String,
    returns: Type,
    doc: String,
) : FunctionSpecBuilder(name, returns, doc) {
    internal var implementation: Implementation? = null
        private set

    fun handle(handler: Handler) = set(Implementation.Now(handler))

    fun handleAsync(handler: AsyncHandler) = set(Implementation.Later(handler))

    private fun set(implementation: Implementation) {
        check(this.implementation == null) { "handle { } given twice" }
        this.implementation = implementation
    }

    internal fun buildSpec(): FunctionSpec = build()
}

@SchemaDsl
class ImplementationBuilder internal constructor(private val spec: ModuleSpec) {
    internal val handlers = linkedMapOf<String, Implementation>()
    internal val emitted = linkedSetOf<String>()

    fun handle(
        function: String,
        handler: Handler,
    ) = set(function, Implementation.Now(handler))

    fun handleAsync(
        function: String,
        handler: AsyncHandler,
    ) = set(function, Implementation.Later(handler))

    private fun set(
        function: String,
        implementation: Implementation,
    ) {
        require(spec.function(function) != null) { "${spec.namespace} declares no function '$function'" }
        require(handlers.put(function, implementation) == null) { "'$function' handled twice" }
    }

    /** Only these events are published. */
    fun emits(vararg events: String) {
        events.forEach { require(spec.event(it) != null) { "${spec.namespace} declares no event '$it'" } }
        emitted += events
    }
}

/** Wraps this module with [Fit.Accepted.spec], dropping arguments the provider does not accept and result fields the host does not know. */
fun ProviderModule.fittedTo(fit: Fit.Accepted): ProviderModule =
    ProviderModule(
        fit.spec,
        ProviderModule.Dispatch { call ->
            val understood = fit.accepts[call.function]
            val args = if (understood == null) call.args else Args(call.args.values.filterKeys { it in understood })
            callAsync(ProviderCall(call.projectId, call.function, args, call.options)).thenApply { result ->
                fit.spec.function(call.function)?.conformResult(result) ?: result
            }
        },
    )

/** Throws if two modules share a namespace. */
fun Provider.modulesByNamespace(): Map<String, ProviderModule> {
    val duplicate = modules.groupBy { it.spec.namespace }.filterValues { it.size > 1 }.keys
    require(duplicate.isEmpty()) { "A provider publishes $duplicate more than once" }
    return modules.associateBy { it.spec.namespace }
}
