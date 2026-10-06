# Shift Widget

A free, open-source Android calendar app and home-screen widget for shift workers, built on top of your existing Google Calendar.

## Why this exists

I used **MyShiftPlanner** for its home-screen widget, but it was a poor fit:

- **It isn't a real calendar app.** Shifts live in their own separate world instead of in your actual calendar. Nothing you add there shows up in Google Calendar, and your other appointments don't sit next to your shifts.
- **You have to pay for everything.** Widget colours, transparency, layouts: almost every useful option is locked behind a purchase or subscription.

My shifts were already in Google Calendar, with their own colours. All I wanted was a nice widget that shows them, plus a quick way to jot notes onto days. So I built this. It is a small, normal calendar app that reads and writes the calendars already on your phone, with the widget I wanted. All features are free.

## Features

### Widgets
- **Upcoming:** an agenda list of the next 7–62 days that you can scroll. Each day has a block coloured by that day's shift (Day / Night etc.), the shift name and times, and any other events. Days with no shift show a black **OFF** block. The list resets to today each day.
- **Month:** a full month grid with ‹ › navigation. Tap a day to make the Upcoming widget jump to that date. Tap it again to go back to today.
- Adjustable background and header **transparency**, custom colours, and optional **automatic text colours** that pick readable text using WCAG contrast.
- Adjustable divider thickness, week start (Monday/Sunday), and an option to show times on the month grid.

### App
- Month calendar view. Tap a day to see everything on it.
- **Add a note** to any day:
  - Leave the time alone and it's an all-day event, or set a time with a scroll-wheel picker (no clock dial).
  - Pick an end date to make one **multi-day event** (e.g. a holiday).
  - Pick a colour from Google Calendar's own event colours (Tomato, Banana, Peacock…), so the colour syncs to Google too.
- **Edit or delete** your events from the day view. Shifts are protected from accidental edits.
- **Shift colours & labels:** override a shift's colour, or give it a short label (e.g. "Day Shift" → "Day").
- Show or hide individual calendars. Spot calendars that aren't syncing to the phone and turn them on. "Sync now" button.
- Light, dark (true **AMOLED black**) or follow-system theme.

## How it works

- **No Google sign-in or API keys.** The app uses Android's built-in calendar database ([`CalendarContract`](https://developer.android.com/reference/android/provider/CalendarContract)). Any account synced to your phone (Google, Samsung, Outlook…) is already there. The app only asks for calendar permission.
- **Reading:** events are read from the phone's calendar database. Widgets redraw automatically when the calendar changes, at midnight, and every 30 minutes as a backup.
- **Writing:** notes are inserted into the calendar you choose (by default your main Google calendar). Android's own sync then uploads them to Google Calendar, and the app asks for that sync straight away.
- **Shifts** are recognised by title (any event containing "shift" by default; you can mark others as shifts in Settings). The shift's colour comes from Google Calendar unless you override it in the app. In-app overrides change only how the app shows the shift; they don't change Google Calendar.
- Calendars added to Google Calendar **"from URL"** are refreshed by Google's servers every few hours. "Sync now" fetches whatever Google already has.

## Install

1. Download `ShiftWidget.apk` from the [Releases](../../releases) page onto your phone.
2. Open it and allow installing from that source when Android asks.
3. Open **Shift Widget** and allow calendar access.
4. Add the widgets: long-press the home screen → **Widgets** → **Shift Widget**.

Requires Android 8.0 or newer.

## Building from source

Requires Android Studio (or JDK 17+ and the Android SDK).

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

- Kotlin, no third-party dependencies (not even AndroidX).
- `minSdk` 26, `targetSdk` 36.

### Code layout (`app/src/main/java/com/shiftcal/widget/`)

| File | What it does |
|---|---|
| `CalendarRepo.kt` | Reads and writes events and calendars through `CalendarContract`, and requests syncs |
| `WidgetRenderer.kt` | Builds the month and upcoming widget `RemoteViews` |
| `WidgetProviders.kt` | Widget providers: taps, refreshes, midnight alarm |
| `CalendarChangeJob.kt` | Redraws widgets when the calendar database changes |
| `CalendarActivity.kt` | The calendar app: month view, day view, add/edit notes |
| `SettingsActivity.kt` | Colours, options, shift labels, calendar visibility |
| `Prefs.kt` | Saved settings |
| `Contrast.kt` | WCAG contrast maths for automatic text colours |
| `TimeWheel.kt`, `ColorPickerView.kt` | Scroll-wheel time picker and colour picker |
