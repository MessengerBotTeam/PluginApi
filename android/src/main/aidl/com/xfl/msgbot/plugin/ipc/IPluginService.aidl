// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import com.xfl.msgbot.plugin.ipc.IPluginCallback;
import android.os.SharedMemory;

// Host -> plugin. One session per project and role.
// Transaction numbers are fixed: add methods with new numbers and never reuse one. 100 was apiFingerprint.
interface IPluginService {
    // role is a PluginRole; component is the manifest id. Protocol is negotiated by the first request, "hello".
    long open(String role, String component, IPluginCallback callback) = 0;

    oneway void send(long session, in byte[] frame) = 1;

    // Sent before the frame that refers to it; oneway calls on one binder are ordered.
    oneway void sendShared(long session, long transferId, in SharedMemory region) = 2;

    void close(long session) = 3;

    // BinderContract.VERSION of the plugin. A peer without this method answers 0.
    int binderVersion() = 101;
}
