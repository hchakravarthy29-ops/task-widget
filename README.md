# Task Widget

Android home-screen widget that shows today's Google Calendar tasks, with a progress bar and quick actions.
It reads and writes the phone's own calendar (no Google sign-in), so changes sync to Google Calendar and to Claude.

## Status tags (in the event title)

- `✅ DONE – ` finished. Hidden from the list, counted in progress.
- `⌛ DELAYED +N – ` not acted on by the end of its day. The block stretches from its original day through today.
- `🔁 FOLLOW-UP – ` partly done. Stays on the list every day until finished, with an optional note.

Meetings (events with other guests, or invitations from other people) are left off the widget and never changed.

## Actions (tap a task)

Done, Later today, Reschedule (moves the block), Partly done. The circle on the left marks done in one tap, with an Undo popup for a few seconds.
The calendar icon opens the event in Google Calendar.

## Install

To check everything works after installing, follow [TESTING.md](TESTING.md).
New collaborator? Start with [BRIEF.md](BRIEF.md).

1. On the phone, open https://github.com/hchakravarthy29-ops/task-widget/releases/latest/download/app-debug.apk
   in Chrome. It downloads the newest build directly.
2. Tap the download and install it (allow installs from Chrome if asked).
3. Open the app once and allow calendar and notification access.
4. Long-press the home screen, Widgets, Task Widget.

To update later, open the same link and install over the top. Your settings are kept.
