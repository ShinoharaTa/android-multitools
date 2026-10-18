package net.shino3.qsmultitools

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * アラームが鳴っているときの画面。スヌーズか停止かを選ぶ。
 *
 * ロック中・画面オフなら全画面インテントで直接開く。ロック解除は要らない。
 * ロック解除前 (再起動直後) でも開けるよう directBootAware にしてある。
 * 通知やウィジェットから止められたら、この画面も閉じる。
 */
class AlarmRingingActivity : Activity() {

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finishIfNotRinging()
    }

    /** 鳴っている間は戻るで閉じない。閉じると鳴ったまま画面だけ消えて、通知を探す羽目になる。 */
    private val swallowBack = OnBackInvokedCallback { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, swallowBack)
        setContentView(R.layout.activity_alarm_ringing)
        findViewById<View>(R.id.alarmRingingRoot).padForSystemBars()

        findViewById<Button>(R.id.btnAlarmSnooze).apply {
            text = getString(R.string.alarm_snooze_n, Timer.formatDuration(AlarmSettings.snoozeMs(this@AlarmRingingActivity)))
            setOnClickListener {
                AlarmScheduler.snooze(this@AlarmRingingActivity)
                finish()
            }
        }
        findViewById<Button>(R.id.btnAlarmDismiss).setOnClickListener {
            AlarmScheduler.dismiss(this)
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(changed, IntentFilter(AlarmScheduler.ACTION_CHANGED), RECEIVER_NOT_EXPORTED)
        render()
        finishIfNotRinging()
    }

    override fun onStop() {
        unregisterReceiver(changed)
        super.onStop()
    }

    override fun onDestroy() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(swallowBack)
        super.onDestroy()
    }

    private fun render() {
        val ids = AlarmStore.ringingIds(this)
        val alarms = AlarmStore.all(this).filter { it.id in ids }
        val since = AlarmStore.ringingSince(this).takeIf { it > 0 } ?: System.currentTimeMillis()
        findViewById<TextView>(R.id.alarmRingingTime).text = NextAlarm.formatTime(this, since)
        val labels = alarms.map { it.label }.filter { it.isNotBlank() }.joinToString(" / ")
        findViewById<TextView>(R.id.alarmRingingLabel).apply {
            text = labels
            visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun finishIfNotRinging() {
        if (AlarmStore.ringingIds(this).isEmpty()) finish() else render()
    }
}
