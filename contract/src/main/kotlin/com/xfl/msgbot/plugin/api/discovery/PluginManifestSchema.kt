/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.discovery

/**
 * How an APK tells the host what it contains, before anything is bound. One service, one
 * `<meta-data>`, one XML file listing every engine, profile and provider in the APK:
 *
 * ```xml
 * <service android:name=".MyPluginService" android:exported="true"
 *     android:permission="com.xfl.msgbot.permission.PLUGIN">
 *     <intent-filter><action android:name="com.xfl.msgbot.plugin.PLUGIN" /></intent-filter>
 *     <meta-data android:name="com.xfl.msgbot.plugin" android:resource="@xml/msgbot_plugin" />
 * </service>
 * ```
 *
 * For example, a plugin that adds Lua (an engine and a Lua API for it) and a source that reads the
 * messenger's database directly:
 *
 * ```xml
 * <msgbot-plugin protocol="0">
 *     <engine id="luaj">
 *         <language name="lua" label="@string/lua" extension="lua" editorScope="source.lua" />
 *         <option key="strictGlobals" type="boolean" label="@string/strict" default="false" />
 *     </engine>
 *     <profile id="api2" language="lua" label="@string/lua_api2" shim="@raw/api2" template="@raw/starter"
 *         requires="bot project log" />
 *     <provider id="kakao-db" label="@string/kakao_db" provides="bot kakao">
 *         <option key="root" type="boolean" label="@string/use_root" default="true" />
 *     </provider>
 * </msgbot-plugin>
 * ```
 *
 * The host shows, stores and checks everything here without running plugin code. [ATTR_PROVIDES]
 * is a promise: a provider that publishes a namespace it did not list is refused. The detail of
 * each module (its functions and events) the provider says itself when bound.
 */
object PluginManifestSchema {
    const val ACTION = "com.xfl.msgbot.plugin.PLUGIN"
    const val META_DATA = "com.xfl.msgbot.plugin"

    /** Signature-level permission, declared by the host, guarding every plugin service. */
    const val PERMISSION = "com.xfl.msgbot.permission.PLUGIN"

    const val TAG_ROOT = "msgbot-plugin"
    const val TAG_ENGINE = "engine"
    const val TAG_LANGUAGE = "language"
    const val TAG_PROFILE = "profile"
    const val TAG_PROVIDER = "provider"
    const val TAG_OPTION = "option"

    /**
     * On the root: the newest protocol the plugin speaks, its PluginApi's
     * [com.xfl.msgbot.plugin.api.protocol.ProtocolVersion.CURRENT]. The host skips a plugin too
     * old for it; which version a session speaks is agreed when it opens.
     */
    const val ATTR_PROTOCOL = "protocol"

    /** Every component: its ID, unique per kind within the APK. */
    const val ATTR_ID = "id"

    /** Every component, language and option: `@string/...`, translated the ordinary Android way. */
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
     * Space-separated namespaces the profile needs, each optionally `name@minVersion`. A provider
     * in the same APK that provides one of them is selected along with the profile.
     */
    const val ATTR_REQUIRES = "requires"

    /** `false` keeps a profile for existing projects but out of the new-project list. */
    const val ATTR_NEW_PROJECTS = "newProjects"

    // <provider>
    /** Space-separated namespaces the provider publishes: standard ones (`bot`) and its own. */
    const val ATTR_PROVIDES = "provides"

    // <option>
    const val ATTR_KEY = "key"
    const val ATTR_TYPE = "type"
    const val ATTR_DEFAULT = "default"
    const val OPTION_BOOLEAN = "boolean"
    const val OPTION_STRING = "string"
}

/** The role a session is opened for, passed when binding. */
object PluginRole {
    const val ENGINE = "engine"
    const val PROVIDER = "provider"
}
