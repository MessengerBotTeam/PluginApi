// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import com.xfl.msgbot.plugin.ipc.IPluginCallback;
import android.os.SharedMemory;

// Engine plugin service. Frames are the PluginProtocol byte frames.
interface IPluginService {
    // Register the host callback; returns the plugin's protocol version.
    int connect(IPluginCallback callback);

    // Host -> plugin frame. oneway: async.
    oneway void send(in byte[] frame);

    // Host -> plugin large payload, handed over before the frame that refers to it by id.
    // A frame is a byte array, so a big one would have to be inlined and risk
    // TransactionTooLarge. oneway calls on one interface keep their order, so this lands first.
    oneway void sendBlob(long id, in SharedMemory shm);
}
