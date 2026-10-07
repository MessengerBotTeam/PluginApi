/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.remote.Wire.list
import com.xfl.msgbot.plugin.api.remote.Wire.map
import com.xfl.msgbot.plugin.api.remote.Wire.string
import com.xfl.msgbot.plugin.api.tooling.ToolCompletion
import com.xfl.msgbot.plugin.api.tooling.ToolDiagnostic
import com.xfl.msgbot.plugin.api.tooling.ToolHover
import com.xfl.msgbot.plugin.api.tooling.ToolSeverity
import com.xfl.msgbot.plugin.api.tooling.ToolSignature
import com.xfl.msgbot.plugin.api.tooling.ToolSignatureHelp
import com.xfl.msgbot.plugin.api.tooling.ToolingChange
import com.xfl.msgbot.plugin.api.tooling.ToolingFile
import com.xfl.msgbot.plugin.api.tooling.ToolingWorkspace
import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asIntOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull

/**
 * Encoding of the tooling role's requests and answers. What the host reads from a plugin is read
 * leniently: an entry it cannot read is left out, so one bad completion does not hide the rest.
 */
internal object ToolingWire {
    fun workspace(workspace: ToolingWorkspace): Value =
        Wire.obj("language" to workspace.language, "options" to workspace.options, "libs" to workspace.libs.map(::file))

    fun workspaceOf(value: Value): ToolingWorkspace {
        val map = value.map()
        return ToolingWorkspace(
            language = map.string("language"),
            options = map["options"]?.map()?.mapValues { (_, v) -> v.asStringOrNull().orEmpty() }.orEmpty(),
            libs = map["libs"]?.list()?.map(::fileOf).orEmpty(),
        )
    }

    fun change(change: ToolingChange): Value = Wire.obj("upserts" to change.upserts.map(::file), "removes" to change.removes)

    fun changeOf(value: Value): ToolingChange {
        val map = value.map()
        return ToolingChange(
            upserts = map["upserts"]?.list()?.map(::fileOf).orEmpty(),
            removes = map["removes"]?.list()?.map { it.asStringOrNull() ?: throw IllegalArgumentException("A path is text") }.orEmpty(),
        )
    }

    fun diagnostics(items: List<ToolDiagnostic>): Value =
        Value.VArray(
            items.map {
                Wire.obj("start" to it.start, "end" to it.end, "severity" to it.severity.wire, "message" to it.message, "code" to it.code)
            },
        )

    fun diagnosticsOf(value: Value): List<ToolDiagnostic> =
        lenient(value) { map ->
            ToolDiagnostic(
                start = map.int("start"),
                end = map.int("end"),
                severity = ToolSeverity.of(map["severity"]?.asStringOrNull()),
                message = map.string("message"),
                code = map["code"]?.asStringOrNull(),
            )
        }

    fun completions(items: List<ToolCompletion>): Value =
        Value.VArray(
            items.map {
                Wire.obj(
                    "label" to it.label,
                    "kind" to it.kind,
                    "detail" to it.detail,
                    "doc" to it.doc,
                    "insert" to it.insert,
                    "replaceStart" to it.replaceStart,
                    "replaceEnd" to it.replaceEnd,
                )
            },
        )

    fun completionsOf(value: Value): List<ToolCompletion> =
        lenient(value) { map ->
            ToolCompletion(
                label = map.string("label"),
                kind = map["kind"]?.asStringOrNull().orEmpty(),
                detail = map["detail"]?.asStringOrNull(),
                doc = map["doc"]?.asStringOrNull(),
                insert = map["insert"]?.asStringOrNull(),
                replaceStart = map["replaceStart"]?.asIntOrNull(),
                replaceEnd = map["replaceEnd"]?.asIntOrNull(),
            )
        }

    fun hover(hover: ToolHover?): Value =
        if (hover == null) Value.VNull else Wire.obj("start" to hover.start, "end" to hover.end, "text" to hover.text)

    fun hoverOf(value: Value): ToolHover? =
        runCatching {
            val map = value.map()
            ToolHover(map.int("start"), map.int("end"), map.string("text"))
        }.getOrNull()

    fun signatureHelp(help: ToolSignatureHelp?): Value =
        if (help == null) {
            Value.VNull
        } else {
            Wire.obj(
                "signatures" to
                    help.signatures.map { Wire.obj("label" to it.label, "doc" to it.doc, "parameters" to it.parameters) },
                "activeSignature" to help.activeSignature,
                "activeParameter" to help.activeParameter,
            )
        }

    fun signatureHelpOf(value: Value): ToolSignatureHelp? =
        runCatching {
            val map = value.map()
            val signatures =
                lenient(map.getValue("signatures")) { signature ->
                    ToolSignature(
                        label = signature.string("label"),
                        doc = signature["doc"]?.asStringOrNull(),
                        parameters = signature["parameters"]?.list()?.mapNotNull { it.asStringOrNull() }.orEmpty(),
                    )
                }
            ToolSignatureHelp(signatures, map["activeSignature"]?.asIntOrNull() ?: 0, map["activeParameter"]?.asIntOrNull() ?: 0)
        }.getOrNull()?.takeIf { it.signatures.isNotEmpty() }

    private fun file(file: ToolingFile): Value = Wire.obj("path" to file.path, "text" to file.text)

    private fun fileOf(value: Value): ToolingFile {
        val map = value.map()
        return ToolingFile(map.string("path"), map.string("text"))
    }

    private fun <T> lenient(
        value: Value,
        read: (Map<String, Value>) -> T,
    ): List<T> = (value as? Value.VArray)?.items.orEmpty().mapNotNull { runCatching { read(it.map()) }.getOrNull() }

    private fun Map<String, Value>.int(key: String): Int = this[key]?.asIntOrNull() ?: throw IllegalArgumentException("'$key' is missing")
}
