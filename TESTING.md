# Testing Task Widget on your phone

No coding needed. You make a few test events in Google Calendar, tap things, and check that
what you see matches this list. It takes about 15 minutes on day 1, then 2 minutes on each of
the next two mornings (the "delayed" feature only shows up when a day passes).

The app only touches events from the day you first open it. Older events are left alone.
Meetings (events with other guests, or invitations from others) never show up and are never changed.

## 1. Install (once)

1. On your phone, open github.com/hchakravarthy29-ops/task-widget and sign in.
2. Tap **Actions**, open the newest run with a green tick, scroll to **Artifacts**, and download
   **task-widget-debug-apk**.
3. Open the downloaded zip and tap **app-debug.apk**.
   - If the phone says it can't install apps from this source, tap **Settings**, turn on
     **Allow from this source**, and go back.
   - If Play Protect warns about an unknown app, tap **More details**, then **Install anyway**.
     The warning shows because the app isn't from the Play Store; it's your own app.
4. Open **Task Widget** and tap **Allow calendar and notifications**. Allow both.
   The screen should say **Tracking tasks from** today's date.
5. Long-press the home screen, tap **Widgets**, find **Task Widget**, and drag it onto the screen.

## 2. Make test events (day 1)

In Google Calendar, make these on **today**, in your own calendar:

| Name | When |
|---|---|
| Test A | an hour from now, 30 min |
| Test B | all day |
| Test C | any time today |
| Test D | any time today |
| Test E | any time today |
| Test Late | any time today. **Leave this one alone for the whole test.** |
| Test Repeat | any time today, set to repeat daily |

Then open the Task Widget app once (that refreshes the widget straight away; otherwise it
refreshes by itself every 30 minutes).

## 3. Day 1 checks

Tick each one off, and note any that don't match.

- [ ] **List:** all 7 show. The all-day one (B) is at the top, the rest are in time order.
      The top line says **0 of 7 done** (or close to it).
- [ ] **Done:** tap the coloured circle next to **A**. A **Marked done** popup appears.
      Leave it; it closes by itself after about 6 seconds. A disappears from the widget, the counter
      goes up by one, and in Google Calendar the title is now **✅ DONE – Test A**.
- [ ] **Undo:** tap the circle next to **B**, then tap **Undo** in the popup. B comes back, and its
      title in Google Calendar is unchanged.
- [ ] **Later today:** tap the *name* **C**, then **Later today**, then **In 1 hour**. In Google
      Calendar, C has moved to an hour from now.
- [ ] **Reschedule:** tap **D**, **Reschedule**, **Tomorrow**, **Keep the time**. D leaves today's
      widget and appears tomorrow in Google Calendar, title unchanged.
- [ ] **Partly done:** tap **E**, **Partly done**, type "call back", tap **Next**, choose
      **Pick a date and time**, and pick today, 3 minutes from now.
      E now shows in amber as **Follow up · call back**, and its Google Calendar title starts with
      **🔁 FOLLOW-UP**. About 3 minutes later a **Follow up** notification arrives. Tapping it
      opens the action menu for E.
- [ ] **Reminder survives a restart:** make one more event, **Test F**, today. Tap it, **Partly done**, **Next**, **Pick a date and time**, today,
      5 minutes from now. Then restart the phone straight away. The **Follow up** notification
      for F still arrives (within a few minutes of the time you picked).
- [ ] **No reminder for finished tasks:** make **Test G** today, give it a Partly done reminder
      5 minutes away, then mark G done with the circle and let the popup close. No notification
      for G arrives.
- [ ] **Open in Calendar:** tap the small calendar icon on any row. Google Calendar opens on that event.
- [ ] **Repeating:** tap the circle, and then the name, of **Test Repeat**. Both times a message
      says repeating events can only be changed in Google Calendar, and nothing changes.
- [ ] **Hand-typed status:** in Google Calendar, rename **B** to `✅ DONE – Test B` yourself
      (copy the tick from Test A). Open the app. B disappears from the widget and counts as done.
      Then rename it back to `Test B`.
- [ ] **Meetings stay off:** make **Test Meeting** today and add a guest (any friend's email).
      When Google asks about sending invitations, tap **Don't send**. Open the app.
      Test Meeting does not appear on the widget, and neither do any real meetings you have today.
- [ ] **Old events:** open any event from last week in Google Calendar. Its title and time are
      unchanged.

## 4. Day 2 morning

Open the Task Widget app once to refresh.

- [ ] **Test Late** is at the top in red: **Delayed +1**. In Google Calendar it now runs from
      yesterday through today, titled **⌛ DELAYED +1 – Test Late**.
- [ ] **E** is still amber **Follow up**, *not* red delayed. In Google Calendar it stretches into today.
- [ ] **A** (done yesterday) does not come back.
- [ ] **Test Meeting** in Google Calendar is unchanged: same title, still only yesterday.
- [ ] **D** shows today at its usual time, as a normal task.
- [ ] Mark **E** done with the circle. It disappears; in Google Calendar its block ends today.

## 5. Day 3 morning

- [ ] **Test Late** now says **Delayed +2**, and its block runs from day 1 through today.
- [ ] Mark it done. It disappears and the counter goes up.

## 6. Tidy up

Delete the test events in Google Calendar.

## Reporting back

Paste the list into chat with the ones that didn't match, plus a screenshot of the widget and of
the event in Google Calendar if you can. "Step X didn't do Y, it did Z instead" is all that's needed.

Known gaps you may notice:

- Reminders can arrive a few minutes late; Android groups them to save battery.
- The look is a placeholder; styling comes later.
