package com.harshith.taskwidget

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

class ActionActivity : Activity() {
    companion object {
        const val EXTRA_EVENT_ID = "eventId"
        /** true: mark done at once and offer Undo (the widget's circle). */
        const val EXTRA_DONE_NOW = "doneNow"
        private const val UNDO_WINDOW_MS = 6000L
    }

    private var eventId = -1L
    private var item: Item? = null
    private val handler = Handler(Looper.getMainLooper())
    private var undoDialog: AlertDialog? = null
    /** Set while a done can still be undone; the follow-up reminder is cancelled once it can't. */
    private var doneBefore: Item? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        item = if (eventId >= 0 && Repo.hasPermission(this)) Repo.getItem(this, eventId) else null
        val task = item
        if (task == null) {
            Toast.makeText(this, "Open Task Widget and allow calendar access", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (task.recurring) {
            Toast.makeText(this, "Repeating events can only be changed in Google Calendar", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_DONE_NOW, false)) {
            if (task.parsed.kind == Kind.DONE) {
                // e.g. a second quick tap before the widget redrew.
                Toast.makeText(this, "Already marked done", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            doneWithUndo()
        } else {
            showMain(task)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        undoDialog?.dismiss()
        if (doneBefore != null) Reminders.cancel(applicationContext, eventId)
        super.onDestroy()
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    /** Marks done straight away, then shows a short-lived popup with Undo. */
    private fun doneWithUndo() {
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val before = try {
                withContext(Dispatchers.IO) { Repo.markDone(app, eventId) }.also { refreshWidgets(app) }
            } catch (e: Exception) {
                null
            }
            if (before == null) {
                Toast.makeText(app, "Couldn't mark it done", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            doneBefore = before
            if (isFinishing || isDestroyed) return@launch
            undoDialog = dialog()
                .setTitle("Marked done")
                .setMessage(before.parsed.base)
                .setPositiveButton("Undo") { _, _ -> undo(before) }
                .setNegativeButton("OK") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            handler.postDelayed({ if (!isFinishing) finish() }, UNDO_WINDOW_MS)
        }
    }

    private fun undo(before: Item) {
        doneBefore = null
        val app = applicationContext
        runSafely(app) { Repo.undoDone(app, before) }
        finish()
    }

    /** Runs a calendar change off the main thread, refreshes the widget, and never crashes the app. */
    private fun runSafely(app: Context, block: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
                refreshWidgets(app)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Couldn't update the calendar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showMain(task: Item) {
        if (task.parsed.kind == Kind.DONE) {
            // A stale tap from a widget that hadn't redrawn yet.
            Toast.makeText(this, "Already marked done", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val labels = arrayOf("Done", "Later today", "Reschedule", "Partly done")
        dialog()
            .setTitle(task.parsed.base)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> doneWithUndo()
                    1 -> laterToday()
                    2 -> reschedule()
                    3 -> partlyDone()
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Uses the application context: the activity finishes before the work completes. */
    private fun perform(block: (Context) -> Unit) {
        val app = applicationContext
        runSafely(app) { block(app) }
        finish()
    }

    private fun laterToday() {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val today = now.toLocalDate()
        // Only offer choices that still land today (late at night "in 3 hours" would be tomorrow).
        val options = listOf(
            "In 1 hour" to now.plusHours(1),
            "In 3 hours" to now.plusHours(3),
            "This evening (6 PM)" to today.atTime(18, 0),
        ).filter { (_, t) -> t.toLocalDate() == today && t.isAfter(now) }
        val labels = (options.map { it.first } + "Pick a time…").toTypedArray()
        dialog().setTitle("Later today").setItems(labels) { _, which ->
            if (which < options.size) moveTo(today, options[which].second.toLocalTime(), keepStatus = true)
            else pickTime { t -> moveTo(today, t, keepStatus = true) }
        }.setOnCancelListener { finish() }.show()
    }

    private fun reschedule() {
        val labels = arrayOf("Tomorrow", "This weekend (Saturday)", "Next Monday", "Pick a date…")
        dialog().setTitle("Reschedule").setItems(labels) { _, which ->
            val today = LocalDate.now()
            when (which) {
                0 -> askTime(today.plusDays(1))
                1 -> askTime(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)))
                2 -> askTime(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))
                else -> pickDate { d -> askTime(d) }
            }
        }.setOnCancelListener { finish() }.show()
    }

    private fun askTime(date: LocalDate) {
        val labels = arrayOf("Keep the time", "Pick a time…")
        dialog().setTitle(date.toString()).setItems(labels) { _, which ->
            if (which == 0) moveTo(date, null) else pickTime { t -> moveTo(date, t) }
        }.setOnCancelListener { finish() }.show()
    }

    private fun moveTo(date: LocalDate, time: LocalTime?, keepStatus: Boolean = false) {
        perform { app -> Repo.reschedule(app, eventId, date, time, keepStatus) }
    }

    private fun partlyDone() {
        val input = EditText(this).apply {
            hint = "What's pending? (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(48, 32, 48, 16)
        }
        dialog().setTitle("Partly done")
            .setView(input)
            .setPositiveButton("Next") { _, _ -> askReminder(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun askReminder(note: String) {
        val labels = arrayOf("No reminder", "In 3 hours", "Tomorrow 9 AM", "Pick a date and time…")
        dialog().setTitle("Remind me").setItems(labels) { _, which ->
            val now = LocalDateTime.now()
            when (which) {
                0 -> savePartly(note, null)
                1 -> savePartly(note, now.plusHours(3))
                2 -> savePartly(note, LocalDate.now().plusDays(1).atTime(9, 0))
                else -> pickDate { d -> pickTime { t -> savePartly(note, LocalDateTime.of(d, t)) } }
            }
        }.setOnCancelListener { finish() }.show()
    }

    private fun savePartly(note: String, at: LocalDateTime?) {
        val millis = at?.atZone(zone)?.toInstant()?.toEpochMilli()
        perform { app -> Repo.partlyDone(app, eventId, note, millis) }
    }

    private fun pickDate(done: (LocalDate) -> Unit) {
        val t = LocalDate.now()
        DatePickerDialog(this, android.R.style.Theme_Material_Dialog, { _, y, m, d ->
            done(LocalDate.of(y, m + 1, d))
        }, t.year, t.monthValue - 1, t.dayOfMonth).also {
            // No past dates: a task moved into the past would come straight back as delayed.
            it.datePicker.minDate = System.currentTimeMillis() - 1000
            it.setOnCancelListener { finish() }
        }.show()
    }

    private fun pickTime(done: (LocalTime) -> Unit) {
        val n = LocalTime.now()
        TimePickerDialog(this, android.R.style.Theme_Material_Dialog, { _, h, m ->
            done(LocalTime.of(h, m))
        }, n.hour, n.minute, false).also {
            it.setOnCancelListener { finish() }
        }.show()
    }
}
