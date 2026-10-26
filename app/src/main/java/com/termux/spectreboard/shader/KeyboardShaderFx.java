// SPDX-License-Identifier: GPL-3.0-only

package com.termux.spectreboard.shader;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.RequiresApi;

import com.termux.spectreboard.keyboard.Key;

/**
 * The API-33+ implementation (see scope.md 2026-10-23).
 *
 * A post-process over the real keyboard: the rendered keys and letters are the input
 * ({@code uniform shader iContent}), so their shapes can be torn and displaced — something a
 * per-key paint shader cannot do. Per-key press data arrives as {@code iHit[]} uniforms, so the
 * damage is anchored to the key that was just hit.
 *
 * Inputs: JDSP's bands.bin (read directly; SpectreBoard is UID 1000) for the continuous floor
 * and the no-map fallback, and qs-shader-live's cached beat clock for phase-locked hits.
 *
 * The loop only runs while the keyboard is shown AND (music is live OR a key was hit within
 * the last 1.2 s). Otherwise one static frame is left on screen and the loop stops: this is
 * the most-used surface on the phone, so it must not burn a full keyboard raster at 120 Hz
 * for no reason.
 */
@RequiresApi(33)
final class KeyboardShaderFx implements Choreographer.FrameCallback {
    private static final long HIT_TAIL_MS = 1200;
    private static final long MIN_FRAME_NS = 15_000_000L;   // ~60 fps cap
    private static final int MAX_HITS = 4;

    private final View fx;
    private final ShaderFile shader;
    private final BandsFeed bands = new BandsFeed();
    private final BeatSource beat;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean attached;
    private boolean running;
    private long lastRenderNs;
    private long lastHitMs;
    private long lastFrameNs;

    private final float[] bandFlat = new float[BandsFeed.N];
    private final float[] bandDbFlat = new float[BandsFeed.N];
    private final float[] hits = new float[MAX_HITS * 4];
    private final float[] hitAge = new float[MAX_HITS];
    private final long[] hitAt = new long[MAX_HITS];
    private int hitCount;

    // local conditioning (same names as the desktop feed)
    private float onsetSmooth, onsetAccum, bassAccum;

    private final int[] fxLoc = new int[2];
    private final int[] kbLoc = new int[2];
    private final java.io.File statusFile;
    private long lastStatusMs;

    KeyboardShaderFx(View fx) {
        this.fx = fx;
        this.shader = new ShaderFile(ShaderPaths.dir(fx.getContext()),
                ShaderPaths.MAIN_FILE, "keyboard_error.txt");
        this.beat = BeatSource.get(fx.getContext());
        this.statusFile = ShaderPaths.status(fx.getContext(), "keyboard_status.txt");
        for (int i = 0; i < MAX_HITS; i++) hitAge[i] = 9f;
    }

    void attach() {
        if (attached) return;
        attached = true;
        handler.post(visibilityPoll);
    }

    void detach() {
        attached = false;
        handler.removeCallbacks(visibilityPoll);
        stop();
        try {
            fx.setRenderEffect(null);
        } catch (Throwable ignored) {
        }
    }

    private final Runnable visibilityPoll = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            boolean shown = fx.isAttachedToWindow() && fx.isShown();
            if (shown && !running) {
                // Music alone should wake an open keyboard, not only a key hit.
                bands.poll(System.nanoTime());
                if (bands.live) start();
            } else if (!shown && running) {
                stop();
            }
            handler.postDelayed(this, 500);
        }
    };

    private void start() {
        if (running || !attached) return;
        if (Build.VERSION.SDK_INT < 33) return;
        running = true;
        lastFrameNs = 0;
        lastRenderNs = 0;
        beat.setActive("keyboard", true);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void stop() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        beat.setActive("keyboard", false);
        // Leave the last rendered effect on the view: it stays as a static look while the
        // keyboard is not animating, and costs nothing until the next redraw.
    }

    /** Called from the keyboard's press path; rect is in this view's pixel space. */
    void onKeyHit(View keyboardView, Key key) {
        if (key == null || key.isSpacer()) return;
        try {
            keyboardView.getLocationInWindow(kbLoc);
            fx.getLocationInWindow(fxLoc);
            float x = kbLoc[0] - fxLoc[0] + key.getDrawX() + keyboardView.getPaddingLeft();
            float y = kbLoc[1] - fxLoc[1] + key.getY() + keyboardView.getPaddingTop();
            float w = key.getDrawWidth();
            float h = key.getHeight();
            System.arraycopy(hits, 0, hits, 4, (MAX_HITS - 1) * 4);
            System.arraycopy(hitAt, 0, hitAt, 1, MAX_HITS - 1);
            hits[0] = x;
            hits[1] = y;
            hits[2] = w;
            hits[3] = h;
            hitAt[0] = SystemClock.elapsedRealtime();
            hitCount = Math.min(MAX_HITS, hitCount + 1);
            lastHitMs = hitAt[0];
            if (attached && !running && fx.isShown()) start();
        } catch (Throwable t) {
            Log.w(KeyboardShader.TAG, "key hit failed", t);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (!fx.isAttachedToWindow() || !fx.isShown()) {
            stop();
            return;
        }
        long nowNs = System.nanoTime();
        if (lastRenderNs != 0 && nowNs - lastRenderNs < MIN_FRAME_NS) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        lastRenderNs = nowNs;

        RuntimeShader s = shader.get();
        if (s == null || !shader.has("iContent")) {
            // missing / broken file: stock keyboard, keep polling so a fixed file appears
            try {
                fx.setRenderEffect(null);
            } catch (Throwable ignored) {
            }
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        boolean live = bands.poll(nowNs);
        if (live || bands.live) applyBands(nowNs);

        BeatSource.Frame f = beat.frame();

        shader.set("iResolution", fx.getWidth(), fx.getHeight());
        shader.set("iTime", ((SystemClock.elapsedRealtime() - ShaderFile.T0) % 3_600_000L) / 1000f);
        shader.set("iLive", bands.live ? 1f : 0f);
        shader.set("iBeatOn", f.on ? 1f : 0f);
        shader.set("iBeatPhase", (float) f.phase);
        shader.set("iBeatCount", (float) (f.count % 1024.0));
        shader.set("iBpm", (float) f.bpm);
        shader.set("iBarPhase", (float) f.barPhase);
        shader.set("iBeatsPerBar", (float) f.beatsPerBar);

        long nowMs = SystemClock.elapsedRealtime();
        for (int i = 0; i < MAX_HITS; i++) {
            hitAge[i] = hitAt[i] == 0 ? 9f : (nowMs - hitAt[i]) / 1000f;
        }
        shader.setArray("iBands", bandFlat);
        shader.setArray("iBandsDb", bandDbFlat);
        shader.setArray("iHit", hits);
        shader.setArray("iHitAge", hitAge);
        shader.set("iHitN", hitCount);

        if (nowMs - lastStatusMs > 1000) {
            lastStatusMs = nowMs;
            writeStatus(f);
        }

        try {
            fx.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "iContent"));
        } catch (Throwable t) {
            Log.w(KeyboardShader.TAG, "render effect failed", t);
            stop();
            return;
        }

        boolean active = bands.live || (lastHitMs != 0 && nowMs - lastHitMs < HIT_TAIL_MS);
        if (active) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            // leave the last frame on screen, stop burning full keyboard rasters
            stop();
        }
    }

    private void writeStatus(BeatSource.Frame f) {
        String s = String.format(java.util.Locale.US,
                "live=%b bass=%.2f onset=%.2f beatOn=%b bpm=%.1f phase=%.2f hits=%d%n",
                bands.live, bands.features[2], bands.features[10], f.on, f.bpm, f.phase, hitCount);
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(statusFile)) {
            o.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private void applyBands(long nowNs) {        System.arraycopy(bands.raw, 0, bandFlat, 0, BandsFeed.N);
        System.arraycopy(bands.cooked, 0, bandDbFlat, 0, BandsFeed.N);

        float dt = lastFrameNs == 0 ? 0f : Math.min(0.1f, (nowNs - lastFrameNs) / 1e9f);
        lastFrameNs = nowNs;
        float[] ft = bands.features;
        shader.set("u_rms", ft[0]);
        shader.set("u_sub", ft[1]);
        shader.set("u_bass", ft[2]);
        shader.set("u_lowmid", ft[3]);
        shader.set("u_mid", ft[4]);
        shader.set("u_highmid", ft[5]);
        shader.set("u_presence", ft[6]);
        shader.set("u_brilliance", ft[7]);
        shader.set("u_centroid", ft[8]);
        shader.set("u_flux", ft[9]);
        shader.set("u_onset", ft[10]);

        onsetSmooth += (ft[10] - onsetSmooth) * (1f - (float) Math.exp(-dt / 0.15f));
        onsetAccum += ft[10] * dt;
        bassAccum += ft[2] * ft[2] * ft[2] * dt;
        shader.set("u_onset_smooth", onsetSmooth);
        shader.set("u_onset_accum", onsetAccum);
        shader.set("u_bass_accum", bassAccum);
    }
}
