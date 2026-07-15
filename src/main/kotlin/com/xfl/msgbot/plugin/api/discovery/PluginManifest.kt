/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.discovery

import com.xfl.msgbot.plugin.api.protocol.ProtocolVersion

/**
 * Discovery metadata a separate-APK engine plugin advertises. Phase 2 parses this from the
 * plugin service `<meta-data>` for signature verification and capability negotiation. Defined
 * now, not yet consumed (Phase 1 uses in-process engines only).
 */
data class PluginManifest(
    val engineId: String,
    val packageName: String,
    val serviceClass: String,
    val languages: List<String>,
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val abis: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    /** apiLevels this plugin ships its own shim for. Only consulted when the host has no shim
     *  for the plugin's language; empty means "none declared". */
    val apiLevels: List<String> = emptyList(),
) {
    companion object {
        const val PLUGIN_SERVICE_ACTION = "com.xfl.msgbot.plugin.ENGINE"

        /** Message source plugins advertise this instead; same transport, opposite direction. */
        const val SOURCE_SERVICE_ACTION = "com.xfl.msgbot.plugin.SOURCE"

        /** Source identity, the open string a project points at (mirrors engineId). */
        const val META_SOURCE_ID = "com.xfl.msgbot.plugin.sourceId"

        /** Signature-level permission guarding the plugin service (host signing key). */
        const val PLUGIN_PERMISSION = "com.xfl.msgbot.permission.PLUGIN"

        /**
         * Name to show the user, declared with `android:resource` (not `android:value`) so it is a
         * string resource the host resolves against the plugin's own resources: Android then picks
         * the right translation, and the host can resolve it in whatever language the user chose
         * for the app rather than the plugin's idea of it. Falls back to the app label.
         */
        const val META_DISPLAY_NAME = "com.xfl.msgbot.plugin.displayName"

        // <meta-data> keys a plugin service declares in its manifest.
        const val META_ENGINE_ID = "com.xfl.msgbot.plugin.engineId"
        const val META_LANGUAGES = "com.xfl.msgbot.plugin.languages"
        const val META_PROTOCOL = "com.xfl.msgbot.plugin.protocolVersion"

        /** Comma-separated apiLevels this plugin ships shims for, e.g. "API2,LEGACY". */
        const val META_API_LEVELS = "com.xfl.msgbot.plugin.apiLevels"

        /** Comma-separated capabilities this plugin/source declares support for. */
        const val META_CAPABILITIES = "com.xfl.msgbot.plugin.capabilities"
    }
}
