package com.termux.spectreboard.spectre

import android.content.Context
import android.content.Intent
import android.view.inputmethod.CursorAnchorInfo

/**
 * One neko (shaderfx scope.md 2026-11-11): the status-bar cat climbs down to chase the text cursor. The IME is told
 * where the cursor is in ANY text field (CursorAnchorInfo, requested in InputLogic.startInput), so we just forward
 * its screen position to SystemUI as an explicit broadcast. No position, no message: apps that don't report a cursor
 * (Termux today) simply don't call her down.
 */
object NekoCursor {
    private const val ACTION = "dev.marx.shaderfx.NEKO_CURSOR"
    private const val SYSTEMUI = "com.android.systemui"
    private var lastX = Float.NaN
    private var lastBottom = Float.NaN
    private var on = false
    private var visible = false   // LatinIME only calls onAnchor while isInputViewShown(): a focused field keeps
                                  // reporting after BACK hides the keyboard (live test 2026-11-11 19:25)

    fun shown() { visible = true }

    fun onAnchor(ctx: Context, info: CursorAnchorInfo?) {
        if (info == null) return
        visible = true
        val mx = info.insertionMarkerHorizontal
        if (mx.isNaN()) return                                   // a selection, or no caret reported
        if (info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0) return
        val pts = floatArrayOf(mx, info.insertionMarkerTop, mx, info.insertionMarkerBottom)
        info.matrix.mapPoints(pts)                               // view-local -> screen px
        if (on && pts[0] == lastX && pts[3] == lastBottom) return
        lastX = pts[0]; lastBottom = pts[3]; on = true
        send(ctx, Intent(ACTION).putExtra("on", true)
            .putExtra("x", pts[0]).putExtra("top", pts[1]).putExtra("bottom", pts[3]))
    }

    fun gone(ctx: Context) {
        visible = false
        if (!on) return
        on = false
        send(ctx, Intent(ACTION).putExtra("on", false))
    }

    /** IME (re)start: whatever the last process told SystemUI, the cat goes home. */
    fun reset(ctx: Context) {
        on = false; visible = false
        send(ctx, Intent(ACTION).putExtra("on", false))
    }

    private fun send(ctx: Context, i: Intent) {
        try { ctx.sendBroadcast(i.setPackage(SYSTEMUI)) } catch (_: Throwable) { }
    }
}
