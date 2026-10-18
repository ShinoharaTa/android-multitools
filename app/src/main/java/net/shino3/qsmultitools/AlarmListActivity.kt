package net.shino3.qsmultitools

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * 自前アラームの一覧。ON/OFF の切り替え、追加・編集画面への入口、鳴り方の設定を置く。
 *
 * 件数は多くても十数件の想定なので、ListView は使わずに LinearLayout へ並べ直すだけにしてある。
 */
class AlarmListActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var next: TextView

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_list)
        findViewById<View>(R.id.alarmListScroll).padForSystemBars()

        list = findViewById(R.id.alarmList)
        empty = findViewById(R.id.alarmListEmpty)
        next = findViewById(R.id.alarmListNext)

        findViewById<Button>(R.id.btnAlarmAdd).setOnClickListener {
            startActivity(Intent(this, AlarmEditActivity::class.java))
        }
        findViewById<Button>(R.id.btnAlarmAddWidget).setOnClickListener {
            requestPinWidget(AlarmWidgetProvider::class.java)
        }
        setUpSettings()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(changed, IntentFilter(AlarmScheduler.ACTION_CHANGED), RECEIVER_NOT_EXPORTED)
        render()
    }

    override fun onStop() {
        unregisterReceiver(changed)
        super.onStop()
    }

    private fun render() {
        val alarms = AlarmStore.all(this)
        val now = System.currentTimeMillis()
        list.removeAllViews()
        empty.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        alarms.forEach { alarm ->
            val row = inflater.inflate(R.layout.item_alarm, list, false)
            row.findViewById<TextView>(R.id.alarmTime).text = AlarmFormat.time(this, alarm.hour, alarm.minute)
            row.findViewById<TextView>(R.id.alarmSub).text = subText(alarm, now)
            row.alpha = if (alarm.enabled || alarm.snoozeUntil > now) 1f else 0.5f
            row.findViewById<Switch>(R.id.alarmSwitch).apply {
                isChecked = alarm.enabled
                setOnCheckedChangeListener { _, checked -> AlarmScheduler.setEnabled(this@AlarmListActivity, alarm.id, checked) }
            }
            row.setOnClickListener {
                startActivity(Intent(this, AlarmEditActivity::class.java).putExtra(AlarmEditActivity.EXTRA_ID, alarm.id))
            }
            list.addView(row)
        }

        val upcoming = alarms.mapNotNull { it.nextTrigger(now) }.minOrNull()
        next.text = if (upcoming == null) {
            getString(R.string.alarm_next_none)
        } else {
            getString(
                R.string.alarm_next,
                "${NextAlarm.formatDay(this, upcoming)} ${NextAlarm.formatTime(this, upcoming)}",
                AlarmFormat.until(this, upcoming, now),
            )
        }
    }

    /** 「平日 · 起床」「スヌーズ中 (7:10 に再開)」のような 2 行目。 */
    private fun subText(alarm: Alarm, now: Long): String {
        val parts = mutableListOf<String>()
        if (alarm.snoozeUntil > now) {
            parts += getString(R.string.alarm_snoozing, NextAlarm.formatTime(this, alarm.snoozeUntil))
        } else {
            parts += AlarmFormat.repeat(this, alarm, now)
        }
        if (alarm.label.isNotBlank()) parts += alarm.label
        return parts.joinToString(" · ")
    }

    private fun setUpSettings() {
        val snoozes = AlarmSettings.SNOOZE_CANDIDATES.toList()
        bindSpinner(
            R.id.spinnerSnooze,
            snoozes.map { Timer.formatDuration(it) },
            snoozes.indexOf(AlarmSettings.snoozeMs(this)),
        ) { AlarmSettings.setSnoozeMs(this, snoozes[it]) }

        val modes = AlarmRingMode.entries
        bindSpinner(
            R.id.spinnerAlarmRingMode,
            modes.map { getString(it.labelRes) },
            modes.indexOf(AlarmSettings.ringMode(this)),
        ) { AlarmSettings.setRingMode(this, modes[it]) }

        findViewById<CheckBox>(R.id.checkAlarmVibrate).apply {
            isChecked = AlarmSettings.vibrate(this@AlarmListActivity)
            setOnCheckedChangeListener { _, checked -> AlarmSettings.setVibrate(this@AlarmListActivity, checked) }
        }
    }

    private fun bindSpinner(id: Int, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        findViewById<Spinner>(id).apply {
            adapter = ArrayAdapter(this@AlarmListActivity, android.R.layout.simple_spinner_item, labels)
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selected.coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                    onSelect(position)

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }
}
