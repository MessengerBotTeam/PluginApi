/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.discovery

/**
 * How an APK tells the host what it contains, before anything is bound. One service, one
 * `<meta-data>`, one XML file listing every engine, profile, source and extension in the APK:
 *
 * ```xml
 * <service android:name=".MyPluginService" android:exported="true"
 *     android:permission="com.xfl.msgbot.permission.PLUGIN">
 *     <intent-filter><action android:name="com.xfl.msgbot.plugin.PLUGIN" /></intent-filter>
 *     <meta-data android:name="com.xfl.msgbot.plugin" android:resource="@xml/msgbot_plugin" />
 * </service>
 * ```
 * ```xml
 * <msgbot-plugin protocol="4">
 *     <engine id="luaj">
 *         <language name="lua" label="@string/lua" extension="lua" editorScope="source.lua" />
 *         <option key="strictGlobals" type="boolean" label="@string/strict" default="false" />
 *     </engine>
 *     <profile id="api2" language="lua" label="@string/lua_api2" shim="@raw/api2" template="@raw/starter"
 *         requires="bot project log" />
 *     <source id="discord" label="@string/discord">
 *         <option key="channel" type="string" label="@string/channel" />
 *     </source>
 *     <extension id="weather" namespace="weather" label="@string/weather" />
 * </msgbot-plugin>
 * ```
 *
 * The host shows, stores and checks everything here without running plugin code. What a provider
 * can do in detail (its module spec) it says itself when bound.
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
    const val TAG_SOURCE = "source"
    const val TAG_EXTENSION = "extension"
    const val TAG_OPTION = "option"

    /** On the root: the protocol the plugin speaks, checked before binding. */
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

    /** Space-separated namespaces the profile needs, each optionally `name@minVersion`. */
    const val ATTR_REQUIRES = "requires"

    /** `false` keeps a profile for existing projects but out of the new-project list. */
    const val ATTR_NEW_PROJECTS = "newProjects"

    // <extension>
    const val ATTR_NAMESPACE = "namespace"

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
