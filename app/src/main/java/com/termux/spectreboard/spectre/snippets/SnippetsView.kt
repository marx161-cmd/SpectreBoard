// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.snippets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.spectreboard.event.HapticEvent
import com.termux.spectreboard.keyboard.KeyboardActionListener
import com.termux.spectreboard.keyboard.KeyboardSwitcher
import com.termux.spectreboard.keyboard.KeyboardTypeface
import com.termux.spectreboard.keyboard.internal.keyboard_parser.floris.KeyCode
import com.termux.spectreboard.latin.AudioAndHapticFeedbackManager
import com.termux.spectreboard.latin.R
import com.termux.spectreboard.latin.common.ColorType
import com.termux.spectreboard.latin.settings.Settings
import com.termux.spectreboard.latin.utils.ResourceUtils

/**
 * Snippets panel (toolbar key SNIPPETS, scope.md 2026-11-21): "a second clipboard". Header with
 * count, import and close; grid of snippets (trigger + one-line preview); tap inserts the
 * rendered text. Shown in place of the main keyboard view, same height as the clipboard panel.
 */
@SuppressLint("ViewConstructor")
class SnippetsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var listener: KeyboardActionListener? = null
    private val title: TextView
    private val importButton: TextView
    private val closeButton: ImageButton
    private val empty: TextView
    private val list: RecyclerView
    private val adapter = Adapter()
    private val storeListener: () -> Unit = { post { refresh() } }

    init {
        orientation = VERTICAL
        visibility = GONE
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(4), dp(2))
        }
        title = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        importButton = TextView(context).apply {
            text = context.getString(R.string.snippets_import)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener { v -> feedback(v); SnippetImportActivity.start(context) }
            contentDescription = context.getString(R.string.snippets_import)
        }
        closeButton = ImageButton(context, null, R.attr.suggestionWordStyle).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = context.getString(R.string.snippets_close)
            setOnClickListener { v -> feedback(v); KeyboardSwitcher.getInstance().hideSnippets() }
        }
        header.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(importButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(closeButton, LayoutParams(dp(44), dp(40)))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = FrameLayout(context)
        empty = TextView(context).apply {
            text = context.getString(R.string.snippets_empty)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = GONE
        }
        list = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = this@SnippetsView.adapter
            setPadding(dp(4), 0, dp(4), dp(4))
            clipToPadding = false
        }
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // same footprint as the clipboard panel
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(resources, Settings.getValues()) + paddingTop + paddingBottom
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    fun start(listener: KeyboardActionListener) {
        this.listener = listener
        SnippetStore.init(context)
        SnippetStore.addListener(storeListener)
        val colors = Settings.getValues().mColors
        val text = colors.get(ColorType.KEY_TEXT)
        title.setTextColor(text)
        importButton.setTextColor(colors.get(ColorType.KEY_TEXT))
        empty.setTextColor(colors.get(ColorType.KEY_HINT_TEXT))
        colors.setColor(closeButton, ColorType.TOOL_BAR_KEY)
        KeyboardTypeface.customTypeface()?.let { title.typeface = it; empty.typeface = it }
        refresh()
        list.scrollToPosition(0)
    }

    fun stop() {
        SnippetStore.removeListener(storeListener)
        listener = null
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        val set = SnippetStore.current()
        adapter.items = set.snippets.sortedBy { it.trigger }
        adapter.notifyDataSetChanged()
        title.text = context.getString(R.string.snippets_title, set.snippets.size)
        empty.visibility = if (set.isEmpty) VISIBLE else GONE
    }

    private fun insert(snippet: Snippet, v: View) {
        feedback(v)
        val l = listener ?: return
        SnippetExpander.insertFromPanel(snippet, l)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            KeyboardSwitcher.getInstance().hideSnippets()
    }

    private fun feedback(v: View) =
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, v, HapticEvent.KEY_PRESS)

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<Snippet> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context)
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    }

    private inner class Holder(context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val trigger = TextView(context)
        private val preview = TextView(context)
        private var snippet: Snippet? = null

        init {
            val root = itemView as LinearLayout
            root.orientation = VERTICAL
            root.setPadding(dp(10), dp(6), dp(10), dp(8))
            root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            root.isHapticFeedbackEnabled = false
            root.isSoundEffectsEnabled = false
            val colors = Settings.getValues().mColors
            colors.setBackground(root, ColorType.KEY_BACKGROUND)
            trigger.setTextColor(colors.get(ColorType.KEY_HINT_TEXT))
            trigger.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            trigger.isSingleLine = true
            preview.setTextColor(colors.get(ColorType.KEY_TEXT))
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            preview.isSingleLine = true
            preview.ellipsize = TextUtils.TruncateAt.END
            KeyboardTypeface.customTypeface()?.let { trigger.typeface = it; preview.typeface = it }
            root.addView(trigger)
            root.addView(preview)
            root.setOnClickListener { v -> snippet?.let { insert(it, v) } }
        }

        fun bind(s: Snippet) {
            snippet = s
            trigger.text = s.trigger
            preview.text = s.preview.take(200)
        }
    }
}
