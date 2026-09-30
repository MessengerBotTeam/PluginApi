// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import com.xfl.msgbot.plugin.ipc.IPluginCallback;
import android.os.SharedMemory;

interface IPluginService {
    long open(String role, String component, IPluginCallback callback) = 0;
    oneway void send(long session, in byte[] frame) = 1;
    oneway void sendShared(long session, long transferId, in SharedMemory region) = 2;
    void close(long session) = 3;
    String apiFingerprint() = 100;
}
