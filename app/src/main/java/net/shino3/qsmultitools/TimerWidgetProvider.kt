package net.shino3.qsmultitools

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/**
 * タイマーの即開始ウィジェット。
 *
 * - 待機中: セットアップ画面で選んだ長さの開始ボタンを並べる
 * - 動作中: 残り時間と、延長 (+1分) / キャンセルのボタン
 * - 鳴動中: 経過時間と、停止 / 延長のボタン
 *
 * 残り時間は RemoteViews の Chronometer にカウントダウンさせるので、毎秒の更新はしない。
 * 描き直すのは状態が変わったとき ([Timer] から呼ばれる) とリサイズのときだけ。
 * 見た目は「次のアラーム」と同じ [WidgetStyle] を使う。
 */
class TimerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        render(context, manager, appWidgetId)
    }

    companion object {

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, TimerWidgetProvider::class.java))
            }.getOrNull() ?: return
            ids.forEach { render(context, manager, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            runCatching {
                val size = widgetSize(manager, appWidgetId, DEFAULT_TIMER_SIZE)
                manager.updateAppWidget(appWidgetId, buildViews(context, size))
            }
        }

        fun buildViews(context: Context, size: SizeF): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_timer)
            val style = WidgetStyle.load(context)
            val state = Timer.state(context)

            val buttons: List<Pair<String, PendingIntent>> = when (state.phase) {
                Timer.Phase.IDLE -> TimerSettings.presets(context).map {
                    Timer.formatDuration(it) to TimerReceiver.startIntent(context, it)
                }
                Timer.Phase.RUNNING -> listOf(
                    Timer.formatExtend(TimerSettings.RUNNING_EXTEND_MS) to
                        TimerReceiver.extendIntent(context, TimerSettings.RUNNING_EXTEND_MS),
                    context.getString(R.string.timer_cancel_short) to TimerReceiver.cancelIntent(context),
                )
                Timer.Phase.RINGING -> listOf(
                    context.getString(R.string.timer_stop) to TimerReceiver.cancelIntent(context),
                ) + TimerSettings.EXTENDS.take(2).map {
                    Timer.formatExtend(it) to TimerReceiver.extendIntent(context, it)
                }
            }

            val idle = state.phase == Timer.Phase.IDLE
            val metrics = timerMetrics(size, if (idle) 0 else buttons.size, state.remainingMs)
            views.applyFrame(context, style, R.id.widgetRoot, R.id.widgetInner, metrics.paddingDp)

            views.setViewVisibility(R.id.timerInfo, if (idle) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.timerPresets, if (idle) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.timerActions, if (idle) View.GONE else View.VISIBLE)

            if (!idle) {
                val ringing = state.phase == Timer.Phase.RINGING
                views.setTextViewText(
                    R.id.timerHeader,
                    context.getString(if (ringing) R.string.timer_done_title else R.string.timer_widget_remaining),
                )
                views.setViewVisibility(R.id.timerHeader, if (metrics.showHeader) View.VISIBLE else View.GONE)
                views.setTextViewTextSize(R.id.timerHeader, TypedValue.COMPLEX_UNIT_SP, metrics.headerSp)
                views.setTextViewTextSize(R.id.timerRemaining, TypedValue.COMPLEX_UNIT_SP, metrics.timeSp)
                // 動作中は終了時刻へ向けて数え下げ、鳴動中は鳴り始めからの経過を数え上げる。
                views.setChronometer(R.id.timerRemaining, state.endElapsed, if (ringing) "+%s" else null, true)
                views.setChronometerCountDown(R.id.timerRemaining, !ringing)
            }

            val container = if (idle) R.id.timerPresets else R.id.timerActions
            val itemLayout = if (idle) R.layout.widget_timer_preset else R.layout.widget_timer_action
            views.removeAllViews(container)
            buttons.forEach { (label, intent) ->
                val button = RemoteViews(context.packageName, itemLayout)
                button.setTextViewText(R.id.timerButton, label)
                button.setTextViewTextSize(R.id.timerButton, TypedValue.COMPLEX_UNIT_SP, metrics.buttonSp)
                button.setOnClickPendingIntent(R.id.timerButton, intent)
                style.palette?.let {
                    button.setTextColor(R.id.timerButton, it.text)
                    button.tintBackground(R.id.timerButton, (it.text and 0x00FFFFFF) or BUTTON_ALPHA)
                }
                views.addView(container, button)
            }

            style.palette?.let {
                views.setTextColor(R.id.timerHeader, it.subText)
                views.setTextColor(R.id.timerRemaining, it.text)
            }

            // ボタン以外の場所を押したときの行き先。鳴っていれば完了画面、それ以外はセットアップ画面。
            views.setOnClickPendingIntent(R.id.widgetRoot, openIntent(context, state.phase))
            return views
        }

        private fun openIntent(context: Context, phase: Timer.Phase): PendingIntent {
            val target = if (phase == Timer.Phase.RINGING) TimerRingingActivity::class.java else MainActivity::class.java
            return PendingIntent.getActivity(
                context,
                REQ_WIDGET_OPEN,
                Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private const val REQ_WIDGET_OPEN = 20

        /** ボタン背景の濃さ。文字色に重ねるアルファ。 */
        private const val BUTTON_ALPHA = 0x26000000
    }
}

data class TimerMetrics(
    val timeSp: Float,
    val headerSp: Float,
    val buttonSp: Float,
    val showHeader: Boolean,
    val paddingDp: Float,
)

/**
 * タイマーウィジェットの文字サイズ。
 *
 * 残り時間は「ウィジェット幅からボタン列の幅を引いた残り」と高さの両方に収まる大きさにする。
 * ボタンの幅は文字サイズ次第で決まるので、高さから決めた文字サイズで見積もる。
 */
fun timerMetrics(size: SizeF, actionCount: Int, remainingMs: Long): TimerMetrics {
    val padding = (size.height * 0.10f).coerceIn(4f, 10f)
    val contentHeight = size.height - padding * 2f
    val buttonSp = (contentHeight * 0.30f).coerceIn(11f, 20f)
    val actionWidth = actionCount * (buttonSp * 3.2f + 6f)

    val showHeader = size.height >= 68f
    val stack = if (showHeader) 1f + TIMER_HEADER_RATIO else 1f
    // 1 時間を超えると「H:MM:SS」で 7 文字、それ未満は「MM:SS」で 5 文字。
    val chars = if (remainingMs >= 3_600_000) 7 else 5
    val byHeight = contentHeight / (TIMER_LINE_HEIGHT * stack)
    val byWidth = (size.width - padding * 2f - actionWidth) / (chars * 0.6f)
    val timeSp = minOf(byHeight, byWidth).coerceIn(11f, 72f)
    return TimerMetrics(
        timeSp = timeSp,
        headerSp = (timeSp * TIMER_HEADER_RATIO).coerceAtLeast(9f),
        buttonSp = buttonSp,
        showHeader = showHeader,
        paddingDp = padding,
    )
}

/** 既定の 3x1 相当。 */
val DEFAULT_TIMER_SIZE = SizeF(220f, 72f)

private const val TIMER_HEADER_RATIO = 0.38f
private const val TIMER_LINE_HEIGHT = 1.45f
