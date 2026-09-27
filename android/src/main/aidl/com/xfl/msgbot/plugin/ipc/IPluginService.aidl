// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import com.xfl.msgbot.plugin.ipc.IPluginCallback;
import android.os.SharedMemory;

// One service can host independent project sessions.
interface IPluginService {
    int protocolVersion();
    long open(IPluginCallback callback);

    oneway void send(long sessionId, in byte[] frame);

    oneway void sendBlob(long sessionId, long blobId, in SharedMemory shm);
    void close(long sessionId);
}
