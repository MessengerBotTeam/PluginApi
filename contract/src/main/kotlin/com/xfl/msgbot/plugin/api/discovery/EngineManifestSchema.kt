/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.discovery

/**
 * The XML an engine uses to describe what it can run, pointed at by a single `<meta-data>`:
 *
 * ```xml
 * <meta-data android:name="com.xfl.msgbot.plugin.engine" android:resource="@xml/engine"/>
 * ```
 * ```xml
 * <engine id="chaquopy-python">
 *   <language name="python" label="@string/lang_python" extension="py"
 *             editorScope="source.python" grammar="@raw/python_tm" icon="@drawable/ic_python"/>
 *   <option key="venv" type="boolean" label="@string/opt_venv" default="true"/>
 * </engine>
 * ```
 *
 * Read before binding: the host needs this to offer the language and open the
 * editor, when no engine exists yet to be asked. The same schema and parser serve plugins and the
 * app's builtins; a document may hold more than one `<engine>` (the root element is not read).
 */
object EngineManifestSchema {
    /** `<meta-data android:name>` whose `android:resource` points at the XML above. */
    const val META_ENGINE = "com.xfl.msgbot.plugin.engine"

    const val TAG_ENGINE = "engine"
    const val TAG_LANGUAGE = "language"

    /** A per-project setting this engine takes; the host renders it and hands the answer back on `load`. */
    const val TAG_OPTION = "option"

    /** Engine identity on `<engine>`, the open string a project points at (e.g. "javet-node"). */
    const val ATTR_ID = "id"

    /** Language identity on `<language>`, lowercase (e.g. "python"). Matches what a project stores. */
    const val ATTR_NAME = "name"

    /** Name to show the user, as `@string/...` so it is translated the ordinary Android way. */
    const val ATTR_LABEL = "label"

    /** Script file extension, no dot (e.g. "py"). */
    const val ATTR_EXTENSION = "extension"

    /** TextMate scope for the editor (e.g. "source.python"). Absent means edit as plain text. */
    const val ATTR_EDITOR_SCOPE = "editorScope"

    /** TextMate grammar as `@raw/...`. Only consulted when the host has no grammar for the scope. */
    const val ATTR_GRAMMAR = "grammar"

    /** Language icon as `@drawable/...`. Absent falls back to a generic one. */
    const val ATTR_ICON = "icon"

    /** Option identity on `<option>`, the key its value is stored and delivered under. */
    const val ATTR_KEY = "key"

    /** One of [OPTION_BOOLEAN] or [OPTION_STRING]; any other type is dropped rather than guessed at. */
    const val ATTR_TYPE = "type"

    /** Rendered as a switch. Values travel as "true"/"false". */
    const val OPTION_BOOLEAN = "boolean"

    /** Rendered as a text field. */
    const val OPTION_STRING = "string"

    /** Value an `<option>` starts at, as a string. Absent means "" (or false for a boolean). */
    const val ATTR_DEFAULT = "default"
}
