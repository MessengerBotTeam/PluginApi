/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.binding

/**
 * The names every language binding defines in the script's global scope. What each looks like in
 * a given language is that language's binding spec (docs/bindings); these are the same everywhere.
 */
object Binding {
    /** The project's modules, as data: a list of `ModuleSpec.toValue()`. Bound before the profile runs. */
    const val API = "__api"

    /** `__host_call(name, args)`: calls `name` with named `args`, returns its value or throws. */
    const val HOST_CALL = "__host_call"

    /** `__host_call_async(name, args)`: the same, answered later the language's own way (a Promise). */
    const val HOST_CALL_ASYNC = "__host_call_async"

    /** `__dispatch(name, payload)`: defined by the profile, called by the engine for each event. */
    const val DISPATCH = "__dispatch"
}

/** What the JavaScript binding shares between engines. */
object JavaScriptBinding {
    /**
     * A CommonJS `require` over the project's sources. Evaluating it yields
     * `function (sources, compile, fallback)` returning `makeRequire(fromPath)`:
     *
     * - `sources`: `{path: text}`, from [com.xfl.msgbot.plugin.api.engine.LoadRequest.sources]
     * - `compile(path, text)`: the engine's way of turning a module into
     *   `function (exports, require, module, __filename, __dirname)`
     * - `fallback(specifier)`: the runtime's own `require` for anything that is not a relative
     *   path (Node's builtins and packages), or null
     *
     * Engines set the global `require` to `makeRequire(entry)` before running the entry.
     */
    val MODULE_LOADER: String by lazy {
        checkNotNull(JavaScriptBinding::class.java.getResourceAsStream("javascript/modules.js")) { "modules.js is missing from the PluginApi jar" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
}
