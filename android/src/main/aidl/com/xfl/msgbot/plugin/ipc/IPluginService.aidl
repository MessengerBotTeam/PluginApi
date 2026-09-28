// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import com.xfl.msgbot.plugin.ipc.IPluginCallback;
import android.os.SharedMemory;

// Host -> plugin. One service hosts any number of independent sessions, one per project and role.
interface IPluginService {
    int protocolVersion();

    // role is PluginRole.ENGINE or PluginRole.PROVIDER; component is the manifest id.
    long open(String role, String component, IPluginCallback callback);

    oneway void send(long session, in byte[] frame);

    // Sent before the frame that refers to it; oneway calls on one binder keep their order.
    oneway void sendShared(long session, long transferId, in SharedMemory region);

    void close(long session);
}
