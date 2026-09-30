/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

/** AIDL implementations return [FINGERPRINT] from `apiFingerprint`. */
object BinderContract {
    const val FINGERPRINT = "98220de7ea33953000dd9f910fe26ea5f2b9895674b09ffb70aaeb34053675d6"
    internal const val MISMATCH =
        "PluginApi Binder interfaces do not match. Rebuild the app and plugin with the same PluginApi Android version."

    internal fun verify(remote: () -> String?) {
        val actual =
            try {
                remote()
            } catch (e: android.os.RemoteException) {
                throw IllegalStateException(MISMATCH, e)
            }
        check(actual == FINGERPRINT) { MISMATCH }
    }
}
