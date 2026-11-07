/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.ipc

import android.os.ParcelFileDescriptor

/**
 * The files the host lets one plugin session open, normally the folder of the project the session runs.
 * Set it on [HostSessionTransport.files] before the plugin can ask.
 */
fun interface HostFiles {
    /**
     * Opens [path] as [com.xfl.msgbot.plugin.api.engine.HostFileMode] [mode] and returns a descriptor the
     * plugin gets its own copy of; the host closes the one returned here.
     *
     * Failures are reported with [fail], whose message starts with the POSIX error name.
     */
    fun open(
        path: String,
        mode: Int,
    ): ParcelFileDescriptor

    companion object {
        /** Throws the exception that carries a POSIX error [name] such as `ENOENT` to the plugin; Binder passes an [IllegalStateException] message on unchanged. */
        fun fail(
            name: String,
            detail: String,
        ): Nothing = throw IllegalStateException("$name: $detail")
    }
}
