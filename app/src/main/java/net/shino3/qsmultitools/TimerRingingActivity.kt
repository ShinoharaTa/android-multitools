package net.shino3.qsmultitools

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 鳴っているときの完了画面。止めるか、延長して再開するかを選ぶ。
 *
 * ロック中・画面オフなら全画面インテントで直接開く。ロック画面の上に出すので、
 * 止めたり延長したりするのにロック解除は要らない。
 *
 * 通知やウィジェットから止められたら、この画面も閉じる ([Timer.ACTION_CHANGED] を受ける)。
 */
class TimerRingingActivity : Activity() {

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
        setContentView(R.layout.activity_timer_ringing)
        findViewById<android.view.View>(R.id.ringingRoot).padForSystemBars()

        val state = Timer.state(this)
        findViewById<TextView>(R.id.ringingDuration).text =
            getString(R.string.timer_done_text, Timer.formatDuration(state.totalMs))
        // 鳴り始めてからの経過を数え上げる。
        findViewById<Chronometer>(R.id.ringingElapsed).apply {
            base = state.endElapsed
            start()
        }

        findViewById<Button>(R.id.btnRingingStop).setOnClickListener {
            Timer.cancel(this)
            finish()
        }

        val extends = findViewById<LinearLayout>(R.id.ringingExtends)
        TimerSettings.EXTENDS.forEach { ms ->
            extends.addView(
                Button(this).apply {
                    text = Timer.formatExtend(ms)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        Timer.extend(this@TimerRingingActivity, ms)
                        finish()
                    }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(changed, IntentFilter(Timer.ACTION_CHANGED), RECEIVER_NOT_EXPORTED)
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

    private fun finishIfNotRinging() {
        if (Timer.state(this).phase != Timer.Phase.RINGING) finish()
    }
}
