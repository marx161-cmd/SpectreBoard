// SPDX-License-Identifier: GPL-3.0-only

package com.termux.spectreboard.shader;

import android.content.Context;

import java.io.File;

/**
 * Where the keyboard shader files live.
 *
 * Primary: Termux home, which SpectreBoard (UID 1000, same shared user as the whole
 * com.termux family) can read and write — so the shader is edited from a terminal with
 * the same loop as ShaderEditor.
 * Fallback: the app's own files dir, for a device where Termux home is not reachable.
 */
public final class ShaderPaths {
    public static final File TERMUX_DIR =
            new File("/data/data/com.termux/files/home/shaders/keyboard");
    /** Beat maps synced by beatmaps-sync-pixel for qs-shader-live; read-only here. */
    public static final File QS_DIR = new File("/data/local/tmp/qs_shader");

    public static final String MAIN_FILE = "keyboard.agsl";

    private ShaderPaths() {
    }

    public static File dir(Context ctx) {
        if (TERMUX_DIR.isDirectory() && TERMUX_DIR.canRead()) {
            return TERMUX_DIR;
        }
        File f = new File(ctx.getApplicationContext().getFilesDir(), "keyboard");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    public static File status(Context ctx, String name) {
        return new File(dir(ctx), name);
    }
}
