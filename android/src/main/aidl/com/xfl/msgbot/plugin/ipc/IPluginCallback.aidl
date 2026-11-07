// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xfl.msgbot.plugin.ipc;

import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;

// Plugin -> host, per session. Frames are oneway so the plugin never blocks.
// Transaction numbers are fixed: add methods with new numbers and never reuse one. 100 was apiFingerprint.
interface IPluginCallback {
    oneway void onFrame(in byte[] frame) = 0;

    oneway void onShared(long transferId, in SharedMemory region) = 1;

    // Opens a file of the project this session belongs to. mode is a HostFileMode. Fails with an
    // IllegalStateException whose message starts with the POSIX error name, or answers null when the
    // host serves no files. The host closes its own copy; the caller owns the one it receives.
    ParcelFileDescriptor openFile(String path, int mode) = 2;

    // BinderContract.VERSION of the host. A peer without this method answers 0.
    int binderVersion() = 101;
}
