package com.harshith.taskwidget

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16171A"))
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(size: Float, color: String) = TextView(this).apply {
            textSize = size
            setTextColor(Color.parseColor(color))
        }
        root.addView(text(22f, "#ECECEC").apply { text = "Task Widget" })
        status = text(14f, "#9A9CA3").apply { setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Allow calendar and notifications"
            setOnClickListener { askPermissions() }
        })
        root.addView(Button(this).apply {
            text = "Refresh widget now"
            setOnClickListener { refresh() }
        })
        list = text(14f, "#ECECEC").apply { setPadding(0, pad, 0, 0); gravity = Gravity.START }
        root.addView(ScrollView(this).apply { addView(list) })
        setContentView(root)
        Reminders.ensureChannel(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        if (!Repo.hasPermission(this)) {
            status.text = "Calendar access is off. Tap the button below to allow it."
            list.text = ""
            return
        }
        status.text = "Reading your calendar…"
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val (snap, since) = try {
                withContext(Dispatchers.IO) {
                    Repo.sync(app)
                    Repo.load(app) to Repo.trackingSince(app)
                }.also { refreshWidgets(app) }
            } catch (e: Exception) {
                status.text = "Couldn't read your calendar. Check calendar access and try Refresh."
                return@launch
            }
            status.text = "${snap.done} of ${snap.total} done today. Tracking tasks from $since; older events are left as they are. " +
                "Add the widget from your home screen: long-press, Widgets, Task Widget."
            list.text = snap.active.joinToString("\n\n") { i ->
                val p = i.parsed
                val tag = when (p.kind) {
                    Kind.DELAYED -> "Delayed +${p.delayDays}"
                    Kind.FOLLOWUP -> "Follow up"
                    else -> ""
                }
                if (tag.isEmpty()) p.base else "${p.base}\n$tag"
            }
        }
    }
}
