// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import android.os.SharedMemory;

interface IPluginCallback {
    oneway void onFrame(in byte[] frame) = 0;
    oneway void onShared(long transferId, in SharedMemory region) = 1;
    String apiFingerprint() = 100;
}
