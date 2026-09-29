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

/** A module spec and its implementation. Build with [provide] or [implement]. */
class ProviderModule(
    val spec: ModuleSpec,
    private val dispatch: (ProviderCall) -> Value,
) {
    /** Throws [CallException] for a specific [ErrorCode]; other exceptions mean [ErrorCode.FAILED]. */
    fun call(call: ProviderCall): Value = dispatch(call)
}

/** Returns a [Value] or anything [Value.of] converts. */
typealias Handler = (ProviderCall) -> Any?

/** Declares and implements a module. Every function needs a [ProviderFunctionBuilder.handle]. */
fun provide(
    namespace: String,
    version: Int = 1,
    block: ProviderModuleBuilder.() -> Unit,
): ProviderModule {
    val builder = ProviderModuleBuilder().apply(block)
    val spec = ModuleSpec(namespace, version, builder.functions.map { it.first }, builder.events.toList(), builder.doc)
    return ProviderModule(spec, dispatcher(spec, builder.functions.associate { (function, handler) -> function.name to handler }))
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
    handlers: Map<String, Handler>,
): (ProviderCall) -> Value =
    { call ->
        val handler =
            handlers[call.function]
                ?: throw CallException(ErrorCode.UNKNOWN_FUNCTION, "${spec.namespace} has no function '${call.function}'")
        Value.of(handler(call))
    }

@SchemaDsl
class ProviderModuleBuilder internal constructor() {
    var doc: String = ""
    internal val functions = mutableListOf<Pair<FunctionSpec, Handler>>()
    internal val events = mutableListOf<EventSpec>()

    fun function(
        name: String,
        returns: Type = Type.VOID,
        doc: String = "",
        block: ProviderFunctionBuilder.() -> Unit,
    ) {
        val builder = ProviderFunctionBuilder(name, returns, doc).apply(block)
        val handler = builder.handler ?: throw IllegalArgumentException("Function '$name' has no handle { } block")
        functions += builder.buildSpec() to handler
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
    internal var handler: Handler? = null

    fun handle(handler: Handler) {
        check(this.handler == null) { "handle { } given twice" }
        this.handler = handler
    }

    internal fun buildSpec(): FunctionSpec = build()
}

@SchemaDsl
class ImplementationBuilder internal constructor(private val spec: ModuleSpec) {
    internal val handlers = linkedMapOf<String, Handler>()
    internal val emitted = linkedSetOf<String>()

    fun handle(
        function: String,
        handler: Handler,
    ) {
        require(spec.function(function) != null) { "${spec.namespace} declares no function '$function'" }
        require(handlers.put(function, handler) == null) { "'$function' handled twice" }
    }

    /** Only these events are published. */
    fun emits(vararg events: String) {
        events.forEach { require(spec.event(it) != null) { "${spec.namespace} declares no event '$it'" } }
        emitted += events
    }
}

/** Wraps this module with [Fit.Accepted.spec], dropping arguments the provider does not accept and result fields the host does not know. */
fun ProviderModule.fittedTo(fit: Fit.Accepted): ProviderModule =
    ProviderModule(fit.spec) { call ->
        val understood = fit.accepts[call.function]
        val args = if (understood == null) call.args else Args(call.args.values.filterKeys { it in understood })
        val result = call(ProviderCall(call.projectId, call.function, args, call.options))
        fit.spec.function(call.function)?.conformResult(result) ?: result
    }

/** Throws if two modules share a namespace. */
fun Provider.modulesByNamespace(): Map<String, ProviderModule> {
    val duplicate = modules.groupBy { it.spec.namespace }.filterValues { it.size > 1 }.keys
    require(duplicate.isEmpty()) { "A provider publishes $duplicate more than once" }
    return modules.associateBy { it.spec.namespace }
}
