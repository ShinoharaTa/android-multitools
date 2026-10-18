package net.shino3.qsmultitools

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner

/**
 * タイマー画面。操作パネル ([TimerPanel]) と、開始ボタンの候補・鳴り方の設定。
 * ウィジェットと通知からの「アプリを開く」はここに来る。
 */
class TimerActivity : Activity() {

    private lateinit var panel: TimerPanel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timer)
        findViewById<View>(R.id.timerScroll).padForSystemBars()
        panel = TimerPanel(this, findViewById(R.id.timerPanel), compact = false)

        findViewById<Button>(R.id.btnAddTimerWidget).setOnClickListener {
            requestPinWidget(TimerWidgetProvider::class.java)
        }
        setUpSettings()
    }

    override fun onStart() {
        super.onStart()
        panel.start()
    }

    override fun onStop() {
        panel.stop()
        super.onStop()
    }

    /** 開始ボタンの候補、鳴り終わり、バイブ。変えたらパネルとウィジェットにすぐ反映する。 */
    private fun setUpSettings() {
        val container = findViewById<LinearLayout>(R.id.timerPresetChecks)
        val enabled = TimerSettings.presets(this).toMutableSet()
        TimerSettings.PRESET_CANDIDATES.forEach { value ->
            container.addView(
                CheckBox(this).apply {
                    text = Timer.formatDuration(value)
                    isChecked = value in enabled
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) enabled.add(value) else enabled.remove(value)
                        TimerSettings.setPresets(this@TimerActivity, enabled)
                        // 1 つも残らなければ既定に戻るので、表示も実際の候補に合わせ直す。
                        val actual = TimerSettings.presets(this@TimerActivity).toSet()
                        if (actual != enabled) {
                            enabled.clear()
                            enabled.addAll(actual)
                            TimerSettings.PRESET_CANDIDATES.forEachIndexed { index, v ->
                                (container.getChildAt(index) as? CheckBox)?.isChecked = v in actual
                            }
                        }
                        panel.render()
                        TimerWidgetProvider.refresh(this@TimerActivity)
                    }
                },
            )
        }

        val modes = RingMode.entries
        findViewById<Spinner>(R.id.spinnerRingMode).apply {
            adapter = ArrayAdapter(
                this@TimerActivity,
                android.R.layout.simple_spinner_item,
                modes.map { getString(it.labelRes) },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(modes.indexOf(TimerSettings.ringMode(this@TimerActivity)), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    TimerSettings.setRingMode(this@TimerActivity, modes[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        findViewById<CheckBox>(R.id.checkTimerVibrate).apply {
            isChecked = TimerSettings.vibrate(this@TimerActivity)
            setOnCheckedChangeListener { _, checked -> TimerSettings.setVibrate(this@TimerActivity, checked) }
        }
    }
}
