package net.shino3.qsmultitools

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.TextView

/**
 * タイマーの操作パネル。ホーム画面 (compact) とタイマー画面の両方に同じものを置く。
 *
 * - 待機中: 開始ボタン。compact なら 1 行に収まるぶんだけ、そうでなければ 3 列で全部
 * - 動作中: 残り時間と、延長 / キャンセル
 * - 鳴動中: 経過時間と、停止 / 延長
 *
 * 状態は [Timer] が持つ。変わったことは [Timer.ACTION_CHANGED] で知らされるので、
 * [start] と [stop] の間だけ受けて描き直す。
 */
class TimerPanel(private val activity: Activity, root: View, private val compact: Boolean) {

    private val idle: LinearLayout = root.findViewById(R.id.timerPanelIdle)
    private val active: View = root.findViewById(R.id.timerPanelActive)
    private val header: TextView = root.findViewById(R.id.timerPanelHeader)
    private val clock: Chronometer = root.findViewById(R.id.timerPanelClock)
    private val actions: LinearLayout = root.findViewById(R.id.timerPanelActions)

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    fun start() {
        activity.registerReceiver(changed, IntentFilter(Timer.ACTION_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        render()
    }

    fun stop() {
        activity.unregisterReceiver(changed)
        clock.stop()
    }

    fun render() {
        val state = Timer.state(activity)
        val isIdle = state.phase == Timer.Phase.IDLE
        idle.visibility = if (isIdle) View.VISIBLE else View.GONE
        active.visibility = if (isIdle) View.GONE else View.VISIBLE
        if (isIdle) {
            renderPresets()
            clock.stop()
            return
        }

        val ringing = state.phase == Timer.Phase.RINGING
        header.text = activity.getString(
            if (ringing) R.string.timer_done_title else R.string.timer_widget_remaining,
        )
        // 動作中は終了時刻へ数え下げ、鳴動中は鳴り始めからの経過を数え上げる。
        clock.base = state.endElapsed
        clock.isCountDown = !ringing
        clock.format = if (ringing) "+%s" else null
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 40f else 64f)
        clock.start()

        actions.removeAllViews()
        if (ringing) {
            addAction(activity.getString(R.string.timer_stop)) { Timer.cancel(activity) }
            TimerSettings.EXTENDS.take(if (compact) 1 else 2).forEach { ms ->
                addAction(Timer.formatExtend(ms)) { Timer.extend(activity, ms) }
            }
        } else {
            addAction(Timer.formatExtend(TimerSettings.RUNNING_EXTEND_MS)) {
                Timer.extend(activity, TimerSettings.RUNNING_EXTEND_MS)
            }
            if (!compact) {
                addAction(Timer.formatExtend(TimerSettings.EXTENDS[1])) { Timer.extend(activity, TimerSettings.EXTENDS[1]) }
            }
            addAction(activity.getString(R.string.timer_cancel)) { Timer.cancel(activity) }
        }
    }

    private fun renderPresets() {
        idle.removeAllViews()
        val presets = TimerSettings.presets(activity)
        // ホームでは 1 行に収まるぶんだけ出し、その行を等分して埋める。
        val shown = if (compact) presets.take(COMPACT_COLUMNS) else presets
        val columns = if (compact) shown.size.coerceAtLeast(1) else FULL_COLUMNS
        shown.chunked(columns).forEach { chunk ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { ms ->
                row.addView(
                    Button(activity).apply {
                        text = Timer.formatDuration(ms)
                        if (!compact) {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                            minimumHeight = activity.resources.getDimensionPixelSize(R.dimen.button_large)
                        }
                        layoutParams = activity.gappedWeight()
                        setOnClickListener { Timer.start(activity, ms) }
                    },
                )
            }
            // 最後の行が埋まらないときは、同じ幅の空きを足して揃える。
            repeat(columns - chunk.size) {
                row.addView(View(activity).apply {
                    layoutParams = activity.gappedWeight().apply { height = 1 }
                })
            }
            idle.addView(row)
        }
    }

    private fun addAction(label: String, onClick: () -> Unit) {
        actions.addView(
            Button(activity).apply {
                text = label
                layoutParams = activity.gappedWeight()
                setOnClickListener { onClick() }
            },
        )
    }

    private companion object {
        const val COMPACT_COLUMNS = 4
        const val FULL_COLUMNS = 3
    }
}
