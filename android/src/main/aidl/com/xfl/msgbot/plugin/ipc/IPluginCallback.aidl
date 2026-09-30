// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import android.os.SharedMemory;

// Plugin -> host, per session. oneway so the plugin never blocks.
// Transaction numbers are fixed: add methods with new numbers and never reuse one. 100 was apiFingerprint.
interface IPluginCallback {
    oneway void onFrame(in byte[] frame) = 0;

    oneway void onShared(long transferId, in SharedMemory region) = 1;

    // BinderContract.VERSION of the host. A peer without this method answers 0.
    int binderVersion() = 101;
}
