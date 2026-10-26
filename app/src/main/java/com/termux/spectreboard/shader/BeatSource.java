// SPDX-License-Identifier: GPL-3.0-only

package com.termux.spectreboard.shader;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Port of qs-shader-live's {@code BeatSource.java} (2026-10-18). Cached-song beat clock:
 * the active media session is matched against the slim desktop beat maps and gives the song
 * position; music playing on comrade (Cybersyn's mirror session has no position) follows the
 * mirrored go signal. No bands and no live tracker here — that is BandsFeed's job; this class
 * only answers "is there an absolute beat grid, and where are we in it".
 *
 * Changes from the original: own status file (never qs_shader/status.txt), maps/go/offset read
 * from qs-shader-live's shared folder, logging tag.
 */
public final class BeatSource {
    private static final File MAPS = new File(ShaderPaths.QS_DIR, "index.json");
    private static final File MAP_DIR = new File(ShaderPaths.QS_DIR, "maps");
    private static final File GO = new File(ShaderPaths.QS_DIR, "go.txt");
    private static final File OFFSET = new File(ShaderPaths.QS_DIR, "beat_offset_ms.txt");
    private static final String MIRROR_PKG = "com.termux.cybersyn";
    private static final double FULL_COVERAGE = 0.90;
    private static final double CONF_MIN = 0.10;
    private static final double DURATION_TOL_S = 2.0;
    private static final long POLL_MS = 500;
    private static final String TAG = "KeyboardShader";

    private String nowPlaying = "nothing playing";

    public static final class Track {
        int id;
        String artist, title;
        double bpm, coverage, confidence = Double.NaN, medianIv, span;
        double[] beats, downbeats;
        int beatsPerBar = 4;
        boolean exactKey;
        final Set<String> names = new HashSet<>();
        final Set<String> keys = new HashSet<>();
    }

    public static final class Frame {
        public boolean on;
        public double phase, count, bpm, barPhase, beatsPerBar = 4;
    }

    private static BeatSource instance;

    public static synchronized BeatSource get(Context ctx) {
        if (instance == null) instance = new BeatSource(ctx);
        return instance;
    }

    private final Context ctx;
    private final Handler handler;
    private volatile boolean active;
    private final File statusFile;
    private List<Track> maps;
    private long mapsMtime = -1;

    private volatile Track song;
    private volatile double anchorPosS;
    private volatile long anchorRealtime;
    private volatile float speed;
    private volatile long goStartWallMs;
    private volatile double offsetS;
    private String sessionKey;

    private BeatSource(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.statusFile = ShaderPaths.status(this.ctx, "beat_status.txt");
        HandlerThread t = new HandlerThread("KbShaderBeat");
        t.start();
        handler = new Handler(t.getLooper());
    }

    private final Set<String> clients = new HashSet<>();

    /** Each surface reports itself; the poll runs while any surface is active. */
    public synchronized void setActive(String who, boolean on) {
        if (on) clients.add(who); else clients.remove(who);
        boolean any = !clients.isEmpty();
        if (any == active) return;
        active = any;
        if (any) handler.post(this::poll);
    }

    private void poll() {
        if (!active) return;
        try {
            loadMapsIfChanged();
            offsetS = readOffsetS();
            if (!pollSession()) pollGo();
            writeStatus();
        } catch (Throwable t) {
            Log.w(TAG, "beat poll failed", t);
        }
        handler.postDelayed(this::poll, POLL_MS);
    }

    private boolean pollSession() {
        MediaController playing = null;
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            for (MediaController c : msm.getActiveSessions(null)) {
                if (MIRROR_PKG.equals(c.getPackageName())) continue;
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    playing = c;
                    break;
                }
            }
        } catch (Throwable t) {
            // no MEDIA_CONTENT_CONTROL -> go signal only
        }
        if (playing == null) {
            sessionKey = null;
            nowPlaying = "nothing playing locally";
            return false;
        }
        MediaMetadata md = playing.getMetadata();
        String artist = md != null ? nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST)) : "";
        String title = md != null ? nz(md.getString(MediaMetadata.METADATA_KEY_TITLE)) : "";
        long durMs = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        String key = playing.getPackageName() + "|" + artist + "|" + title + "|" + durMs;
        Track t = song;
        if (!key.equals(sessionKey)) {
            sessionKey = key;
            t = match(artist, title, durMs / 1000.0);
            nowPlaying = playing.getPackageName() + " '" + artist + " - " + title + "' -> "
                    + (t != null ? String.format(Locale.US, "map %.1f BPM", t.bpm) : "no map");
            Log.i(TAG, "now playing " + nowPlaying);
        }
        if (t == null) {
            song = null;
            return false;
        }
        PlaybackState s = playing.getPlaybackState();
        long since = SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
        anchorPosS = (s.getPosition() + since * s.getPlaybackSpeed()) / 1000.0;
        anchorRealtime = SystemClock.elapsedRealtime();
        speed = s.getPlaybackSpeed();
        goStartWallMs = 0;
        song = t;
        return true;
    }

    /** "go <heard_start_phone_ms> <duration_s> <key>" | "stop <ms>" from comrade. */
    private void pollGo() {
        String line = readSmall(GO);
        String[] p = line == null ? new String[0] : line.trim().split(" ", 4);
        if (p.length == 4 && "go".equals(p[0])) {
            try {
                long start = Long.parseLong(p[1]);
                double dur = Double.parseDouble(p[2]);
                long now = System.currentTimeMillis();
                if (now >= start - 5_000L && now <= start + (long) (dur * 1000) + 2_000L) {
                    Track t = byKey(normKey(p[3]));
                    if (t != null) {
                        goStartWallMs = start;
                        song = t;
                        return;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        goStartWallMs = 0;
        song = null;
    }

    /** Render-thread snapshot. */
    public Frame frame() {
        Frame f = new Frame();
        Track t = song;
        if (t == null) return f;
        double pos;
        if (goStartWallMs > 0) {
            pos = (System.currentTimeMillis() - goStartWallMs) / 1000.0;
        } else {
            pos = anchorPosS + (SystemClock.elapsedRealtime() - anchorRealtime) * speed / 1000.0;
        }
        pos -= offsetS;
        double[] b = mapLookup(t.beats, pos, t.medianIv);
        if (b == null) return f;
        f.on = true;
        f.count = b[0];
        f.phase = b[1];
        f.bpm = 60.0 / Math.max(b[2], 1e-3);
        f.beatsPerBar = t.beatsPerBar;
        double[] bar = t.downbeats != null && t.downbeats.length >= 2
                ? mapLookup(t.downbeats, pos, t.medianIv * t.beatsPerBar) : null;
        f.barPhase = bar != null ? bar[1] : mod(b[0], t.beatsPerBar) / t.beatsPerBar;
        return f;
    }

    private void writeStatus() {
        Frame f = frame();
        List<Track> m = maps;
        String s = String.format(Locale.US,
                "maps=%d session=%s go=%s beatOn=%b bpm=%.1f count=%.2f bar=%.2f offsetMs=%.0f%n",
                m == null ? 0 : m.size(), nowPlaying, goStartWallMs > 0, f.on, f.bpm, f.count,
                f.barPhase, offsetS * 1000);
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(statusFile)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    // --- copied from ShaderEditor BeatClock (keep in sync) ---

    static double[] mapLookup(double[] bt, double t, double medianIv) {
        if (bt == null || bt.length < 2 || medianIv <= 0.0) return null;
        int last = bt.length - 1;
        double count, tbeat;
        if (t <= bt[0]) {
            tbeat = bt[1] - bt[0];
            count = -(bt[0] - t) / Math.max(tbeat, 1e-6);
        } else if (t >= bt[last]) {
            tbeat = bt[last] - bt[last - 1];
            count = last + (t - bt[last]) / Math.max(tbeat, 1e-6);
        } else {
            int lo = 0, hi = last;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (bt[mid] <= t) lo = mid; else hi = mid;
            }
            tbeat = bt[lo + 1] - bt[lo];
            count = lo + (t - bt[lo]) / Math.max(tbeat, 1e-6);
        }
        return new double[]{count, mod(count, 1.0), tbeat};
    }

    private Track match(String artist, String title, double durationS) {
        List<Track> m = maps;
        if (m == null || title.isEmpty()) return null;
        Set<String> want = new HashSet<>();
        want.add(norm(title));
        if (!artist.isEmpty()) want.add(norm(artist + title));
        for (Track t : m) {
            boolean hit = false;
            for (String n : want) if (t.names.contains(n)) { hit = true; break; }
            if (!hit) continue;
            if (t.coverage < FULL_COVERAGE) continue;
            if (!Double.isNaN(t.confidence) && t.confidence < CONF_MIN) continue;
            if (!t.exactKey && durationS > 0) {
                if (Math.abs(durationS - t.span) > Math.max(DURATION_TOL_S, 0.1 * durationS)) continue;
            }
            return loadBeats(t) ? t : null;
        }
        return null;
    }

    private Track byKey(String key) {
        List<Track> m = maps;
        if (m == null) return null;
        for (Track t : m) if (t.keys.contains(key)) return loadBeats(t) ? t : null;
        return null;
    }

    private void loadMapsIfChanged() {
        long mt = MAPS.exists() ? MAPS.lastModified() : 0;
        if (mt == mapsMtime) return;
        mapsMtime = mt;
        if (mt == 0) { maps = null; return; }
        List<Track> out = new ArrayList<>();
        try {
            String js = new String(Files.readAllBytes(MAPS.toPath()), StandardCharsets.UTF_8);
            JSONArray tracks = new JSONObject(js).getJSONArray("tracks");
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject o = tracks.getJSONObject(i);
                Track t = new Track();
                t.id = o.getInt("id");
                t.span = o.optDouble("span", 0);
                t.artist = o.optString("artist");
                t.title = o.optString("title");
                t.bpm = o.optDouble("bpm", 0);
                t.coverage = o.optDouble("coverage", 0);
                t.confidence = o.isNull("confidence") ? Double.NaN : o.optDouble("confidence");
                t.beatsPerBar = Math.max(1, o.optInt("beats_per_bar", 4));
                t.names.add(norm(t.title));
                t.names.add(norm(t.artist + t.title));
                JSONArray keys = o.optJSONArray("keys");
                if (keys != null) {
                    for (int k = 0; k < keys.length(); k++) {
                        String key = keys.getString(k);
                        t.keys.add(normKey(key));
                        if (key.startsWith("file:")) {
                            String base = fileBase(key);
                            t.names.add(norm(base));
                            int dash = base.indexOf(" - ");
                            if (dash > 0) t.names.add(norm(base.substring(dash + 3)));
                            t.exactKey = true;
                        }
                    }
                }
                out.add(t);
            }
            maps = out;
            sessionKey = null;
            Log.i(TAG, "loaded " + out.size() + " beat maps");
        } catch (Throwable e) {
            Log.w(TAG, "cannot load " + MAPS, e);
        }
    }

    /** Per-track file: int32 nBeats, int32 nDown, float32 beats[], float32 downbeats[] (LE). */
    private static boolean loadBeats(Track t) {
        if (t.beats != null) return true;
        File f = new File(MAP_DIR, t.id + ".bin");
        try {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(Files.readAllBytes(f.toPath()))
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int nb = bb.getInt(), nd = bb.getInt();
            if (nb < 2 || nb > 200_000 || nd < 0 || nd > 200_000) return false;
            double[] beats = new double[nb];
            for (int k = 0; k < nb; k++) beats[k] = bb.getFloat();
            double[] down = new double[nd];
            for (int k = 0; k < nd; k++) down[k] = bb.getFloat();
            double[] iv = new double[nb - 1];
            for (int k = 0; k < iv.length; k++) iv[k] = beats[k + 1] - beats[k];
            Arrays.sort(iv);
            t.medianIv = iv[iv.length / 2];
            t.downbeats = nd >= 2 ? down : null;
            t.beats = beats;
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "cannot load " + f, e);
            return false;
        }
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String normKey(String key) {
        if (!key.startsWith("file:")) return key;
        try {
            return "file:" + URLDecoder.decode(key.substring(5).replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return key;
        }
    }

    private static String fileBase(String key) {
        String path = key.substring(5);
        try {
            path = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
        }
        String base = path.substring(path.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static double readOffsetS() {
        String s = readSmall(OFFSET);
        if (s == null) return 0.0;
        try {
            return Double.parseDouble(s.trim()) / 1000.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String readSmall(File f) {
        if (!f.exists() || f.length() > 4096) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static double mod(double a, double m) {
        double r = a % m;
        return r < 0 ? r + m : r;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
