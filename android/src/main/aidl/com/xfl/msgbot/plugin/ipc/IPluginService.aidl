// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import com.xfl.msgbot.plugin.ipc.IPluginCallback;
import android.os.SharedMemory;

// Host -> plugin. One session per project and role.
interface IPluginService {
    // role is a PluginRole; component is the manifest id. Protocol is negotiated by the first request, "hello".
    long open(String role, String component, IPluginCallback callback);

    oneway void send(long session, in byte[] frame);

    // Sent before the frame that refers to it; oneway calls on one binder are ordered.
    oneway void sendShared(long session, long transferId, in SharedMemory region);

    void close(long session);
}
