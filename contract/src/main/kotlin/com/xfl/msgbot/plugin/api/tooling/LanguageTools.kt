/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.tooling

/**
 * What a plugin gives the editor for one language: diagnostics, completion, hover and signature
 * help. The host sends the text being edited and asks questions about it; it never runs the
 * language server's code, and the plugin never edits files.
 *
 * Calls arrive one at a time on one thread, in the order the host made them. Everything is plain
 * text, so an implementation may be a language server, a type checker library or a hand-written
 * parser. Offsets are UTF-16 code units from the start of the file, the unit of Java and
 * JavaScript strings and of the editor's own indexes.
 *
 * Every function but [configure] and [sync] has an empty default so a tool implements only what
 * it can answer. It lists those in [capabilities]; the host does not ask for the others.
 */
interface LanguageTools : AutoCloseable {
    val capabilities: Set<ToolingCapability>

    /**
     * Starts or restarts the workspace. Drops every file from earlier calls, so [sync] sends the
     * documents again.
     */
    fun configure(workspace: ToolingWorkspace)

    /** Applies edits to the host's view of the files. [ToolingChange.removes] is applied first. */
    fun sync(change: ToolingChange)

    fun diagnostics(path: String): List<ToolDiagnostic> = emptyList()

    fun complete(
        path: String,
        offset: Int,
    ): List<ToolCompletion> = emptyList()

    fun hover(
        path: String,
        offset: Int,
    ): ToolHover? = null

    fun signatureHelp(
        path: String,
        offset: Int,
    ): ToolSignatureHelp? = null

    override fun close() = Unit
}

enum class ToolingCapability(val wire: String) {
    DIAGNOSTICS("diagnostics"),
    COMPLETION("completion"),
    HOVER("hover"),
    SIGNATURE_HELP("signatureHelp"),
    ;

    companion object {
        /** Unknown names, from a newer plugin, are left out. */
        fun of(wire: String): ToolingCapability? = entries.firstOrNull { it.wire == wire }
    }
}

/** Creates the tools of one session. [create] runs on the thread that then serves every call. */
fun interface ToolingFactory {
    fun create(context: ToolingContext): LanguageTools
}

interface ToolingContext {
    /** Reads what the host allows: the project's files and the declaration files it ships. */
    val host: ToolingHost

    /** Tells the host about a failure no request is waiting on. */
    fun reportError(
        message: String,
        error: Throwable? = null,
    )
}

/**
 * Read-only access to the host's files, for a tool that follows imports. Calls block until the
 * host answers, so make them from the tool's own thread, and cache what you read.
 */
interface ToolingHost {
    /** The text of the file at [path], or null if it does not exist, is not text or is not readable by the plugin. */
    fun read(path: String): String?

    /** The names inside the directory at [path], with `/` after a directory's, or null if there is no such directory. */
    fun list(path: String): List<String>?
}

/**
 * [libs] are declaration files that belong to no project, such as the standard library or the
 * API a project can call. [options] are the plugin's own settings.
 */
class ToolingWorkspace(
    val language: String,
    val options: Map<String, String> = emptyMap(),
    val libs: List<ToolingFile> = emptyList(),
)

/** [path] is the host's path; the same string names the file in later calls and in [ToolingHost]. */
class ToolingFile(
    val path: String,
    val text: String,
)

class ToolingChange(
    val upserts: List<ToolingFile> = emptyList(),
    val removes: List<String> = emptyList(),
)

enum class ToolSeverity(val wire: String) {
    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
    HINT("hint"),
    ;

    companion object {
        fun of(wire: String?): ToolSeverity = entries.firstOrNull { it.wire == wire } ?: ERROR
    }
}

/** A problem in `[start, end)`. [code] is the tool's own identifier for the kind of problem. */
data class ToolDiagnostic(
    val start: Int,
    val end: Int,
    val severity: ToolSeverity,
    val message: String,
    val code: String? = null,
)

/**
 * A completion. The host replaces `[replaceStart, replaceEnd)` with [insert], or with [label]
 * and the word before the cursor when [insert] is null. [kind] is a free word such as `function`,
 * `property` or `keyword`; the host shows an icon for those it knows.
 */
data class ToolCompletion(
    val label: String,
    val kind: String = "",
    val detail: String? = null,
    val doc: String? = null,
    val insert: String? = null,
    val replaceStart: Int? = null,
    val replaceEnd: Int? = null,
)

/** [text] is plain text; the host does not render markup. */
data class ToolHover(
    val start: Int,
    val end: Int,
    val text: String,
)

data class ToolSignature(
    val label: String,
    val doc: String? = null,
    val parameters: List<String> = emptyList(),
)

data class ToolSignatureHelp(
    val signatures: List<ToolSignature>,
    val activeSignature: Int = 0,
    val activeParameter: Int = 0,
)
