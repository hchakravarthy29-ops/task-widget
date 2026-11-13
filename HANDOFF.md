# Handoff for Claude Code: Task Widget

Owner: Harshith (Galaxy S24 Ultra, Android 14+). Written 14 Nov 2026 by Claude in chat. The code in this repo has never been compiled or run. Your first job is to make it build and then verify behaviour.

Do not use APEX or multi-agent orchestration. Keep the work direct and proportionate.

## Goal

A dark Android home-screen widget that shows today's Google Calendar tasks, with a progress bar and quick actions. It forms a loop between Harshith, the app, Google Calendar and Claude (in chat). Calendar events are the only shared state. There is no backend and no Google sign-in: the app reads and writes the phone's own calendar through `CalendarContract`, which Google Calendar syncs to the cloud. Claude in chat reads and writes the same events through Calendar tools.

## Decisions already made with Harshith (do not reopen)

- Show everything on the calendar for the day, from calendars where the user is owner (access >= 700) and visible. Skip declined events.
- Show today plus anything undone from earlier days. Done tasks are hidden but counted in the progress bar.
- Status lives in the event title as a prefix:
  - `✅ DONE – `
  - `⌛ DELAYED +N – ` (N = days late)
  - `🔁 FOLLOW-UP – `
  The dash is an en dash with spaces. No colour changes: calendar colours are the user's category colours.
- Delayed: never user-triggered. An undone task whose end day is before today is stretched automatically. Its start stays; its end moves to today (all-day: today+1 00:00 UTC; timed: today at the original end time of day). The prefix becomes `⌛ DELAYED +N` and N grows each day. Done stops the stretch and ends the block on the completion day.
- Follow-up (button name "Partly done"): adds the prefix, an optional note stored as a first line in the description (`Follow-up note: ...`), and an optional phone notification via AlarmManager. It carries over every day (the block stretches) but is not delayed, and stays visible.
- Four actions: Done, Later today (snooze: moves the time within today), Reschedule (moves the block; Tomorrow, this weekend, next Monday, or a picked date and optional time; no delay tag), Partly done. Rescheduling strips any status prefix.
- List order: delayed (red, most late first), then today's tasks (all-day first, then by time), then follow-ups (amber).
- Row: coloured circle (calendar colour) = one-tap done, tap the text = action dialog, calendar icon = open the event in Google Calendar.
- Repeating events are shown but not modified (editing would change the whole series).
- Widgets cannot swipe, so swipe gestures from the first design were dropped.
- Style is a placeholder (dark). A style round with Harshith comes later.

## Layout

- `Model.kt`: title prefix parsing and building, `Item`, and the pure rules (`Rules.stretch`, ordering, active and done-today tests). Easy to unit test with JUnit; there are no tests yet. Add them.
- `Repo.kt`: all `CalendarContract` reads and writes, sync (stretch), and the four actions.
- `Reminders.kt`: AlarmManager scheduling, notification channel, `ReminderReceiver`.
- `TaskWidget.kt`: Glance 1.1.0 widget, `DoneAction` callback, receiver.
- `ActionActivity.kt`: translucent activity that shows the action dialogs.
- `MainActivity.kt`: permissions and a plain status and list screen.
- `.github/workflows/build.yml`: builds `assembleDebug` on every push and uploads the APK artifact. There is no Gradle wrapper yet. The workflow installs Gradle 8.7. Add the wrapper if you can generate it.

Stack: AGP 8.5.2, Kotlin 1.9.24, Compose compiler 1.5.14, compileSdk and targetSdk 34, minSdk 26, Glance 1.1.0.

## Known risks to check first

1. Compile errors. Glance API signatures were written from memory (`LinearProgressIndicator`, `LazyColumn` `items(itemId = ...)`, `ColorProvider(Color)`, `ColorFilter.tint`, `cornerRadius`, `actionRunCallback`). Fix whatever the compiler reports.
2. The widget's `initialLayout` is `@layout/glance_default_loading_layout`, which ships with Glance. Confirm it resolves.
3. `Repo.sync` runs inside `provideGlance` on every widget update. Confirm it does not loop: updating events fires calendar change notifications, but nothing observes them. Updates come from the 30-minute `updatePeriodMillis`, the app opening and action callbacks.
4. Stretch maths for timed events, DST and all-day UTC handling (`Item.dayOf`, `Rules.endThrough`). Write unit tests for: a task one day late, three days late, a delayed task then done, a follow-up carrying over, all-day vs timed, and reschedule.
5. Converting an all-day event to timed (Later today and Reschedule with a time) sets `ALL_DAY=0`, `EVENT_TIMEZONE` and `DTSTART`/`DTEND`. Check that the provider accepts the update, and add the alert reminder only when it does.
6. The "Mark done" circle is easy to tap by accident. Consider an undo (title edit back) if it proves a problem.
7. Alarms set with `setAndAllowWhileIdle` do not survive a reboot. A boot receiver could reschedule from follow-up events.
8. `POST_NOTIFICATIONS` is requested from `MainActivity` only.

## Next steps

1. Get `gradle assembleDebug` passing. Fix compile errors with minimal changes.
2. Add JUnit tests for `Model.kt` rules and `Titles` parsing.
3. If an emulator or device is available, test with real calendar events: all-day, timed, delayed, follow-up, done.
4. Commit on `main` (this repo has no protected branches), push, and check the Actions run. Harshith installs the artifact `task-widget-debug-apk`.
5. Then, if time allows: widget look and feel, a boot receiver for reminders, an undo for done, and a full-screen list in `MainActivity`.

## Out of scope for you

Claude in chat handles the Claude side of the loop (reading these events, marking things done when Harshith says so, asking about follow-ups). Do not build a backend or add Google sign-in.
