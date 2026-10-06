package com.shiftcal.widget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The app's main screen: a month calendar. Tap a day to see, edit and add to it. */
class CalendarActivity : Activity() {

    private var month: YearMonth = YearMonth.now()
    private var darkApplied = false
    private lateinit var title: TextView
    private lateinit var weekdays: LinearLayout
    private lateinit var grid: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        darkApplied = AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        actionBar?.hide()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            // Android 15+ draws edge-to-edge; keep clear of the status/nav bars.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(6), bars.top + dp(6), dp(6), bars.bottom + dp(6))
                insets
            }
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(headerButton("‹") { month = month.minusMonths(1); render() })
        title = TextView(this).apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener { month = YearMonth.now(); render() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(headerButton("›") { month = month.plusMonths(1); render() })
        header.addView(headerButton("⟳") { syncNow() })
        header.addView(headerButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        weekdays = LinearLayout(this)
        root.addView(weekdays, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        if (!CalendarRepo.hasPermission(this) || !CalendarRepo.canWrite(this)) requestCalendar()
        handleOpenDay(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenDay(intent)
    }

    /** Redraw when calendar data changes (e.g. a sync brings new events), at most every half second. */
    private val handler = Handler(Looper.getMainLooper())
    private val redraw = Runnable { if (!isFinishing) render() }
    private val calendarObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(redraw)
            handler.postDelayed(redraw, 500)
        }
    }

    override fun onStart() {
        super.onStart()
        if (CalendarRepo.hasPermission(this))
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
    }

    override fun onStop() {
        super.onStop()
        contentResolver.unregisterContentObserver(calendarObserver)
    }

    private fun syncNow() {
        val n = CalendarRepo.requestSync(this)
        toast(if (n == 0) "No synced calendar accounts on this phone"
              else "Syncing… new events will appear in a moment")
    }

    override fun onResume() {
        super.onResume()
        // Theme changed in settings (or the phone's dark mode changed) → rebuild with the new theme.
        if (AppTheme.isDark(this) != darkApplied) {
            recreate()
            return
        }
        render()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        BaseWidgetProvider.updateAll(this)
        BaseWidgetProvider.scheduleRefreshes(this)
        render()
    }

    private fun requestCalendar() =
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1)

    /** Opened from the Upcoming widget on a particular day. */
    private fun handleOpenDay(intent: Intent?) {
        val day = intent?.getLongExtra(EXTRA_DAY, Long.MIN_VALUE) ?: return
        if (day == Long.MIN_VALUE) return
        intent.removeExtra(EXTRA_DAY)
        val date = LocalDate.ofEpochDay(day)
        month = YearMonth.from(date)
        render()
        showDay(date)
    }

    /** Calendar data changed: redraw here and on the home-screen widgets. */
    private fun changed() {
        render()
        BaseWidgetProvider.updateAll(this)
    }

    // ------------------------------------------------------------- month grid

    private fun render() {
        title.text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))

        val firstDow = if (Prefs.weekStartsMonday(this)) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        weekdays.removeAllViews()
        for (i in 0 until 7) {
            weekdays.addView(TextView(this).apply {
                text = firstDow.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                gravity = Gravity.CENTER
                textSize = 12f
                alpha = 0.7f
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val today = LocalDate.now()
        val gridStart = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDow))
        val events = CalendarRepo.eventsByDay(this, gridStart, gridStart.plusDays(42))
        val todayColor = Prefs.color(this, StyleColor.TODAY)

        grid.removeAllViews()
        for (w in 0 until 6) {
            val row = LinearLayout(this)
            for (d in 0 until 7) {
                val date = gridStart.plusDays((w * 7 + d).toLong())
                row.addView(dayCell(date, events[date].orEmpty(), date == today, todayColor),
                    LinearLayout.LayoutParams(0, -1, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun dayCell(date: LocalDate, events: List<DayEvent>, isToday: Boolean, todayColor: Int): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(2))
            if (YearMonth.from(date) != month) alpha = 0.4f
            if (isToday) background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setStroke(dp(2), todayColor)
            }
            setOnClickListener { showDay(date) }
        }
        cell.addView(TextView(this).apply {
            text = date.dayOfMonth.toString()
            gravity = Gravity.CENTER_HORIZONTAL
            textSize = 13f
            if (isToday) setTypeface(typeface, Typeface.BOLD)
        })
        val maxChips = 3
        events.take(maxChips).forEach { ev ->
            val color = WidgetRenderer.shiftColor(this, ev)
            cell.addView(TextView(this).apply {
                text = Prefs.shiftLabel(this@CalendarActivity, ev.title) ?: ev.title
                textSize = 10f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(Contrast.bestText(color))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(1), dp(2), dp(1))
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(color) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        if (events.size > maxChips) cell.addView(TextView(this).apply {
            text = "+${events.size - maxChips}"
            textSize = 10f
            gravity = Gravity.CENTER
        })
        return cell
    }

    // -------------------------------------------------------------- day panel

    private fun showDay(date: LocalDate) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        lateinit var dialog: AlertDialog

        // Everything on this day. Shifts are locked; other events can be edited or removed.
        val events = CalendarRepo.eventsByDay(this, date, date.plusDays(1))[date].orEmpty()
        if (events.isEmpty()) box.addView(TextView(this).apply { text = "Nothing on this day"; alpha = 0.6f })
        events.forEach { ev ->
            val isShift = Prefs.isShift(this, ev.title)
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
            }
            row.addView(dot(WidgetRenderer.shiftColor(this, ev)), LinearLayout.LayoutParams(dp(12), dp(12)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = ev.title; textSize = 16f })
            val sub = buildList {
                add(WidgetRenderer.timeRange(this@CalendarActivity, ev, " - "))
                when {
                    isShift -> add("shift")
                    ev.repeating -> add("repeats – edit in Google Calendar")
                    !ev.editable -> add("read-only calendar")
                }
            }.joinToString(" · ")
            texts.addView(TextView(this).apply { text = sub; textSize = 13f; alpha = 0.65f })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })

            if (!isShift && ev.editable) {
                row.setOnClickListener { dialog.dismiss(); editEvent(date, ev) }
                row.addView(iconButton("✎") { dialog.dismiss(); editEvent(date, ev) })
                row.addView(iconButton("🗑") { confirmDelete(ev) { dialog.dismiss(); showDay(date) } })
            }
            box.addView(row)
        }

        // Add a new note.
        box.addView(TextView(this).apply {
            text = "New note"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, 0)
        })
        val form = noteForm(CalendarRepo.noteCalendar(this), "", null, null, rangeFrom = date)
        box.addView(form.view)

        dialog = AlertDialog.Builder(this)
            .setTitle(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Add note") { _, _ -> addNote(date, form) }
            .setNegativeButton("Close", null)
            .show()
        form.bindSaveButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
    }

    private fun editEvent(date: LocalDate, ev: DayEvent) {
        val cal = CalendarRepo.calendars(this).firstOrNull { it.id == ev.calendarId }
        val startTime = if (ev.allDay) null
            else Instant.ofEpochMilli(ev.begin).atZone(ZoneId.systemDefault()).toLocalTime()
        val form = noteForm(cal, ev.title, startTime, ev.color)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(form.view)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val ok = CalendarRepo.updateEvent(this, ev, date, form.text(), form.time(), form.color())
                toast(if (ok) "Saved" else "Couldn't save the change")
                changed()
                showDay(date)
            }
            .setNeutralButton("Delete") { _, _ -> confirmDelete(ev) { showDay(date) } }
            .setNegativeButton("Cancel") { _, _ -> showDay(date) }
            .show()
        form.bindSaveButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
    }

    private fun confirmDelete(ev: DayEvent, after: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage("Delete \"${ev.title}\"? It will be removed from your calendar.")
            .setPositiveButton("Delete") { _, _ ->
                toast(if (CalendarRepo.deleteEvent(this, ev)) "Deleted" else "Couldn't delete it")
                changed()
                after()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNote(date: LocalDate, form: NoteForm) {
        if (!CalendarRepo.canWrite(this)) {
            toast("Allow calendar access to add notes")
            requestCalendar()
            return
        }
        val cal = CalendarRepo.addNote(this, date, form.text(), form.time(), form.color(), form.lastDay() ?: date)
        toast(when {
            cal == null -> "Couldn't save – no calendar that can be edited was found"
            !cal.isGoogle -> "Added to ${cal.name} (phone only, not on Google)"
            !cal.syncing -> "Added to ${cal.name} (not syncing – see Settings)"
            else -> "Added to ${cal.name} · ${cal.account}"
        })
        changed()
    }

    // -------------------------------------------------------- note/edit form

    /** Text + time (untouched = all day) + colour. Shared by "new note" and "edit". */
    private inner class NoteForm(
        val view: View,
        private val edit: EditText,
        val time: () -> LocalTime?,
        val color: () -> EventColor?,
        /** Last day of a multi-day note, or null for a single day. */
        val lastDay: () -> LocalDate? = { null },
    ) {
        fun text() = edit.text.toString().trim()

        /** Only allow saving when there's some text. */
        fun bindSaveButton(b: Button) {
            b.isEnabled = text().isNotEmpty()
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { b.isEnabled = !s.isNullOrBlank() }
            })
        }
    }

    /** [rangeFrom]: the note's first day; when given, the form also offers an "until" day for multi-day notes. */
    private fun noteForm(
        cal: CalendarInfo?, initialText: String, initialTime: LocalTime?, initialColor: Int?, rangeFrom: LocalDate? = null,
    ): NoteForm {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val edit = EditText(this).apply {
            hint = "Note"
            setText(initialText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(edit)

        // Time: "All day" until a time is set with the wheels; ✕ goes back to all day.
        var time: LocalTime? = initialTime
        val timeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val timeBtn = Button(this).apply { isAllCaps = false }
        val clear = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val wheel = TimeWheel(this, initialTime ?: LocalTime.of(9, 0)) { t ->
            time = t
            timeBtn.text = "At " + formatTime(this, t)
        }
        fun showTime() {
            val t = time
            timeBtn.text = if (t == null) "All day" else "At " + formatTime(this, t)
            clear.visibility = if (t == null) View.GONE else View.VISIBLE
            wheel.visibility = if (t == null) View.GONE else View.VISIBLE
        }
        timeBtn.setOnClickListener {
            if (time == null) time = wheel.time
            showTime()
        }
        clear.setOnClickListener { time = null; showTime() }
        timeRow.addView(timeBtn)
        timeRow.addView(clear)
        box.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        box.addView(wheel)
        showTime()

        // Days: one day, or "until" a later day → one multi-day event.
        var lastDay: LocalDate? = null
        if (rangeFrom != null) {
            val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
            val rangeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val rangeBtn = Button(this).apply { isAllCaps = false }
            val rangeClear = TextView(this).apply {
                text = "✕"
                textSize = 18f
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            fun showRange() {
                val l = lastDay
                rangeBtn.text = if (l == null) "One day" else
                    "Until ${l.format(dayFmt)} · ${java.time.temporal.ChronoUnit.DAYS.between(rangeFrom, l) + 1} days"
                rangeClear.visibility = if (l == null) View.GONE else View.VISIBLE
            }
            rangeBtn.setOnClickListener {
                val shown = lastDay ?: rangeFrom.plusDays(1)
                DatePickerDialog(this, { _, y, m, d ->
                    val picked = LocalDate.of(y, m + 1, d)
                    lastDay = if (picked.isAfter(rangeFrom)) picked else null
                    showRange()
                }, shown.year, shown.monthValue - 1, shown.dayOfMonth).apply {
                    setTitle("Last day")
                    datePicker.minDate = rangeFrom.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.show()
            }
            rangeClear.setOnClickListener { lastDay = null; showRange() }
            rangeRow.addView(rangeBtn)
            rangeRow.addView(rangeClear)
            box.addView(rangeRow)
            showRange()
        }

        // Colour: the calendar's default, or one of its event colours.
        val colourLabel = TextView(this).apply { textSize = 13f; alpha = 0.7f; setPadding(0, dp(10), 0, dp(2)) }
        box.addView(colourLabel)
        val palette = CalendarRepo.eventColors(this, cal)
        var selected: EventColor? = palette.firstOrNull { it.color == initialColor }
        val swatches = ArrayList<Pair<EventColor?, View>>()
        val ring = TextView(this).currentTextColor
        fun refreshSwatches() {
            colourLabel.text = "Colour · " + (selected?.let { googleColourName(it) } ?: "Calendar default")
            swatches.forEach { (col, v) ->
                (v.background as GradientDrawable).setStroke(if (col == selected) dp(3) else dp(1),
                    if (col == selected) ring else 0x55888888)
            }
        }
        val options: List<EventColor?> = listOf<EventColor?>(null) + palette
        options.chunked(6).forEach { rowColors ->
            val row = LinearLayout(this)
            rowColors.forEach { col ->
                val v = TextView(this).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(col?.color ?: (cal?.color ?: 0xFF888888.toInt()))
                    }
                    if (col == null) {
                        text = "A"   // automatic: the calendar's own colour
                        setTextColor(Contrast.bestText(cal?.color ?: 0xFF888888.toInt()))
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    setOnClickListener { selected = col; refreshSwatches() }
                }
                swatches += col to v
                row.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            box.addView(row)
        }
        refreshSwatches()

        return NoteForm(box, edit, { time }, { selected }, { lastDay })
    }

    // ---------------------------------------------------------------- helpers

    private fun headerButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 24f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(dp(44), -1) }

    private fun iconButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun dot(color: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    /** Google Calendar's name for one of its 11 event colours (by Google's colour id, else by value). */
    private fun googleColourName(col: EventColor): String {
        val byKey = mapOf(
            "1" to "Lavender", "2" to "Sage", "3" to "Grape", "4" to "Flamingo", "5" to "Banana", "6" to "Tangerine",
            "7" to "Peacock", "8" to "Graphite", "9" to "Blueberry", "10" to "Basil", "11" to "Tomato",
        )
        val byValue = mapOf(
            0xFF7986CB.toInt() to "Lavender", 0xFF33B679.toInt() to "Sage", 0xFF8E24AA.toInt() to "Grape",
            0xFFE67C73.toInt() to "Flamingo", 0xFFF6BF26.toInt() to "Banana", 0xFFF4511E.toInt() to "Tangerine",
            0xFF039BE5.toInt() to "Peacock", 0xFF616161.toInt() to "Graphite", 0xFF3F51B5.toInt() to "Blueberry",
            0xFF0B8043.toInt() to "Basil", 0xFFD50000.toInt() to "Tomato",
        )
        return col.key?.let { byKey[it] } ?: byValue[col.color] ?: "Custom"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun formatTime(c: Context, t: LocalTime): String =
        t.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mma", Locale.getDefault()))
            .lowercase()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DAY = "open_day"

        /** Intent that opens the app on [date] with its day panel showing. */
        fun openDayIntent(c: Context, date: LocalDate): Intent =
            Intent(c, CalendarActivity::class.java)
                .putExtra(EXTRA_DAY, date.toEpochDay())
                .setData(android.net.Uri.parse("shiftwidget://open/${date.toEpochDay()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
