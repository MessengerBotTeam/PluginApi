// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import android.os.SharedMemory;

// Plugin -> host frame delivery. oneway: async, never blocks the caller's binder thread.
interface IPluginCallback {
    oneway void onFrame(in byte[] frame);

    // Mirror of IPluginService.sendBlob for the plugin -> host direction.
    oneway void onBlob(long id, in SharedMemory shm);
}
