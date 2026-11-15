/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import android.os.Environment
import java.io.File

/**
 * `android.os.Environment` for scripts. Old bot scripts find their folders through it; the folders it names are the
 * same, but as [GuardFile]s, so what the scripts do there goes through the host under the project's file rules.
 */
class GuardEnvironment {
    companion object {
        @JvmField val DIRECTORY_MUSIC: String = Environment.DIRECTORY_MUSIC

        @JvmField val DIRECTORY_PODCASTS: String = Environment.DIRECTORY_PODCASTS

        @JvmField val DIRECTORY_RINGTONES: String = Environment.DIRECTORY_RINGTONES

        @JvmField val DIRECTORY_ALARMS: String = Environment.DIRECTORY_ALARMS

        @JvmField val DIRECTORY_NOTIFICATIONS: String = Environment.DIRECTORY_NOTIFICATIONS

        @JvmField val DIRECTORY_PICTURES: String = Environment.DIRECTORY_PICTURES

        @JvmField val DIRECTORY_MOVIES: String = Environment.DIRECTORY_MOVIES

        @JvmField val DIRECTORY_DOWNLOADS: String = Environment.DIRECTORY_DOWNLOADS

        @JvmField val DIRECTORY_DCIM: String = Environment.DIRECTORY_DCIM

        @JvmField val DIRECTORY_DOCUMENTS: String = Environment.DIRECTORY_DOCUMENTS

        // Literal: the real fields are newer than the oldest Android this runs on, and not constants.
        @JvmField val DIRECTORY_AUDIOBOOKS: String = "Audiobooks"

        @JvmField val DIRECTORY_RECORDINGS: String = "Recordings"

        @JvmField val DIRECTORY_SCREENSHOTS: String = "Screenshots"

        @JvmField val MEDIA_UNKNOWN: String = Environment.MEDIA_UNKNOWN

        @JvmField val MEDIA_CHECKING: String = Environment.MEDIA_CHECKING

        @JvmField val MEDIA_NOFS: String = Environment.MEDIA_NOFS

        @JvmField val MEDIA_SHARED: String = Environment.MEDIA_SHARED

        @JvmField val MEDIA_BAD_REMOVAL: String = Environment.MEDIA_BAD_REMOVAL

        @JvmField val MEDIA_UNMOUNTABLE: String = Environment.MEDIA_UNMOUNTABLE

        @JvmField val MEDIA_EJECTING: String = Environment.MEDIA_EJECTING

        @JvmField val MEDIA_MOUNTED: String = Environment.MEDIA_MOUNTED

        @JvmField val MEDIA_MOUNTED_READ_ONLY: String = Environment.MEDIA_MOUNTED_READ_ONLY

        @JvmField val MEDIA_REMOVED: String = Environment.MEDIA_REMOVED

        @JvmField val MEDIA_UNMOUNTED: String = Environment.MEDIA_UNMOUNTED

        private fun guard(file: File): File = GuardFile(file.path)

        @JvmStatic fun getExternalStorageDirectory(): File = guard(@Suppress("DEPRECATION") Environment.getExternalStorageDirectory())

        @JvmStatic fun getExternalStoragePublicDirectory(type: String): File = guard(@Suppress("DEPRECATION") Environment.getExternalStoragePublicDirectory(type))

        @JvmStatic fun getRootDirectory(): File = guard(Environment.getRootDirectory())

        @JvmStatic fun getDataDirectory(): File = guard(Environment.getDataDirectory())

        @JvmStatic fun getDownloadCacheDirectory(): File = guard(Environment.getDownloadCacheDirectory())

        @JvmStatic fun getStorageDirectory(): File = guard(Environment.getStorageDirectory())

        @JvmStatic fun getExternalStorageState(): String = Environment.MEDIA_MOUNTED

        @JvmStatic fun getExternalStorageState(path: File): String = Environment.MEDIA_MOUNTED

        @JvmStatic fun getStorageState(path: File): String = Environment.MEDIA_MOUNTED

        @JvmStatic fun isExternalStorageEmulated(): Boolean = Environment.isExternalStorageEmulated()

        @JvmStatic fun isExternalStorageEmulated(path: File): Boolean = Environment.isExternalStorageEmulated(path)

        @JvmStatic fun isExternalStorageRemovable(path: File): Boolean = Environment.isExternalStorageRemovable(path)

        @JvmStatic fun isExternalStorageManager(path: File): Boolean = true

        @JvmStatic fun isExternalStorageLegacy(path: File): Boolean = false

        @JvmStatic fun isExternalStorageRemovable(): Boolean = Environment.isExternalStorageRemovable()

        /** True: what a script may do with files is decided by the host, which holds that permission. */
        @JvmStatic fun isExternalStorageManager(): Boolean = true

        @JvmStatic fun isExternalStorageLegacy(): Boolean = false
    }
}
