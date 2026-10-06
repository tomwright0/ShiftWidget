package com.shiftcal.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Palette + hex + RGB sliders. */
class ColorPickerView(context: Context, initial: Int) : LinearLayout(context) {

    var color: Int = initial or 0xFF000000.toInt()
        private set

    private val preview = View(context)
    private val hex = EditText(context)
    private val sliders = ArrayList<SeekBar>()
    private var updating = false

    init {
        orientation = VERTICAL
        val pad = dp(16)
        setPadding(pad, dp(8), pad, 0)

        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(10) })

        PALETTE.toList().chunked(6).forEach { rowColors ->
            val row = LinearLayout(context)
            rowColors.forEach { col ->
                val sw = View(context)
                sw.background = swatch(col, dp(6).toFloat())
                sw.setOnClickListener { setColor(col) }
                row.addView(sw, LayoutParams(0, dp(36), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            addView(row)
        }

        val hexRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        hexRow.addView(TextView(context).apply { text = "Hex  #" })
        hex.filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
        hex.isSingleLine = true
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating || s?.length != 6) return
                runCatching { Color.parseColor("#$s") }.onSuccess { setColor(it, fromHex = true) }
            }
        })
        hexRow.addView(hex, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(hexRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        listOf("R", "G", "B").forEach { name ->
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(context).apply { text = name; width = dp(20) })
            val sb = SeekBar(context).apply { max = 255 }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) setColor(Color.rgb(sliders[0].progress, sliders[1].progress, sliders[2].progress))
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            sliders += sb
            row.addView(sb, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(row)
        }
        setColor(color)
    }

    fun setColor(c: Int, fromHex: Boolean = false) {
        color = c or 0xFF000000.toInt()
        updating = true
        preview.background = swatch(color, dp(8).toFloat())
        if (!fromHex) hex.setText(String.format("%06X", color and 0xFFFFFF))
        sliders[0].progress = Color.red(color)
        sliders[1].progress = Color.green(color)
        sliders[2].progress = Color.blue(color)
        updating = false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Google Calendar's event colours first, then some neutrals/extras. */
        val PALETTE = intArrayOf(
            0xFFD50000.toInt(), 0xFFE67C73.toInt(), 0xFFF4511E.toInt(), 0xFFF6BF26.toInt(),
            0xFF33B679.toInt(), 0xFF0B8043.toInt(), 0xFF039BE5.toInt(), 0xFF3F51B5.toInt(),
            0xFF7986CB.toInt(), 0xFF8E24AA.toInt(), 0xFF616161.toInt(), 0xFFFFC107.toInt(),
            0xFF000000.toInt(), 0xFF1E1E24.toInt(), 0xFF2D2D36.toInt(), 0xFF9E9EA8.toInt(),
            0xFFE0E0E0.toInt(), 0xFFFFFFFF.toInt(),
        )

        fun swatch(color: Int, radius: Float) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(2, 0x55888888)
        }
    }
}
