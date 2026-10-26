// SPDX-License-Identifier: GPL-3.0-only

package com.termux.spectreboard.shader;

import android.graphics.RuntimeShader;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of qs-shader-live's {@code ShaderFile.java} (2026-10-18): one live AGSL file,
 * mtime-checked hot reload (at most once a second), {@code //#include name.agsl} support,
 * compile errors written to a readable status file, and setters that only touch uniforms the
 * shader actually declares.
 *
 * A file that fails to compile leaves {@link #get()} null; the caller removes the RenderEffect
 * so the keyboard draws stock. It never throws into the IME.
 */
final class ShaderFile {
    /** One clock for every surface (wrapped hourly so float precision survives). */
    static final long T0 = SystemClock.elapsedRealtime();

    private static final long CHECK_MS = 1000;
    private static final Pattern UNIFORM = Pattern.compile(
            "uniform\\s+(?:float|half|float2|half2|float3|half3|float4|half4|shader)\\s+(\\w+)");
    private static final Pattern INCLUDE = Pattern.compile("(?m)^//#include\\s+(\\S+)\\s*$");

    private final File dir;
    private final File src;
    private final File status;
    private RuntimeShader shader;
    private Set<String> uniforms = new HashSet<>();
    private long mtime = -1;
    private long lastCheck;
    private long incMtime;
    private long commonMtime = -1;
    private final File common;

    ShaderFile(File dir, String name, String statusName) {
        this.dir = dir;
        this.src = new File(dir, name);
        this.status = new File(dir, statusName);
        this.common = new File(dir, "common.agsl");
    }

    /** Current shader (reloaded if the file changed), or null if missing / failed. */
    RuntimeShader get() {
        long now = SystemClock.elapsedRealtime();
        if (mtime < 0 || now - lastCheck >= CHECK_MS) {
            lastCheck = now;
            reload();
        }
        return shader;
    }

    File source() {
        return src;
    }

    boolean has(String name) {
        return uniforms.contains(name);
    }

    void set(String name, float v) {
        if (shader != null && uniforms.contains(name)) shader.setFloatUniform(name, v);
    }

    void set(String name, float a, float b) {
        if (shader != null && uniforms.contains(name)) shader.setFloatUniform(name, a, b);
    }

    void set(String name, float a, float b, float c, float d) {
        if (shader != null && uniforms.contains(name)) shader.setFloatUniform(name, a, b, c, d);
    }

    void setArray(String name, float[] v) {
        if (shader != null && uniforms.contains(name)) shader.setFloatUniform(name, v);
    }

    private void reload() {
        long m = src.exists() ? src.lastModified() : 0;
        long commonM = common.exists() ? common.lastModified() : 0;
        if (m == mtime && commonM == commonMtime) return;
        commonMtime = commonM;
        mtime = m;
        if (m == 0) {
            shader = null;
            return;
        }
        try {
            String code = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);
            Matcher inc = INCLUDE.matcher(code);
            StringBuffer sb = new StringBuffer();
            while (inc.find()) {
                File f = new File(dir, inc.group(1));
                incMtime = Math.max(incMtime, f.lastModified());
                inc.appendReplacement(sb, Matcher.quoteReplacement(
                        new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            }
            inc.appendTail(sb);
            code = sb.toString();
            RuntimeShader s = new RuntimeShader(code);
            Set<String> names = new HashSet<>();
            Matcher mm = UNIFORM.matcher(code);
            while (mm.find()) names.add(mm.group(1));
            uniforms = names;
            shader = s;
            writeStatus("OK " + src + " uniforms=" + names + "\n");
        } catch (Throwable t) {
            shader = null;
            writeStatus("ERROR " + t + "\n");
            Log.w(KeyboardShader.TAG, src.getName() + " compile failed", t);
        }
    }

    private void writeStatus(String msg) {
        try (FileOutputStream o = new FileOutputStream(status)) {
            o.write(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }
}
