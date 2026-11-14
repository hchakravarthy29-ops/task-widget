# Handoff for Claude Code: Task Widget

Owner: Harshith (Galaxy S24 Ultra, Android 14+). Not a programmer: explain in plain words, no jargon.
First written 14 Nov 2026 by Claude in chat; updated 14 Nov 2026 by Claude Code (see "Status").

## Status (14 Nov 2026)

- Builds and unit tests pass in GitHub Actions on every push. There is no Android SDK on Harshith's PC,
  so CI is the compiler: push to a temporary branch, check the run, then push `main` and delete the branch.
- Each `main` build is published to the rolling release `latest-build`. Phone download link:
  https://github.com/hchakravarthy29-ops/task-widget/releases/latest/download/app-debug.apk
- Builds are signed with the committed `debug.keystore` (on purpose, not secret) so updates install over the top.
- **Nothing has run on a real device yet.** Harshith is about to install and follow `TESTING.md`
  (day 1 checks, then day 2 and day 3 mornings). Next session: ask how testing went and fix what failed.
- Local copy: `D:\Task widget\task-widget`.

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
- `TaskWidget.kt`: Glance 1.1.0 widget and receiver. Always refresh with `refreshWidgets(context)`,
  not `updateAll`: it bumps a state key the composition reads, so a still-running Glance session
  reloads the calendar instead of only recomposing stale data.
- `ActionActivity.kt`: translucent activity for the action dialogs and the Done + Undo popup
  (`EXTRA_DONE_NOW`, from the widget's circle).
- `MainActivity.kt`: permissions and a plain status and list screen (shows the tracking start day).
- `Reminders.kt`: also `BootReceiver`; reminders are saved in SharedPreferences and re-set after
  reboot or app update. A reminder whose task is done or deleted is skipped.
- Tests: `app/src/test/kotlin/...` (`TitlesTest`, `RulesTest`, `MeetingTest`). Run in CI via `./gradlew testDebugUnitTest`.
- `.github/workflows/build.yml`: tests, `assembleDebug`, artifact upload, and the `latest-build` release on `main`.

Stack: AGP 8.5.2, Kotlin 1.9.24, Compose compiler 1.5.14, compileSdk and targetSdk 34, minSdk 26, Glance 1.1.0, Gradle wrapper 8.7.

## Decisions added with Harshith (14 Nov)

- Only events that end on or after the first day the app synced are tracked (`Repo.trackingSince`),
  so installing doesn't turn old events into DELAYED tasks.
- Meetings are left out completely: someone else organised it, or a guest other than you is invited
  (rooms don't count). `Rules.isMeeting`; "you" = calendar owner address or its Google account.
- Done shows a 6-second "Marked done" popup with Undo (restores title and end time).
- Status parsing accepts U+FE0F after the emoji and ⏳ as well as ⌛.

## Unverified on a device (check during or after Harshith's testing)

1. Glance refresh (`refreshWidgets` with `currentState` + `produceState`) actually redraws right
   after Done/Undo. If it doesn't, the task stays on the widget until the next 30-minute refresh.
2. All-day to timed conversion (Later today, Reschedule with a time) is accepted by the provider.
3. Meeting detection against real Google data (ORGANIZER / OWNER_ACCOUNT / attendee rows).
4. Reminders after a reboot (`BootReceiver`), and how late `setAndAllowWhileIdle` fires on Samsung.
5. The "Open in Google Calendar" intent (`content://com.android.calendar/events/<id>`).

## Questions answered by Harshith (after the subagent review)

1. Answered: "Later today" keeps a delayed or follow-up tag and reminder; Reschedule clears them.
2. Answered: moving a multi-day task keeps all its days (stretched delayed/follow-up blocks move as one day).
3. Answered (15 Nov): keep using every owned calendar, including the shared WJC Deadlines calendar (team sees the tags).

## Incident 15 Nov: runaway DELAYED count (fixed in 3e0313f)
A late all-day WJC event went to "DELAYED +33" within minutes on the phone: each refresh re-stretched it
because Instances lagged the Events table after our write. Same lag made the widget need repeated refreshes
after Done, Later today and Reschedule. Fix: one-off events read title and times from Events, `sync` re-reads
before writing, stretches each event at most once per day, and is synchronized. The event was restored by hand.

## Small known rough edges (not fixed)

- The Reschedule time dialog title shows the date as ISO (`2026-11-15`).
- A picked reminder time earlier today fires straight away (dates can't be in the past).
- `POST_NOTIFICATIONS` is requested from `MainActivity` only.

## Next steps

1. Fix whatever Harshith reports from `TESTING.md`.
2. Then: a style round for the widget, and a full-screen list in `MainActivity`.

## Out of scope for you

Claude in chat handles the Claude side of the loop (reading these events, marking things done when Harshith says so, asking about follow-ups). Do not build a backend or add Google sign-in.
