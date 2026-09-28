// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import android.os.SharedMemory;

// Plugin -> host, for one session. oneway: never blocks the plugin.
interface IPluginCallback {
    oneway void onFrame(in byte[] frame);

    oneway void onShared(long transferId, in SharedMemory region);
}
