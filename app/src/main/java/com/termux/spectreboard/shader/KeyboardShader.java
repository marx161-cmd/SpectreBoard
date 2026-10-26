// SPDX-License-Identifier: GPL-3.0-only

package com.termux.spectreboard.shader;

import android.os.Build;
import android.view.View;

import com.termux.spectreboard.keyboard.Key;

/**
 * API-neutral entry point for the keyboard shader (scope.md 2026-10-23).
 *
 * Everything that touches {@code RuntimeShader}/{@code RenderEffect} lives in
 * {@link KeyboardShaderFx}, which is only loaded when the device is API 33+. On anything older
 * every call is a no-op and the keyboard draws exactly stock.
 */
public final class KeyboardShader {
    public static final String TAG = "KeyboardShader";

    /** Held as Object so this class never resolves the API-33 implementation on old devices. */
    private static Object impl;

    private KeyboardShader() {
    }

    /** Attach to the view whose rendered content should be warped (the keyboard wrapper). */
    public static void attach(View fxView) {
        if (fxView == null || Build.VERSION.SDK_INT < 33) return;
        try {
            detach();
            Object o = new KeyboardShaderFx(fxView);
            ((KeyboardShaderFx) o).attach();
            impl = o;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "attach failed", t);
            impl = null;
        }
    }

    public static void detach() {
        Object o = impl;
        impl = null;
        if (o == null) return;
        try {
            ((KeyboardShaderFx) o).detach();
        } catch (Throwable ignored) {
        }
    }

    /** A key was pressed; keyboardView is the view the key coordinates belong to. */
    public static void onKeyHit(View keyboardView, Key key) {
        Object o = impl;
        if (o == null || Build.VERSION.SDK_INT < 33) return;
        try {
            ((KeyboardShaderFx) o).onKeyHit(keyboardView, key);
        } catch (Throwable ignored) {
        }
    }
}
