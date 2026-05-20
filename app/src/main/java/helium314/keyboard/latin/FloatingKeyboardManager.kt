// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.databinding.FloatContainerBinding
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.setFloatingSize
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.define.DebugFlags

// influenced by LeanType FloatingKeyboardManager, but actually quite different

// todo: improvements for later
//  add a frame around the keyboard
//  make views outside the keyboard (window) visible
//   (dynamic) floating gesture preview
//   more suggestions popup
class FloatingKeyboardManager {
    private val TAG = this::class.java.simpleName
    val isFloating get() = floatingContainer != null
    private var floatingContainer: FloatContainerBinding? = null
    private val containerRoot get() = floatingContainer?.root

    fun updateFloating(view: View) {
        val windowParams = containerRoot?.layoutParams as? WindowManager.LayoutParams
        if (windowParams == null) {
            Log.e(TAG, "updateFloating, but we're not floating")
            return
        }
        if (floatingContainer?.content?.children?.firstOrNull() != view) {
            // maybe switching views would be enough, but re-enabling works just fine
            // (but always enableFloating breaks on orientation change)
            enableFloating(view)
            return
        }
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "updateFloating with $view")
        val context = view.context
        windowParams.gravity = Gravity.TOP or Gravity.START
        val (x, y) = readPosition(context)
        windowParams.x = x
        windowParams.y = y
        windowParams.width = Settings.getValues().mFloatingWidth
        try {
            context.windowManager().updateViewLayout(containerRoot, windowParams)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout error", e)
        }
    }

    // returns whether it was successful and actually changed something
    fun enableFloating(view: View): Boolean {
        val context = view.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
            // ideally we would wait until it's set and then continue
            requestOverlayPermission(context)
            return false
        }

        val windowParams = WindowManager.LayoutParams(
            Settings.getValues().mFloatingWidth, // todo: increase if we add some frame around the keyboard
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        windowParams.gravity = Gravity.TOP or Gravity.START
        val (x, y) = readPosition(context)
        windowParams.x = x
        windowParams.y = y
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "Initializing floating view at $x, $y: $view")

        containerRoot?.isVisible = true
        floatingContainer?.content?.removeAllViews()
        if (!isFloating)
            floatingContainer = FloatContainerBinding.inflate(LayoutInflater.from(context))
        val wm = context.windowManager()
        try {
            if (containerRoot?.parent == null)
                wm.addView(containerRoot, windowParams)
            (view.parent as? ViewGroup)?.removeView(view)
            floatingContainer?.content?.addView(view, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Could not enable floating keyboard", e)
            disableFloating()
            return false
        }
        val colors = Settings.getValues().mColors
        floatingContainer?.dragHandle?.apply {
            colors.setBackground(this, ColorType.MAIN_BACKGROUND)
            colors.setColor(this, ColorType.FUNCTIONAL_KEY_TEXT)
            setDragListener(windowParams, wm)
        }
        floatingContainer?.resizeHandle?.apply {
            colors.setBackground(this, ColorType.MAIN_BACKGROUND)
            colors.setColor(this, ColorType.FUNCTIONAL_KEY_TEXT)
            setResizeListener(windowParams)
        }
        return true
    }

    fun disableFloating() {
        val container = floatingContainer ?: return
        container.content.removeAllViews()
        container.root.context.windowManager().removeView(container.root)
        floatingContainer = null
    }

    fun hide() {
        containerRoot?.isGone = true
    }

    fun showIfEnabled() {
        containerRoot?.isVisible = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setDragListener(windowParams: WindowManager.LayoutParams, windowManager: WindowManager) {
        val toolbarHeight = if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
            else context.resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()
        var startX = 0f
        var startY = 0f
        var positionX = windowParams.x.toFloat()
        var positionY = windowParams.y.toFloat()
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
                    val sv = Settings.getValues()
                    getWindowVisibleDisplayFrame(windowFrame)
                    val availableWidth = windowFrame.right - windowFrame.left
                    val availableHeight = windowFrame.bottom - windowFrame.top
                    positionX = (positionX + dx).coerceIn(0f, (availableWidth - sv.mFloatingWidth).toFloat())
                    positionY = (positionY + dy).coerceIn(0f, (availableHeight - layoutParams.height - toolbarHeight - sv.mFloatingHeight).toFloat())
                    windowParams.x = positionX.toInt()
                    windowParams.y = positionY.toInt()
                    try {
                        windowManager.updateViewLayout(containerRoot, windowParams)
                    } catch (e: Exception) {
                        Log.w(TAG, "updateViewLayout error", e)
                    }
                    startX = event.rawX
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePosition(context, windowParams.x, windowParams.y)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.setResizeListener(windowParams: WindowManager.LayoutParams) {
        var startX = 0f
        var startY = 0f
        val scale = 3 / context.resources.displayMetrics.density
        val toolbarHeight = if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
            else context.resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()
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
                    getWindowVisibleDisplayFrame(windowFrame)
                    val availableWidth = windowFrame.right - windowFrame.left
                    val availableHeight = windowFrame.bottom - windowFrame.top
                    val maxWidth = (availableWidth * 0.9f).toInt()
                    val maxHeight = (availableHeight * 0.9f).toInt()
                    // avoid setting window outside windowFrame, view behaves strange otherwise
                    val newWidth = (Settings.getValues().mFloatingWidth + dx / scale).toInt().coerceIn(150, maxWidth)
                        .coerceAtMost(availableWidth - windowParams.x)
                    val newHeight = (Settings.getValues().mFloatingHeight + dy / scale).toInt().coerceIn(100, maxHeight)
                        .coerceAtMost(availableHeight - layoutParams.height - toolbarHeight - windowParams.y)
                    setFloatingSize(context, newWidth, newHeight)
                    KeyboardSwitcher.getInstance().reloadKeyboard()
                    // updating window is done in updateFloating, called by reloadKeyboard
                    true
                }
                else -> false
            }
        }
    }

    private fun readPosition(context: Context): Pair<Int, Int> {
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

    @RequiresApi(Build.VERSION_CODES.M)
    private fun requestOverlayPermission(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri()
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun Context.windowManager() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
        else getSystemService(Context.WINDOW_SERVICE) as WindowManager
}
