/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

/**
 * The Binder interfaces grow by adding methods under new transaction numbers, so peers only need to
 * agree on the oldest version both can still serve. AIDL implementations return [VERSION] from
 * `binderVersion`.
 */
object BinderContract {
    /** Raised whenever a method is added to IPluginService or IPluginCallback. */
    const val VERSION = 1

    /** The oldest peer this build can talk to. Raise only when a method this build calls is missing from older peers. */
    const val MIN_VERSION = 1

    internal fun mismatch(version: Int) =
        "PluginApi Binder interfaces are incompatible (peer $version, need at least $MIN_VERSION). " +
            "Rebuild the app or plugin with a current PluginApi."

    internal fun verify(remote: () -> Int) {
        val version =
            try {
                remote()
            } catch (e: android.os.RemoteException) {
                throw IllegalStateException(mismatch(0), e)
            }
        check(version >= MIN_VERSION) { mismatch(version) }
    }
}
