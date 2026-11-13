# Task Widget

Android home-screen widget that shows today's Google Calendar tasks, with a progress bar and quick actions.
It reads and writes the phone's own calendar (no Google sign-in), so changes sync to Google Calendar and to Claude.

## Status tags (in the event title)

- `✅ DONE – ` finished. Hidden from the list, counted in progress.
- `⌛ DELAYED +N – ` not acted on by the end of its day. The block stretches from its original day through today.
- `🔁 FOLLOW-UP – ` partly done. Stays on the list every day until finished, with an optional note.

## Actions (tap a task)

Done, Later today, Reschedule (moves the block), Partly done. The circle on the left marks done in one tap, with an Undo popup for a few seconds.
The calendar icon opens the event in Google Calendar.

## Install

To check everything works after installing, follow [TESTING.md](TESTING.md).

1. Open the latest run under the Actions tab and download the `task-widget-debug-apk` artifact.
2. Unzip, copy `app-debug.apk` to the phone, and install it (allow installs from your file manager if asked).
3. Open the app once and allow calendar and notification access.
4. Long-press the home screen, Widgets, Task Widget.
