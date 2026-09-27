package com.xfl.msgbot.plugin.api.discovery

/** XML metadata for language-specific script facades, independent of engine APKs. */
object ProfileManifestSchema {
    const val ACTION = PluginManifest.PROFILE_SERVICE_ACTION
    const val META_PROFILES = PluginManifest.META_PROFILES
    const val TAG_PROFILE = "profile"
    const val ATTR_ID = "id"
    const val ATTR_LANGUAGE = "language"
    const val ATTR_LABEL = "label"
    const val ATTR_TEMPLATE = "template"
    const val ATTR_SHIM = "shim"
    const val ATTR_NEW_PROJECTS = "newProjects"
}
