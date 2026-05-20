// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.setFloatingSize
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.ViewLayoutUtils

// todo: now it's just static functionality -> move, maybe merge with viewLaoyutUtils and/or  view outline provider utils
// todo: improvements for later
//  add a frame around the keyboard
object FloatingKeyboardManager {
    private val TAG = this::class.java.simpleName

    fun setFloating(view: View) {
        val (x, y) = readPosition(view.context)
        ViewLayoutUtils.placeViewAt(
            view, x, y,
            Settings.getValues().mFloatingWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
            //Settings.getValues().mFloatingHeight + if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
          //  else view.context.resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()
        )
        Log.d("test", "place $view at $x, $y, ${Settings.getValues().mFloatingWidth}, ${Settings.getValues().mFloatingHeight}")
        if (view.findViewById<View>(R.id.float_handle_container)?.isVisible == true)
            return
        view.findViewById<View>(R.id.float_handle_container)?.isVisible = true
        view.findViewById<ImageView>(R.id.drag_handle)?.setDragListener(view)
        view.findViewById<ImageView>(R.id.resize_handle)?.setResizeListener(view)
        Log.d("test", "place at $x, $y, ${Settings.getValues().mFloatingWidth}, ${Settings.getValues().mFloatingHeight}")
    }

    fun disableFloating(view: View) {
        val lp = view.layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        (lp as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = 0
            it.leftMargin = 0
        }
        view.findViewById<View>(R.id.float_handle_container)?.isGone = true
        Log.d("test", "disable floating")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setDragListener(view: View) {
        val toolbarHeight = if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
            else context.resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()
        var startX = 0f
        var startY = 0f
        val lp = view.layoutParams as ViewGroup.MarginLayoutParams
        var positionX = lp.leftMargin.toFloat()
        var positionY = lp.topMargin.toFloat()
        val windowFrame = Rect()
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    startX = event.rawX
                    startY = event.rawY
                    val sv = Settings.getValues()
                    getWindowVisibleDisplayFrame(windowFrame)
                    val availableWidth = windowFrame.right - windowFrame.left
                    val availableHeight = windowFrame.bottom - windowFrame.top
                    positionX = (positionX + dx).coerceIn(0f, (availableWidth - sv.mFloatingWidth).toFloat())
                    positionY = (positionY + dy).coerceIn(0f, (availableHeight - layoutParams.height - toolbarHeight - sv.mFloatingHeight).toFloat())
                    lp.leftMargin = positionX.toInt()
                    lp.topMargin = positionY.toInt()
                    savePosition(context, lp.leftMargin, lp.topMargin)
                    view.layoutParams = lp
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setResizeListener(view: View) {
        var startX = 0f
        var startY = 0f
        val scale = 3 / context.resources.displayMetrics.density
        val toolbarHeight = if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
            else context.resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()
        val windowFrame = Rect()
        val lp = view.layoutParams as ViewGroup.MarginLayoutParams
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    startX = event.rawX
                    startY = event.rawY
                    getWindowVisibleDisplayFrame(windowFrame)
                    val availableWidth = windowFrame.right - windowFrame.left
                    val availableHeight = windowFrame.bottom - windowFrame.top
                    val maxWidth = (availableWidth * 0.9f).toInt()
                    val maxHeight = (availableHeight * 0.9f).toInt()
                    // avoid setting window outside windowFrame, view behaves strange otherwise
                    val newWidth = (Settings.getValues().mFloatingWidth + dx / scale).toInt().coerceIn(150, maxWidth)
                        .coerceAtMost(availableWidth - lp.leftMargin)
                    val newHeight = (Settings.getValues().mFloatingHeight + dy / scale).toInt().coerceIn(100, maxHeight)
                        .coerceAtMost(availableHeight - layoutParams.height - toolbarHeight - lp.topMargin)
                    setFloatingSize(context, newWidth, newHeight)
                    KeyboardSwitcher.getInstance().reloadKeyboard()
                    // updating window is done in updateFloating, called by reloadKeyboard
                    true
                }
                else -> false
            }
        }
    }

    fun readPosition(context: Context): Pair<Int, Int> {
        val width = context.resources.displayMetrics.widthPixels
        return context.prefs().getInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, width / 2) to
            context.prefs().getInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, context.resources.displayMetrics.heightPixels / 2)
    }

    private fun savePosition(context: Context, x: Int, y: Int) {
        val width = context.resources.displayMetrics.widthPixels
        context.prefs().edit {
            putInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, x)
            putInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, y)
        }
    }
}
