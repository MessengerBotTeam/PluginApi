/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.discovery

/**
 * Names used in a plugin's manifest: the service's intent action and `<meta-data>`, and the XML
 * resource listing its engines, profiles and providers. The host reads it without running plugin
 * code. See readme.md for an example.
 */
object PluginManifestSchema {
    const val ACTION = "com.xfl.msgbot.plugin.PLUGIN"
    const val META_DATA = "com.xfl.msgbot.plugin"

    /** Signature-level permission declared by the host; every plugin service must require it. */
    const val PERMISSION = "com.xfl.msgbot.permission.PLUGIN"

    const val TAG_ROOT = "msgbot-plugin"
    const val TAG_ENGINE = "engine"
    const val TAG_LANGUAGE = "language"
    const val TAG_PROFILE = "profile"
    const val TAG_PROVIDER = "provider"
    const val TAG_TOOLING = "tooling"
    const val TAG_OPTION = "option"

    /** Root attribute: the plugin's [com.xfl.msgbot.plugin.api.protocol.ProtocolVersion.CURRENT]. */
    const val ATTR_PROTOCOL = "protocol"

    /** Unique per component kind within the APK. */
    const val ATTR_ID = "id"

    /** A `@string/...` resource. */
    const val ATTR_LABEL = "label"

    // <language>
    const val ATTR_NAME = "name"
    const val ATTR_EXTENSION = "extension"
    const val ATTR_EDITOR_SCOPE = "editorScope"
    const val ATTR_GRAMMAR = "grammar"
    const val ATTR_ICON = "icon"

    // <profile>
    const val ATTR_LANGUAGE = "language"
    const val ATTR_SHIM = "shim"
    const val ATTR_TEMPLATE = "template"

    /**
     * Space-separated namespaces, each optionally `name@minVersion`. Same-APK providers of these
     * are selected with the profile.
     */
    const val ATTR_REQUIRES = "requires"

    /** `false` hides the profile from new projects only. */
    const val ATTR_NEW_PROJECTS = "newProjects"

    // <provider>
    /** Space-separated namespaces. Publishing an unlisted namespace is refused. */
    const val ATTR_PROVIDES = "provides"

    // <tooling>
    /** Space-separated language names (`<language name>`) the tooling serves, such as `javascript typescript`. */
    const val ATTR_LANGUAGES = "languages"

    // <option>
    const val ATTR_KEY = "key"
    const val ATTR_TYPE = "type"
    const val ATTR_DEFAULT = "default"
    const val OPTION_BOOLEAN = "boolean"
    const val OPTION_STRING = "string"
}

/** Session role passed when binding. */
object PluginRole {
    const val ENGINE = "engine"
    const val PROVIDER = "provider"
    const val TOOLING = "tooling"
}
