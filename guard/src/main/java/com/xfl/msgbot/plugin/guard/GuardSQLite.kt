/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * `android.database.sqlite.SQLiteDatabase`, which is final: its static ways to open, create and delete a database.
 * The databases they return are the real class.
 *
 * SQLite opens its file and the journal next to it by path, which no descriptor from the host can stand in for. So a
 * database on storage opens only when the engine app has All files access ([GuardSession.hasStorageAccess]), and then
 * only where the project's file rules allow it; without that access it fails with a message that points to the host
 * `db` module. Databases in memory always work.
 */
class GuardSQLiteDatabase private constructor() {
    companion object {
        const val CONFLICT_ROLLBACK = SQLiteDatabase.CONFLICT_ROLLBACK
        const val CONFLICT_ABORT = SQLiteDatabase.CONFLICT_ABORT
        const val CONFLICT_FAIL = SQLiteDatabase.CONFLICT_FAIL
        const val CONFLICT_IGNORE = SQLiteDatabase.CONFLICT_IGNORE
        const val CONFLICT_REPLACE = SQLiteDatabase.CONFLICT_REPLACE
        const val CONFLICT_NONE = SQLiteDatabase.CONFLICT_NONE
        const val SQLITE_MAX_LIKE_PATTERN_LENGTH = SQLiteDatabase.SQLITE_MAX_LIKE_PATTERN_LENGTH
        const val OPEN_READWRITE = SQLiteDatabase.OPEN_READWRITE
        const val OPEN_READONLY = SQLiteDatabase.OPEN_READONLY
        const val NO_LOCALIZED_COLLATORS = SQLiteDatabase.NO_LOCALIZED_COLLATORS
        const val CREATE_IF_NECESSARY = SQLiteDatabase.CREATE_IF_NECESSARY
        const val ENABLE_WRITE_AHEAD_LOGGING = SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        const val MAX_SQL_CACHE_SIZE = SQLiteDatabase.MAX_SQL_CACHE_SIZE
        const val JOURNAL_MODE_WAL = SQLiteDatabase.JOURNAL_MODE_WAL
        const val JOURNAL_MODE_PERSIST = SQLiteDatabase.JOURNAL_MODE_PERSIST
        const val JOURNAL_MODE_TRUNCATE = SQLiteDatabase.JOURNAL_MODE_TRUNCATE
        const val JOURNAL_MODE_MEMORY = SQLiteDatabase.JOURNAL_MODE_MEMORY
        const val JOURNAL_MODE_DELETE = SQLiteDatabase.JOURNAL_MODE_DELETE
        const val JOURNAL_MODE_OFF = SQLiteDatabase.JOURNAL_MODE_OFF
        const val SYNC_MODE_EXTRA = SQLiteDatabase.SYNC_MODE_EXTRA
        const val SYNC_MODE_FULL = SQLiteDatabase.SYNC_MODE_FULL
        const val SYNC_MODE_NORMAL = SQLiteDatabase.SYNC_MODE_NORMAL
        const val SYNC_MODE_OFF = SQLiteDatabase.SYNC_MODE_OFF

        private const val MEMORY = ":memory:"

        @JvmStatic
        fun openDatabase(
            path: String,
            factory: SQLiteDatabase.CursorFactory?,
            flags: Int,
        ): SQLiteDatabase = SQLiteDatabase.openDatabase(checked(path, flags), factory, flags)

        @JvmStatic
        fun openDatabase(
            path: String,
            factory: SQLiteDatabase.CursorFactory?,
            flags: Int,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = SQLiteDatabase.openDatabase(checked(path, flags), factory, flags, errorHandler)

        @JvmStatic
        fun openDatabase(
            path: File,
            openParams: SQLiteDatabase.OpenParams,
        ): SQLiteDatabase = SQLiteDatabase.openDatabase(File(checked(path.path, openParams.openFlags)), openParams)

        @JvmStatic
        fun openOrCreateDatabase(
            file: File,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = openOrCreateDatabase(file.path, factory)

        @JvmStatic
        fun openOrCreateDatabase(
            path: String,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(checked(path, OPEN_READWRITE or CREATE_IF_NECESSARY), factory)

        @JvmStatic
        fun openOrCreateDatabase(
            path: String,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(checked(path, OPEN_READWRITE or CREATE_IF_NECESSARY), factory, errorHandler)

        @JvmStatic
        fun deleteDatabase(file: File): Boolean = SQLiteDatabase.deleteDatabase(File(checked(file.path, OPEN_READWRITE)))

        @JvmStatic
        fun create(factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.create(factory)

        @JvmStatic
        fun createInMemory(openParams: SQLiteDatabase.OpenParams): SQLiteDatabase = SQLiteDatabase.createInMemory(openParams)

        @JvmStatic
        fun releaseMemory(): Int = SQLiteDatabase.releaseMemory()

        @JvmStatic
        fun findEditTable(tables: String): String = SQLiteDatabase.findEditTable(tables)

        // The absolute path of a database the project may use as [flags] ask, or a refusal like SQLite's own.
        private fun checked(
            path: String,
            flags: Int,
        ): String {
            if (path == MEMORY) return path
            val session = GuardContext.current()
            val host = session.host
            val absolute = GuardFile.normalize(if (path.startsWith("/")) path else host.root.trimEnd('/') + "/" + path)
            if (!session.hasStorageAccess) {
                session.warnOnce(
                    "sqlite",
                    "Java: SQLite needs All files access for this plugin app. Use the db module, or allow the access in the plugin's settings.",
                )
                throw SQLiteCantOpenDatabaseException(
                    "Cannot open $path: SQLite needs All files access for this plugin app. Use the db module instead.",
                )
            }
            val write = flags and OPEN_READONLY == 0
            val existing =
                try {
                    host.stat(absolute)
                } catch (e: HostIOException) {
                    refuse(path, absolute, e)
                }
            // A new database needs a folder the project may write to; a missing one is left for SQLite to report.
            val target = if (existing != null || flags and CREATE_IF_NECESSARY == 0) absolute else absolute.substringBeforeLast('/').ifEmpty { "/" }
            if ((existing != null || target != absolute) && !host.access(target, if (write) 6 else 4)) {
                refuse(path, absolute, HostIOException("EACCES", absolute, "permission denied"))
            }
            return absolute
        }

        private fun refuse(
            path: String,
            absolute: String,
            e: HostIOException,
        ): Nothing {
            if (e.isDenied) GuardContext.reportPath(absolute)
            throw SQLiteCantOpenDatabaseException("Cannot open $path: ${e.errno} (${e.detail})")
        }
    }
}
