/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard;

import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * The static methods of {@link GuardFile}. They hide those of {@code java.io.File}, which Kotlin cannot do, so a
 * script that calls {@code File.createTempFile} on the guard's {@code File} makes the file through the host.
 */
public abstract class GuardFileBase extends File {
    GuardFileBase(String path) {
        super(path);
    }

    GuardFileBase(String parent, String child) {
        super(parent, child);
    }

    GuardFileBase(File parent, String child) {
        super(parent, child);
    }

    GuardFileBase(URI uri) {
        super(uri);
    }

    /** One root, {@code /}. */
    public static File[] listRoots() {
        return new File[] {new GuardFile("/")};
    }

    /** Like {@code File.createTempFile}, in the host's temporary folder. */
    public static File createTempFile(String prefix, String suffix) throws IOException {
        return TempFiles.INSTANCE.create(prefix, suffix, null);
    }

    /** Like {@code File.createTempFile}; in the host's temporary folder when {@code directory} is null. */
    public static File createTempFile(String prefix, String suffix, File directory) throws IOException {
        return TempFiles.INSTANCE.create(prefix, suffix, directory);
    }
}
