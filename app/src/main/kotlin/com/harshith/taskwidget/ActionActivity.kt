package com.harshith.taskwidget

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.glance.appwidget.updateAll
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
        if (intent.getBooleanExtra(EXTRA_DONE_NOW, false)) doneWithUndo() else showMain(task)
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
            val before = withContext(Dispatchers.IO) { Repo.markDone(app, eventId) }
            TaskWidget().updateAll(app)
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
        CoroutineScope(Dispatchers.IO).launch {
            Repo.undoDone(app, before)
            TaskWidget().updateAll(app)
        }
        finish()
    }

    private fun showMain(task: Item) {
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

    private fun perform(block: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            block()
            TaskWidget().updateAll(applicationContext)
        }
        finish()
    }

    private fun laterToday() {
        val labels = arrayOf("In 1 hour", "In 3 hours", "This evening (6 PM)", "Pick a time…")
        dialog().setTitle("Later today").setItems(labels) { _, which ->
            val now = LocalDateTime.now()
            val today = LocalDate.now()
            when (which) {
                0 -> moveTo(today, now.plusHours(1).toLocalTime().withSecond(0).withNano(0))
                1 -> moveTo(today, now.plusHours(3).toLocalTime().withSecond(0).withNano(0))
                2 -> moveTo(today, LocalTime.of(18, 0))
                else -> pickTime { t -> moveTo(today, t) }
            }
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

    private fun moveTo(date: LocalDate, time: LocalTime?) {
        perform { Repo.reschedule(this, eventId, date, time) }
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
        perform { Repo.partlyDone(this, eventId, note, millis) }
    }

    private fun pickDate(done: (LocalDate) -> Unit) {
        val t = LocalDate.now()
        DatePickerDialog(this, android.R.style.Theme_Material_Dialog, { _, y, m, d ->
            done(LocalDate.of(y, m + 1, d))
        }, t.year, t.monthValue - 1, t.dayOfMonth).also {
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
