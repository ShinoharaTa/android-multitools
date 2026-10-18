package net.shino3.qsmultitools

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import android.widget.ToggleButton

/**
 * アラームの追加と編集。[EXTRA_ID] が無ければ新規。
 *
 * 保存すると ON にする。編集するのは鳴らしたいときだという前提。
 */
class AlarmEditActivity : Activity() {

    private var editing: Alarm? = null
    private lateinit var picker: TimePicker
    private lateinit var label: EditText
    private val dayButtons = mutableMapOf<java.time.DayOfWeek, ToggleButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_edit)
        findViewById<View>(R.id.alarmEditScroll).padForSystemBars()

        editing = intent.getIntExtra(EXTRA_ID, 0).takeIf { it != 0 }?.let { AlarmStore.get(this, it) }
        val alarm = editing

        findViewById<TextView>(R.id.alarmEditTitle).setText(
            if (alarm == null) R.string.alarm_add_title else R.string.alarm_edit_title,
        )

        picker = findViewById(R.id.alarmTimePicker)
        picker.setIs24HourView(DateFormat.is24HourFormat(this))
        if (alarm != null) {
            picker.hour = alarm.hour
            picker.minute = alarm.minute
        }

        val days = findViewById<LinearLayout>(R.id.alarmDays)
        days.gravity = android.view.Gravity.CENTER_HORIZONTAL
        AlarmFormat.WEEK_ORDER.forEach { day ->
            val button = ToggleButton(this).apply {
                val text = AlarmFormat.dayShort(day)
                textOn = text
                textOff = text
                isChecked = alarm?.repeatsOn(day) == true
                // 既定の ToggleButton は下線の色しか変わらず選択が読めないので、丸い塗りつぶしにする。
                background = getDrawable(R.drawable.bg_day_toggle)
                setTextColor(getColorStateList(R.color.day_toggle_text))
                minWidth = 0
                minHeight = 0
                val size = toPx(42f)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    val gap = toPx(3f)
                    setMargins(gap, 0, gap, 0)
                }
            }
            dayButtons[day] = button
            days.addView(button)
        }
        findViewById<Button>(R.id.btnDaysWeekdays).setOnClickListener { setDays(Alarm.WEEKDAYS) }
        findViewById<Button>(R.id.btnDaysEveryDay).setOnClickListener { setDays(Alarm.EVERY_DAY) }
        findViewById<Button>(R.id.btnDaysOnce).setOnClickListener { setDays(0) }

        label = findViewById(R.id.alarmLabel)
        label.setText(alarm?.label.orEmpty())

        findViewById<Button>(R.id.btnAlarmSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnAlarmCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAlarmDelete).apply {
            visibility = if (alarm == null) View.GONE else View.VISIBLE
            setOnClickListener { confirmDelete() }
        }
    }

    private fun setDays(mask: Int) {
        dayButtons.forEach { (day, button) -> button.isChecked = mask and (1 shl (day.value - 1)) != 0 }
    }

    private fun save() {
        // TimePicker の数字を直接打ち込んだ場合、フォーカスが外れるまで値が確定しない。
        // 外してから読まないと、打ち込む前の時刻で保存されてしまう。
        picker.clearFocus()
        val mask = dayButtons.entries.fold(0) { acc, (day, button) ->
            if (button.isChecked) acc or (1 shl (day.value - 1)) else acc
        }
        // 新規と、時刻や曜日を変えたときは ON にする。ラベルだけ直したときは ON/OFF を触らない。
        val previous = editing
        val timeChanged = previous == null ||
            previous.hour != picker.hour || previous.minute != picker.minute || previous.days != mask
        val alarm = Alarm(
            id = previous?.id ?: AlarmStore.newId(this),
            hour = picker.hour,
            minute = picker.minute,
            days = mask,
            label = label.text.toString().trim(),
            enabled = if (timeChanged) true else previous.enabled,
            snoozeUntil = if (timeChanged) 0 else previous.snoozeUntil,
        )
        AlarmScheduler.upsert(this, alarm)
        alarm.nextTrigger(System.currentTimeMillis())?.let {
            val message = getString(R.string.alarm_saved, NextAlarm.formatTime(this, it), AlarmFormat.until(this, it))
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun confirmDelete() {
        val alarm = editing ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.alarm_delete_confirm)
            .setPositiveButton(R.string.alarm_delete) { _, _ ->
                AlarmScheduler.delete(this, alarm.id)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "alarm_id"
    }
}
