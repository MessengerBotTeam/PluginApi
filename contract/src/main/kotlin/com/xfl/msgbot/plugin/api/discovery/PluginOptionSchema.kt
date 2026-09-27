package com.xfl.msgbot.plugin.api.discovery

/**
 * Common per-project option declaration for engines, sources, and extensions.
 * Engines put `<option>` inside `<engine>`; provider services advertise an `<options>` XML
 * resource through [PluginManifest.META_OPTIONS].
 */
object PluginOptionSchema {
    const val TAG_OPTIONS = "options"
    const val TAG_OPTION = "option"
    const val ATTR_KEY = "key"
    const val ATTR_TYPE = "type"
    const val ATTR_LABEL = "label"
    const val ATTR_DEFAULT = "default"
    const val TYPE_BOOLEAN = "boolean"
    const val TYPE_STRING = "string"
}
