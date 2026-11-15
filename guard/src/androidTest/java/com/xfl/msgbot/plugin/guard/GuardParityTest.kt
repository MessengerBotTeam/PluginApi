/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier
import kotlin.test.assertEquals

/**
 * Every public constructor, method and field of a replaced class on this Android version must exist on its
 * replacement with the same parameters, so a script written for the real class finds what it calls.
 */
@RunWith(AndroidJUnit4::class)
class GuardParityTest {
    // Not offered on purpose; each needs a reason.
    private val skipped =
        setOf(
            // Hands out the class loader or internals that get around the guard.
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.lang.Runtime",
            "java.lang.System",
            "java.lang.ProcessBuilder",
            // Path is an interface the replacement implements in full; its static of() is checked below.
            "java.nio.file.Path",
        )

    // Final classes whose objects stay real: only their static members are replaced.
    private val staticsOnly = setOf("android.database.sqlite.SQLiteDatabase")

    private val hiddenFields = setOf("CONFLICT_VALUES")

    // Public in the class at run time but @hide in the SDK: apps cannot call them anyway.
    private val hidden =
        setOf(
            "buildExternalStorageAndroidDataDirs", "buildExternalStorageAppCacheDirs", "buildExternalStorageAppDataDirs",
            "buildExternalStorageAppFilesDirs", "buildExternalStorageAppMediaDirs", "buildPath", "buildPaths",
            "getDataCePackageDirectoryForUser", "getDataDePackageDirectoryForUser", "getDataSystemDeviceProtectedDirectory",
            "getDataSystemDirectory", "getInternalMediaDirectories", "getLegacyExternalStorageDirectory", "getOdmDirectory",
            "getOemDirectory", "getProductDirectory", "getProductServicesDirectory", "getSystemExtDirectory", "getVendorDirectory",
            "initForCurrentUser", "maybeTranslateEmulatedPathToInternal",
        )

    private fun signature(
        name: String,
        types: Array<Class<*>>,
    ) = name + types.joinToString(",", "(", ")") { it.name }

    @Test
    fun replacementsOfferEverythingTheRealClassDoes() {
        val missing = ArrayList<String>()
        for ((name, replacement) in GuardPolicy.substitutes) {
            if (name in skipped) continue
            val real = Class.forName(name)
            val onlyStatic = name in staticsOnly
            for (constructor in real.constructors) {
                if (onlyStatic) break
                runCatching { replacement.getConstructor(*constructor.parameterTypes) }.onFailure { missing += "$name ${signature("<init>", constructor.parameterTypes)}" }
            }
            for (method in real.methods) {
                if (method.declaringClass == Any::class.java || name == "android.os.Environment" && method.name in hidden) continue
                if (onlyStatic && !Modifier.isStatic(method.modifiers)) continue
                val found = runCatching { replacement.getMethod(method.name, *method.parameterTypes) }.getOrNull()
                if (found == null || Modifier.isStatic(found.modifiers) != Modifier.isStatic(method.modifiers)) missing += "$name ${signature(method.name, method.parameterTypes)}"
            }
            for (field in real.fields) {
                if (onlyStatic && !Modifier.isStatic(field.modifiers) || field.name in hiddenFields) continue
                runCatching { replacement.getField(field.name) }.onFailure { missing += "$name field ${field.name}" }
            }
        }
        runCatching { GuardPath::class.java.getMethod("of", String::class.java, Array<String>::class.java) }.onFailure { missing += "Path.of(String,String[])" }
        runCatching { GuardPath::class.java.getMethod("of", java.net.URI::class.java) }.onFailure { missing += "Path.of(URI)" }
        assertEquals(emptyList(), missing, missing.joinToString("\n"))
    }
}
