/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.binding

/**
 * Globals a language binding defines for its profiles. Only engines and profiles use them; the host
 * does not. See docs/bindings/README.md.
 */
object Binding {
    /** List of `ModuleSpec.toValue()` for the project. Bound before the profile runs. */
    const val API = "__api"

    /** `__host_call(name, args)`: returns the result or throws. */
    const val HOST_CALL = "__host_call"

    /** `__host_call_async(name, args)`: async variant, e.g. returns a Promise. */
    const val HOST_CALL_ASYNC = "__host_call_async"

    /** `__dispatch(name, payload)`: defined by the profile, called by the engine per event. */
    const val DISPATCH = "__dispatch"
}

/**
 * The JavaScript binding. Part of the contract so every JavaScript engine, built-in or plugin, runs
 * the same profiles. Spec: docs/bindings/javascript.md.
 */
object JavaScriptBinding {
    /** Module name of the profile kit: `require('msgbot')`. */
    const val KIT = "msgbot"

    /** Extensions of JavaScript sources besides the language's own `js`: ES modules and CommonJS files. */
    val MODULE_EXTENSIONS: Set<String> = setOf("mjs", "cjs")

    /**
     * Module loader source. Evaluates to `function (sources, compile, fallback, java, esm)` returning
     * `makeRequire(fromPath)`. Engines set the global `require` to `makeRequire(entry)` before running the profile.
     *
     * - `compile(path, text)` returns the CommonJS wrapper function `(exports, require, module, __filename, __dirname)`.
     * - `fallback(specifier)` is the runtime's own `require`, or null.
     * - `java(name, isClass)` returns the Java package or class for a `java:` module: null when the project may not use
     *   Java, undefined when there is no such class. Pass null instead of a function when the engine runs no Java.
     * - `esm` is true when the engine runs ES modules itself. It then compiles every module that
     *   `makeRequire.esm.entry(path)` and `makeRequire.esm.moduleFor(specifier, fromPath)` hand it (`{ name, source, esm }`,
     *   one module per `name`; `makeRequire.esm.nameOf(specifier, fromPath)` gives that name without loading anything).
     *   When false, files with `#!support esm` on their first line are converted to CommonJS.
     * - `makeRequire.script(path)` is the entry file as source for a global script: without its `#!` line, converted
     *   when needed. An ES module engine runs an entry whose `makeRequire.esm.kindOf(path)` is `"esm"` as a module instead.
     *
     * The rules are in `docs/bindings/javascript.md`.
     */
    val MODULE_LOADER: String by lazy {
        resource("modules.js").replace("/*BUILTINS*/null", jsonObject(mapOf(KIT to resource("kit.js"))))
    }

    private fun resource(name: String): String =
        checkNotNull(JavaScriptBinding::class.java.getResourceAsStream("javascript/$name")) { "$name is missing from the PluginApi jar" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun jsonObject(entries: Map<String, String>): String =
        entries.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "${jsonString(k)}: ${jsonString(v)}" }

    private fun jsonString(text: String): String =
        buildString {
            append('"')
            for (c in text) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' || c == '\u2028' || c == '\u2029' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
}
