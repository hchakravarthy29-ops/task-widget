# Task Widget: brief for a new collaborator (or their Claude)

Repo: https://github.com/hchakravarthy29-ops/task-widget (public). Status as of 15 Nov 2026.
The owner is not a programmer, so explain things to them in plain words. For full detail, read
`HANDOFF.md` (decisions and history), `TESTING.md` (the phone test checklist) and `README.md` after this.

## What the app is

An Android home-screen widget (built for a Galaxy S24 Ultra, Android 14) that shows **today's tasks from
Google Calendar**, with a "done of total" progress bar and quick actions. There is no backend and no
Google sign-in. It reads and writes the phone's own calendar through `CalendarContract`, which Google
Calendar syncs to the cloud. Calendar events are the only shared state: the owner's Claude (in chat) reads
and edits the same events, so **the status lives in the event title**:

- `✅ DONE – ` finished. Hidden from the list but counted in the progress bar.
- `⌛ DELAYED +N – ` not done by the end of its day. The block stretches from its original start through
  today, and N grows by one each day. This is automatic and never user-triggered.
- `🔁 FOLLOW-UP – ` partly done, with an optional note (`Follow-up note: …` as the first line of the
  description) and an optional phone reminder. It carries over daily but is not counted as delayed.

**Widget row:** coloured circle = mark done (shows a 6-second Undo popup); tap the text = an action menu
(Done, Later today, Reschedule, Partly done); calendar icon = open the event in Google Calendar.
**Order:** delayed first (most late first), then today's tasks (all-day first, then by time), then follow-ups.

## Rules already agreed with the owner (don't reopen)

- **What counts:** events from every calendar the user owns, including a shared team calendar (the team
  sees the tags).
- **Meetings are skipped:** events someone else organised, or with any guest other than the user. These
  are never shown or changed. Rooms don't count as guests.
- **Declined events, and calendars the user doesn't own,** are left out.
- **Repeating events** are shown but never modified.
- **Install-day cutoff:** only events ending on or after the day the app was first used are tracked, so
  old events are never rewritten.
- **Later today** moves the task within today and keeps a DELAYED or FOLLOW-UP tag and its reminder.
  **Reschedule** moves it to another day and clears the tag and reminder.
- **Moving a task the user made several days long** keeps all its days. A block that's only long
  because the app stretched it moves as a single day.
- **No colour changes:** calendar colours are the user's own categories. The visual style is a
  placeholder; a style round comes later.

## Code map (Kotlin, Glance 1.1.0, AGP 8.5.2, Kotlin 1.9.24, minSdk 26, target 34)

- `Model.kt`: title parsing and building, and all pure rules (`Rules.stretch`, `done`, `rescheduleSlot`,
  `isMeeting`, ordering). Unit tests live in `app/src/test/...`.
- `Repo.kt`: all `CalendarContract` reads and writes, the daily `sync` (stretch), and the actions.
- `TaskWidget.kt`: the Glance widget. Always refresh with `refreshWidgets()`, never `updateAll`.
- `ActionActivity.kt`: dialogs and the Done + Undo popup.
- `Reminders.kt`: alarms, notifications, and `BootReceiver` (restores reminders after a restart).
- `MainActivity.kt`: permissions and a status screen.

## How to build and ship

The owner's PC has no Android SDK, so **GitHub Actions is the compiler**. Every push runs the unit tests
and `assembleDebug`. A push to `main` also publishes the APK to the rolling release `latest-build`, and
the phone installs it from
`https://github.com/hchakravarthy29-ops/task-widget/releases/latest/download/app-debug.apk`.
Builds are signed with the committed `debug.keystore` (on purpose, not secret), so updates install over
the top. Workflow used so far: push to a temporary branch, check that the run passes, push `main`, delete
the branch.

## Current state and problems

- **Phone testing has just started** (see `TESTING.md`). Checks 1 to 6 pass: install, list, Done, Undo,
  Later today, Reschedule. Checks 7 to 21 are not done yet. Day 2 and day 3 checks (delayed counting)
  need real days to pass.
- **Fixed on 15 Nov, not yet confirmed on the phone:** after the app wrote to an event, the Instances
  table still showed the old values for a while.
  - The widget needed several refreshes before a change showed up.
  - The daily stretch re-ran on every refresh, so one real event reached `DELAYED +33` within minutes.
    It was restored by hand.
  - The fix: one-off events now read their title and times from the Events table; each event is
    stretched at most once per day; and `sync` is synchronized.
  - Ask the owner to confirm that changes now appear straight after a tap.
- **The first install-over-the-top update** (same signing key) hasn't been confirmed yet.
- **Still unverified on a real device:**
  - an all-day event converted to timed being accepted by the provider;
  - meeting detection against real Google data;
  - reminders surviving a reboot, and how late inexact alarms arrive on Samsung;
  - the "open in Google Calendar" intent.
- **Small rough edges:** the reschedule dialog shows dates as ISO (`2026-11-15`), and a reminder time
  picked earlier today fires straight away.
- **Later ideas:** a style round for the widget, and a full-screen task list in `MainActivity`.

## Guardrails

- **The app edits a real Google Calendar, including a shared team one.** Any change to `sync`, the
  stretch rules or the title prefixes can rewrite many real events. Check it with unit tests, and think
  about idempotence: running it twice in a row must change nothing.
- **No backend and no Google sign-in.** The chat side of the loop (Claude reading these events, marking
  tasks done) is handled separately.
- **Don't use multi-agent frameworks** for this repo. It's small; keep the work direct.
