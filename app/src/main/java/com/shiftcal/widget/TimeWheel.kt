package com.shiftcal.widget

import android.content.Context
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import java.time.LocalTime

/** Scrolling hour / minute (5-minute steps) / am-pm wheels – an alternative to the clock-face picker. */
class TimeWheel(context: Context, initial: LocalTime, private val onChange: (LocalTime) -> Unit) : LinearLayout(context) {

    private val is24 = DateFormat.is24HourFormat(context)
    private val hour = NumberPicker(context)
    private val minute = NumberPicker(context)
    private val amPm = NumberPicker(context)

    val time: LocalTime
        get() {
            val m = minute.value * 5
            if (is24) return LocalTime.of(hour.value, m)
            val h12 = hour.value % 12            // 12 → 0
            return LocalTime.of(if (amPm.value == 1) h12 + 12 else h12, m)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        if (is24) {
            hour.minValue = 0; hour.maxValue = 23
            hour.setFormatter { String.format("%02d", it) }
        } else {
            hour.minValue = 1; hour.maxValue = 12
        }
        minute.minValue = 0; minute.maxValue = 11
        minute.displayedValues = Array(12) { String.format("%02d", it * 5) }
        amPm.minValue = 0; amPm.maxValue = 1
        amPm.displayedValues = arrayOf("am", "pm")

        for (p in listOf(hour, minute, amPm)) {
            p.descendantFocusability = FOCUS_BLOCK_DESCENDANTS   // scroll only, no keyboard
            p.setOnValueChangedListener { _, _, _ -> onChange(time) }
        }
        hour.wrapSelectorWheel = true
        minute.wrapSelectorWheel = true

        addView(hour)
        addView(android.widget.TextView(context).apply { text = ":"; textSize = 20f; setPadding(12, 0, 12, 0) })
        addView(minute)
        if (!is24) addView(amPm, LayoutParams(-2, -2).apply { leftMargin = 24 })

        set(initial)
    }

    fun set(t: LocalTime) {
        // Round to the nearest 5 minutes the wheel can show.
        val rounded = t.withSecond(0).withNano(0).let { it.withMinute(it.minute / 5 * 5) }
        if (is24) hour.value = rounded.hour else {
            hour.value = if (rounded.hour % 12 == 0) 12 else rounded.hour % 12
            amPm.value = if (rounded.hour >= 12) 1 else 0
        }
        minute.value = rounded.minute / 5
    }
}
