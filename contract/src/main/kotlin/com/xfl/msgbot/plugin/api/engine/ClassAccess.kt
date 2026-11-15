/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.engine

import com.xfl.msgbot.plugin.api.value.Value
import com.xfl.msgbot.plugin.api.value.asArrayOrNull
import com.xfl.msgbot.plugin.api.value.asBooleanOrNull
import com.xfl.msgbot.plugin.api.value.asObjectOrNull
import com.xfl.msgbot.plugin.api.value.asStringOrNull

/**
 * Which Java classes a script of one project may use, for an engine that runs Java (`<engine java="true">`).
 * The host keeps it with the project and sends it in [LoadRequest.classAccess].
 *
 * Files are never part of this: a script reaches files only through the host, under the project's file rules,
 * whatever this says.
 *
 * @property preset the starting set, see [Preset].
 * @property allow class names added to the preset: an exact name, or a package followed by `.*` (`com.example.*`).
 * @property deny class names taken away, in the same form. They win over [allow] and the preset.
 * @property restrictions false turns the class rules off entirely, so a script may use any class. Files still go
 * through the host.
 */
data class ClassAccess(
    val preset: Preset = Preset.SAFE,
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    val restrictions: Boolean = true,
) {
    enum class Preset {
        /** No Java at all. */
        OFF,

        /** Ordinary classes; reflection, class loaders, processes and runtime internals only through checked stand-ins. */
        SAFE,

        /** [SAFE] plus real reflective members (`Method`, `Field`, `Constructor`) and `java.lang.Process`. */
        WIDE,

        /** [SAFE] with the project's own [allow] and [deny] lists. */
        CUSTOM,
    }

    val javaEnabled: Boolean get() = preset != Preset.OFF

    fun toValue(): Value =
        Value.VObject(
            mapOf(
                "preset" to Value.VString(preset.name.lowercase()),
                "allow" to Value.VArray(allow.map(Value::VString)),
                "deny" to Value.VArray(deny.map(Value::VString)),
                "restrictions" to Value.VBool(restrictions),
            ),
        )

    companion object {
        val DEFAULT = ClassAccess()

        /** Reads what [toValue] wrote. Unknown presets become [Preset.SAFE], so an older engine keeps the safe default. */
        fun fromValue(value: Value): ClassAccess {
            val map = value.asObjectOrNull() ?: throw IllegalArgumentException("Class access is a map")
            val preset = map["preset"]?.asStringOrNull()?.uppercase()?.let { name -> Preset.entries.firstOrNull { it.name == name } } ?: Preset.SAFE
            fun names(key: String) = map[key]?.asArrayOrNull()?.mapNotNull { it.asStringOrNull() }.orEmpty()
            return ClassAccess(preset, names("allow"), names("deny"), map["restrictions"]?.asBooleanOrNull() ?: true)
        }
    }
}
