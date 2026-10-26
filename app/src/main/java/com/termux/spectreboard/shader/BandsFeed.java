// SPDX-License-Identifier: GPL-3.0-only

package com.termux.spectreboard.shader;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Port of ShaderEditor's {@code opengl/JdspBandsFeed.java} (2026-10-22 v108-v110).
 *
 * Reader for JDSP's bands.bin, the audio HAL's 108-band meter. Memory-mapped and
 * polled by sequence number, so every 60 Hz frame the engine publishes is seen
 * exactly once.
 *
 * File layout (little-endian; v3 since 2026-10-22):
 *   [0, 432)     latest RAW frame: 108 floats, linear RMS per semitone band
 *   [432, 464)   header: magic "JDB2", version, seq, ringLen, bands, frameHz,
 *                sampleRate, rms
 *   [464, R)     ring of ringLen raw frames; frame k in slot k % ringLen
 *   [R, R+432)   v3: COOKED latest frame (norm_db 0..1 over -60..0 dBFS, 60/70 ms)
 *   [R+432, +44) v3: 11 features: rms, sub, bass, lowmid, mid, highmid,
 *                presence, brilliance, centroid, flux, onset (0..1)
 *
 * Stale detection: when the sequence stops advancing for 150 ms (paused player,
 * effect detached, JDSP off) every array is zeroed, so a paused song is silence,
 * never a frozen spectrum.
 *
 * Alignment (v3 engines): every band filter rings for its own group delay
 * (283 ms at A0 down to ~2 ms at A8), so each band is read DELAY_S[b] later from
 * a local history; all 108 rows then line up with each other. A v2 engine's
 * single-biquad bank is read unshifted.
 *
 * This copy only changes visibility (public API) and nothing else, so the maths
 * stays identical to the wallpaper's. Keep it in sync with ShaderEditor.
 */
public final class BandsFeed {
    public static final int N = 108;
    public static final int FEATURES = 11;
    public static final int HIST = 32;
    public static final String[] FEATURE_NAMES = {"rms", "sub", "bass", "lowmid", "mid",
            "highmid", "presence", "brilliance", "centroid", "flux", "onset"};

    private static final File FILE = new File("/data/data/com.termux.jdsp/files/bands.bin");
    private static final int HDR_OFF = N * 4;
    private static final int VER_OFF = HDR_OFF + 4;
    private static final int SEQ_OFF = HDR_OFF + 8;
    private static final int RING_LEN_OFF = HDR_OFF + 12;
    private static final int HZ_OFF = HDR_OFF + 20;
    private static final int RMS_OFF = HDR_OFF + 28;
    private static final int RING_OFF = HDR_OFF + 32;
    private static final int MAGIC = 0x3242444A;
    private static final long STALE_NS = 150_000_000L;
    private static final long REMAP_INTERVAL_NS = 1_000_000_000L;
    private static final double DB_FLOOR = -60.0;

    /** Group delay per band of the v3 Butterworth bank, seconds (A0 first). */
    public static final float[] DELAY_S = {
            0.2834f, 0.2675f, 0.2524f, 0.2383f, 0.2249f, 0.2123f, 0.2004f, 0.1891f, 0.1785f, 0.1685f, 0.1590f, 0.1501f,
            0.1417f, 0.1337f, 0.1262f, 0.1191f, 0.1124f, 0.1061f, 0.1002f, 0.0946f, 0.0893f, 0.0842f, 0.0795f, 0.0751f,
            0.0708f, 0.0669f, 0.0631f, 0.0596f, 0.0562f, 0.0531f, 0.0501f, 0.0473f, 0.0446f, 0.0421f, 0.0398f, 0.0375f,
            0.0501f, 0.0473f, 0.0446f, 0.0421f, 0.0398f, 0.0375f, 0.0354f, 0.0334f, 0.0316f, 0.0298f, 0.0281f, 0.0265f,
            0.0250f, 0.0236f, 0.0223f, 0.0211f, 0.0199f, 0.0188f, 0.0177f, 0.0167f, 0.0158f, 0.0149f, 0.0141f, 0.0133f,
            0.0164f, 0.0154f, 0.0146f, 0.0138f, 0.0130f, 0.0123f, 0.0116f, 0.0109f, 0.0103f, 0.0097f, 0.0092f, 0.0087f,
            0.0082f, 0.0077f, 0.0073f, 0.0069f, 0.0065f, 0.0061f, 0.0058f, 0.0055f, 0.0052f, 0.0049f, 0.0046f, 0.0043f,
            0.0041f, 0.0039f, 0.0036f, 0.0034f, 0.0032f, 0.0031f, 0.0029f, 0.0027f, 0.0026f, 0.0024f, 0.0023f, 0.0022f,
            0.0020f, 0.0019f, 0.0018f, 0.0017f, 0.0016f, 0.0015f, 0.0014f, 0.0014f, 0.0013f, 0.0012f, 0.0011f, 0.0011f
    };
    private static final double ENV_ATTACK_MS = 60.0;
    private static final double ENV_RELEASE_MS = 70.0;
    private static final int LOCAL_HIST = 64;

    public static int[] delayFrames(int version, float hz, int cap) {
        int[] d = new int[N];
        for (int b = 0; b < N; b++) {
            d[b] = version >= 3 ? Math.min(cap, Math.round(DELAY_S[b] * hz)) : 0;
        }
        return d;
    }

    /** Aligned latest RAW frame (linear RMS per semitone band). */
    public final float[] raw = new float[N];
    /** Aligned COOKED frame (desktop semantics, 0..1). */
    public final float[] cooked = new float[N];
    /** Aligned raw history, row 0 = newest. */
    public final float[] hist = new float[N * HIST];
    /** Engine features (unaligned, as on the desktop). */
    public final float[] features = new float[FEATURES];
    public int version;
    public float frameHz = 60f;
    public boolean live;

    private MappedByteBuffer map;
    private long mapSize;
    private long lastMapTry;
    private long lastSeq = -1;
    private long lastChangeNanos;
    private final float[] rawRing = new float[N * LOCAL_HIST];
    private final float[] cookedRing = new float[N * LOCAL_HIST];
    private final double[] env = new double[N];
    private int head = -1;
    private long frames;
    private final int[] delayFrames = new int[N];
    private float delayHz;
    private double attackCoef, releaseCoef;

    /** @return true when the arrays changed (a new frame, or everything went stale). */
    public boolean poll(long now) {
        if (map == null || (now - lastMapTry > REMAP_INTERVAL_NS && FILE.length() != mapSize)) {
            if (now - lastMapTry < REMAP_INTERVAL_NS && map == null) {
                return goStale(now);
            }
            lastMapTry = now;
            if (!remap()) {
                return goStale(now);
            }
        }
        if (map.getInt(HDR_OFF) != MAGIC) {
            map = null;
            return goStale(now);
        }
        long seq = map.getInt(SEQ_OFF) & 0xffffffffL;
        if (seq == lastSeq) {
            if (live && now - lastChangeNanos > STALE_NS) {
                return goStale(now);
            }
            return false;
        }
        int ringLen = map.getInt(RING_LEN_OFF);
        float hz = map.getFloat(HZ_OFF);
        if (ringLen <= 0 || hz <= 0f) {
            return false;
        }
        version = map.getInt(VER_OFF);
        if (hz != frameHz || delayHz != hz) {
            frameHz = hz;
            delayHz = hz;
            attackCoef = Math.exp(-1.0 / (hz * ENV_ATTACK_MS / 1000.0));
            releaseCoef = Math.exp(-1.0 / (hz * ENV_RELEASE_MS / 1000.0));
            System.arraycopy(delayFrames(version, hz, LOCAL_HIST - HIST), 0, delayFrames, 0, N);
        }
        if (lastSeq < 0 || seq < lastSeq || seq - lastSeq > ringLen - 2) {
            lastSeq = Math.max(0, seq - Math.min(ringLen - 2, LOCAL_HIST));
            head = -1;
            frames = 0;
            Arrays.fill(env, 0.0);
            Arrays.fill(rawRing, 0f);
            Arrays.fill(cookedRing, 0f);
        }
        for (long k = lastSeq; k < seq; k++) {
            head = (head + 1) % LOCAL_HIST;
            frames++;
            int src = RING_OFF + (int) (k % ringLen) * N * 4;
            int dst = head * N;
            for (int b = 0; b < N; b++) {
                float v = map.getFloat(src + b * 4);
                rawRing[dst + b] = v;
                double x = normDb(v);
                double coef = x > env[b] ? attackCoef : releaseCoef;
                env[b] = coef * env[b] + (1.0 - coef) * x;
                cookedRing[dst + b] = (float) env[b];
            }
        }
        for (int b = 0; b < N; b++) {
            int d = delayFrames[b];
            raw[b] = rawRing[rowAgo(d) * N + b];
            cooked[b] = cookedRing[rowAgo(d) * N + b];
            for (int r = 0; r < HIST; r++) {
                hist[r * N + b] = rawRing[rowAgo(r + d) * N + b];
            }
        }
        int cookedOff = RING_OFF + ringLen * N * 4;
        int featOff = cookedOff + N * 4;
        if (version >= 3 && mapSize >= featOff + FEATURES * 4L) {
            for (int i = 0; i < FEATURES; i++) {
                features[i] = map.getFloat(featOff + i * 4);
            }
        } else {
            Arrays.fill(features, 0f);
            features[0] = normDb(map.getFloat(RMS_OFF));
        }
        lastSeq = seq;
        lastChangeNanos = now;
        live = true;
        return true;
    }

    private int rowAgo(int ago) {
        if (ago >= frames) {
            return (head + 1) % LOCAL_HIST;
        }
        return ((head - ago) % LOCAL_HIST + LOCAL_HIST) % LOCAL_HIST;
    }

    private boolean goStale(long now) {
        if (!live) {
            return false;
        }
        live = false;
        lastChangeNanos = now;
        Arrays.fill(raw, 0f);
        Arrays.fill(cooked, 0f);
        Arrays.fill(hist, 0f);
        Arrays.fill(features, 0f);
        return true;
    }

    private boolean remap() {
        map = null;
        lastSeq = -1;
        delayHz = 0f;
        if (!FILE.exists() || FILE.length() < RING_OFF) {
            return false;
        }
        try (FileChannel ch = FileChannel.open(FILE.toPath(), StandardOpenOption.READ)) {
            mapSize = ch.size();
            map = ch.map(FileChannel.MapMode.READ_ONLY, 0, mapSize);
            map.order(ByteOrder.LITTLE_ENDIAN);
            return true;
        } catch (IOException e) {
            map = null;
            return false;
        }
    }

    static float normDb(float mag) {
        double db = 20.0 * Math.log10(mag + 1e-9);
        double v = (db - DB_FLOOR) / (0.0 - DB_FLOOR);
        return (float) Math.min(1.0, Math.max(0.0, v));
    }
}
