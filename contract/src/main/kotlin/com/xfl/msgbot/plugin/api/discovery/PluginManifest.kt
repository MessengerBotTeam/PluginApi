/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.discovery

import com.xfl.msgbot.plugin.api.protocol.ProtocolVersion

/**
 * Discovery metadata a separate-APK plugin advertises on its service `<meta-data>`: everything the
 * host must know before binding. Anything needed only at load time travels as a frame instead.
 */
data class PluginManifest(
    val engineId: String,
    val packageName: String,
    val serviceClass: String,
    /** What this engine runs. Empty for a source, which runs nothing. */
    val languages: List<LanguageSupport> = emptyList(),
    /** Per-project settings this engine takes. The host offers them and does not read them. */
    val options: List<OptionDecl> = emptyList(),
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val abis: List<String> = emptyList(),
) {
    /** One `<engine>` element: an identity, what it runs, and what it can be told. */
    data class EngineDecl(
        val engineId: String,
        val languages: List<LanguageSupport>,
        val options: List<OptionDecl> = emptyList(),
    )

    /** One per-project setting; the host stores [key] and hands it back on `load`, unread. */
    data class OptionDecl(
        val key: String,
        val type: OptionType,
        val label: String,
        val default: String,
    )

    /** An enum so a `when` in the renderer breaks the build when a kind is added unhandled. */
    enum class OptionType {
        /** Drawn as a switch. Values travel as "true"/"false". */
        BOOLEAN,

        /** Drawn as a text field. */
        STRING,
    }

    /**
     * One language an engine runs, with everything the host needs before it can bind that engine:
     * what to call the file, how to colour it, what to put in it.
     */
    data class LanguageSupport(
        val name: String,
        val label: String,
        val extension: String,
        /** apiLevel -> starter template. A key here is a combination this engine really runs. */
        val apiLevels: Map<String, TemplateRef>,
        val editorScope: String? = null,
        val grammar: TemplateRef? = null,
        val icon: Int? = null,
    )

    /** A resource in the declaring plugin's package. Reading it needs that package's resources. */
    data class TemplateRef(val packageName: String, val resId: Int)

    companion object {
        const val PLUGIN_SERVICE_ACTION = "com.xfl.msgbot.plugin.ENGINE"

        /** Message source plugins advertise this instead; same transport, opposite direction. */
        const val SOURCE_SERVICE_ACTION = "com.xfl.msgbot.plugin.SOURCE"

        /**
         * Source identity, the open string a project points at. All a source declares here: its
         * capabilities and events are answered at runtime, in its Describe frame.
         */
        const val META_SOURCE_ID = "com.xfl.msgbot.plugin.sourceId"

        /** Signature-level permission guarding the plugin service (host signing key). */
        const val PLUGIN_PERMISSION = "com.xfl.msgbot.permission.PLUGIN"

        /**
         * Name to show the user, as `android:resource` so the host resolves it against the
         * plugin's own translations. Falls back to the app label.
         */
        const val META_DISPLAY_NAME = "com.xfl.msgbot.plugin.displayName"

        const val META_PROTOCOL = "com.xfl.msgbot.plugin.protocolVersion"

        /** What an engine runs lives in the XML named here; see [EngineManifestSchema]. */
        val META_ENGINE: String get() = EngineManifestSchema.META_ENGINE
    }
}
