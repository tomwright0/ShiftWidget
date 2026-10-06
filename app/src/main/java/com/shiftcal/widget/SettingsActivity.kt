package com.shiftcal.widget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            // Android 15+ draws apps edge-to-edge; keep content clear of the status/nav bars.
            clipToPadding = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        scroll.addView(content)
        setContentView(scroll)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        if (!CalendarRepo.hasPermission(this)) requestCalendar()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun requestCalendar() =
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1)

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        BaseWidgetProvider.updateAll(this)
        BaseWidgetProvider.scheduleRefreshes(this)
        render()
    }

    /** Persist-then-redraw for any setting change. */
    private fun changed(rerender: Boolean = true) {
        BaseWidgetProvider.updateAll(this)
        if (rerender) render()
    }

    private fun render() {
        content.removeAllViews()

        if (!CalendarRepo.hasPermission(this)) {
            content.addView(text("Calendar access is needed to show your Google Calendar shifts. " +
                "Make sure your Google account is synced on this phone (Settings → Accounts)."))
            content.addView(Button(this).apply { text = "Allow calendar access"; setOnClickListener { requestCalendar() } })
        }

        content.addView(text("Changes apply straight away to the widgets on your home screen. " +
            "To add a widget: long-press the home screen → Widgets → Shift Widget.", small = true))

        // ---- widget style
        section("Widget colours")
        val auto = Prefs.autoTextColors(this)
        content.addView(Switch(this).apply {
            text = "Automatic text colours"
            isChecked = auto
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, on -> Prefs.setAutoTextColors(this@SettingsActivity, on); changed() }
        })
        content.addView(text(
            if (auto) "Titles, dates and event text switch between white and black for the best contrast " +
                "against what's behind them – including your wallpaper when the widget is see-through."
            else "Turn on to pick text colours automatically. Below you set them yourself.",
            small = true
        ))
        val textColors = setOf(StyleColor.HEADER_TEXT, StyleColor.DAY_TEXT, StyleColor.WEEKDAY_TEXT, StyleColor.EVENT_TEXT)
        StyleColor.entries.filter { !auto || it !in textColors }.forEach { sc ->
            colorRow(sc.label, null, Prefs.color(this, sc)) {
                pickColor(sc.label, Prefs.color(this, sc)) { Prefs.setColor(this, sc, it); changed() }
            }
        }
        slider("Background opacity", Prefs.bgOpacity(this)) { Prefs.setBgOpacity(this, it) }
        slider("Header opacity", Prefs.headerOpacity(this)) { Prefs.setHeaderOpacity(this, it) }
        content.addView(Button(this).apply {
            text = "Reset widget colours"
            setOnClickListener { Prefs.resetStyle(this@SettingsActivity); changed() }
        })

        // ---- layout options
        section("Options")
        content.addView(TextView(this).apply {
            text = "App theme: ${AppTheme.NAMES[Prefs.appTheme(this@SettingsActivity)]}"
            textSize = 16f
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("App theme")
                    .setSingleChoiceItems(AppTheme.NAMES, Prefs.appTheme(this@SettingsActivity)) { d, which ->
                        Prefs.setAppTheme(this@SettingsActivity, which)
                        d.dismiss()
                        recreate()   // the calendar screen picks it up when you go back
                    }
                    .show()
            }
        })
        toggle("Week starts on Monday", Prefs.weekStartsMonday(this)) { Prefs.setWeekStartsMonday(this, it) }
        toggle("Month: join multi-day events into one bar", Prefs.joinMultiDay(this)) { Prefs.setJoinMultiDay(this, it) }
        toggle("Month: show start times", Prefs.monthShowTimes(this)) { Prefs.setMonthShowTimes(this, it) }
        toggle("Upcoming: skip days with no shifts", Prefs.upcomingSkipEmpty(this)) { Prefs.setUpcomingSkipEmpty(this, it) }
        stepper("Upcoming: days in list", Prefs.upcomingDays(this), 7, 62) { Prefs.setUpcomingDays(this, it) }
        stepper("Upcoming: line thickness", Prefs.upcomingLineDp(this), 0, 10) { Prefs.setUpcomingLineDp(this, it) }

        // ---- shifts
        section("Shift colours")
        content.addView(text("Every event name found in your calendar. Tap one to change its colour, " +
            "give it a short label (e.g. \"Day\" for Day Shift), mark whether it's a shift, or hide it.", small = true))
        val shifts = CalendarRepo.distinctShifts(this)
        if (shifts.isEmpty()) content.addView(text("No events found yet.", small = true))
        shifts.forEach { s ->
            val custom = Prefs.shiftColor(this, s.title)
            val label = Prefs.shiftLabel(this, s.title)
            val sub = buildList {
                if (Prefs.isShift(this@SettingsActivity, s.title)) add("shift")
                add(if (custom != null) "custom colour" else "Google colour")
                if (label != null) add("label \"$label\"")
                if (Prefs.isShiftHidden(this@SettingsActivity, s.title)) add("hidden")
            }.joinToString(" · ")
            colorRow(s.title, sub, custom ?: s.color) { editShift(s) }
        }

        // ---- notes
        section("Notes")
        val noteCal = CalendarRepo.noteCalendar(this)
        val writable = CalendarRepo.calendars(this).filter { it.writable }
        colorRow("Save notes to", noteCal?.let { "${it.name} · ${it.account}" + if (it.isGoogle) "" else " (phone only)" }
            ?: "No editable calendar found",
            noteCal?.color ?: 0xFF888888.toInt()) {
            if (writable.isEmpty()) return@colorRow
            AlertDialog.Builder(this)
                .setTitle("Save notes to")
                .setSingleChoiceItems(writable.map {
                    "${it.name}\n${it.account}" + if (it.isGoogle) "" else " – phone only, doesn't sync to Google"
                }.toTypedArray(),
                    writable.indexOfFirst { it.id == noteCal?.id }) { d, which ->
                    Prefs.setNoteCalendarId(this, writable[which].id)
                    d.dismiss()
                    render()
                }
                .show()
        }

        // ---- calendars
        section("Calendars")
        content.addView(text("Switch a calendar off to hide it from the app and widgets.", small = true))
        val hidden = Prefs.hiddenCalendars(this)
        CalendarRepo.calendars(this).forEach { cal -> content.addView(calendarRow(cal, cal.id !in hidden)) }
        content.addView(Button(this).apply {
            text = "⟳  Sync calendars now"
            setOnClickListener {
                val n = CalendarRepo.requestSync(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity,
                    if (n == 0) "No synced calendar accounts on this phone" else "Syncing… new events will appear in a moment",
                    Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        content.addView(text("Calendars added to Google Calendar \"from URL\" are refreshed by Google every few " +
            "hours – syncing gets whatever Google has fetched so far.", small = true))
    }

    /** One calendar: colour, name, account, shown/hidden status with a switch, and a fix if it isn't syncing. */
    private fun calendarRow(cal: CalendarInfo, shown: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(View(this).apply { background = ColorPickerView.swatch(cal.color, dp(9).toFloat()) },
            LinearLayout.LayoutParams(dp(18), dp(18)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = cal.name; textSize = 16f })
        texts.addView(TextView(this).apply { text = cal.account; textSize = 12f; alpha = 0.6f })
        val status = TextView(this).apply {
            textSize = 12f
            text = if (shown) "Shown" else "Hidden"
            alpha = if (shown) 0.9f else 0.5f
        }
        texts.addView(status)
        if (!cal.syncing) {
            texts.addView(TextView(this).apply {
                text = "⚠ Not syncing to this phone – its events won't appear"
                textSize = 12f
                setTextColor(0xFFFFB74D.toInt())
            })
            texts.addView(Button(this).apply {
                text = "Turn on sync"
                isAllCaps = false
                setOnClickListener {
                    val ok = CalendarRepo.enableSync(this@SettingsActivity, cal)
                    Toast.makeText(this@SettingsActivity,
                        if (ok) "Sync turned on – events will download shortly" else "Couldn't turn on sync (allow calendar access?)",
                        Toast.LENGTH_SHORT).show()
                    render()
                }
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })

        row.addView(Switch(this).apply {
            isChecked = shown
            setOnCheckedChangeListener { _, on ->
                Prefs.setCalendarHidden(this@SettingsActivity, cal.id, !on)
                status.text = if (on) "Shown" else "Hidden"
                status.alpha = if (on) 0.9f else 0.5f
                changed(rerender = false)
            }
        })
        return row
    }

    // ------------------------------------------------------------ dialogs

    private fun pickColor(title: String, initial: Int, onPick: (Int) -> Unit) {
        val picker = ColorPickerView(this, initial)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(picker) })
            .setPositiveButton("OK") { _, _ -> onPick(picker.color) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editShift(s: ShiftInfo) {
        val picker = ColorPickerView(this, Prefs.shiftColor(this, s.title) ?: s.color)
        val label = EditText(this).apply {
            hint = "Short label, e.g. \"${s.title.substringBefore(" ")}\""
            setText(Prefs.shiftLabel(this@SettingsActivity, s.title) ?: "")
            isSingleLine = true
        }
        val isShift = CheckBox(this).apply {
            text = "This is a shift (colours the date in Upcoming)"
            isChecked = Prefs.isShift(this@SettingsActivity, s.title)
        }
        val hide = CheckBox(this).apply {
            text = "Hide on widgets"
            isChecked = Prefs.isShiftHidden(this@SettingsActivity, s.title)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(picker)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), 0)
                addView(label)
                addView(isShift)
                addView(hide)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(s.title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                Prefs.setShiftColor(this, s.title, picker.color)
                Prefs.setShiftLabel(this, s.title, label.text.toString())
                Prefs.setShiftHidden(this, s.title, hide.isChecked)
                Prefs.setIsShift(this, s.title, isShift.isChecked)
                changed()
            }
            .setNeutralButton("Use Google colour") { _, _ ->
                Prefs.setShiftColor(this, s.title, null)
                Prefs.setShiftLabel(this, s.title, label.text.toString())
                Prefs.setShiftHidden(this, s.title, hide.isChecked)
                Prefs.setIsShift(this, s.title, isShift.isChecked)
                changed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ row helpers

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(6))
        })
    }

    private fun text(s: String, small: Boolean = false) = TextView(this).apply {
        text = s
        if (small) { textSize = 13f; alpha = 0.75f }
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun colorRow(title: String, subtitle: String?, color: Int, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(View(this).apply { background = ColorPickerView.swatch(color, dp(6).toFloat()) },
            LinearLayout.LayoutParams(dp(40), dp(28)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 16f })
        if (subtitle != null) texts.addView(TextView(this).apply { text = subtitle; textSize = 12f; alpha = 0.7f })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        content.addView(row)
    }

    private fun slider(title: String, value: Int, onSet: (Int) -> Unit) {
        val label = TextView(this).apply { text = "$title: $value%"; setPadding(0, dp(10), 0, 0) }
        content.addView(label)
        content.addView(SeekBar(this).apply {
            max = 100
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    label.text = "$title: $p%"
                    onSet(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) = changed(rerender = false)
            })
        })
    }

    private fun toggle(title: String, value: Boolean, onSet: (Boolean) -> Unit) {
        content.addView(Switch(this).apply {
            text = title
            isChecked = value
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on -> onSet(on); changed(rerender = false) }
        })
    }

    private fun stepper(title: String, value: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
        var v = value
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(this).apply { text = "$title: $v"; textSize = 15f }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        fun btn(t: String, d: Int) = Button(this).apply {
            text = t
            setOnClickListener {
                v = (v + d).coerceIn(min, max)
                label.text = "$title: $v"
                onSet(v)
                changed(rerender = false)
            }
        }
        row.addView(btn("−", -1), LinearLayout.LayoutParams(dp(56), -2))
        row.addView(btn("+", 1), LinearLayout.LayoutParams(dp(56), -2))
        content.addView(row)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
