package net.shino3.qsmultitools

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

/**
 * 自前アラームの一覧ウィジェット。1 件ずつ ON/OFF を切り替えられる。
 *
 * 件数は多くないので、コレクション (RemoteViewsService) は使わずに行を addView で並べる。
 * 入りきらないぶんは最後の行を「他 N 件」にまとめる。
 * スクロールはできないが、そのぶん行ごとのボタンに普通の PendingIntent が使え、仕組みが単純になる。
 */
class AlarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
        AlarmScheduler.scheduleMidnightRefresh(context)
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
                manager.getAppWidgetIds(ComponentName(context, AlarmWidgetProvider::class.java))
            }.getOrNull() ?: return
            ids.forEach { render(context, manager, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            runCatching {
                val size = widgetSize(manager, appWidgetId, DEFAULT_ALARM_LIST_SIZE)
                manager.updateAppWidget(appWidgetId, buildViews(context, size))
            }
        }

        private fun buildViews(context: Context, size: SizeF): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_alarm_list)
            // 見た目のテーマは通常の保存領域にあるので、ロック解除前は既定で描く。
            val style = runCatching { WidgetStyle.load(context) }.getOrDefault(WidgetStyle.DEFAULT)
            val palette = style.palette
            views.applyFrame(context, style, R.id.widgetRoot, R.id.widgetInner, PADDING_DP)

            val now = System.currentTimeMillis()
            val alarms = AlarmStore.all(context)

            val upcoming = alarms.mapNotNull { it.nextTrigger(now) }.minOrNull()
            // 「あと N 分」は時間が経つと古くなるので、ウィジェットには絶対時刻で出す。
            // 「今日 / 明日」は日付が変わるときに描き直す (AlarmScheduler.scheduleMidnightRefresh)。
            views.setTextViewText(
                R.id.alarmWidgetNext,
                upcoming?.let {
                    context.getString(
                        R.string.alarm_widget_next,
                        NextAlarm.formatDay(context, it),
                        NextAlarm.formatTime(context, it),
                    )
                } ?: context.getString(R.string.alarm_widget_none_on),
            )
            views.setOnClickPendingIntent(R.id.alarmWidgetHeader, AlarmScheduler.listPendingIntent(context))
            views.setOnClickPendingIntent(R.id.alarmWidgetAdd, activity(context, REQ_ADD, null))

            views.removeAllViews(R.id.alarmWidgetRows)
            // 全部入るならそのまま。入らなければ「他 N 件」の行 (背が低い) のぶんを引いた残りに詰める。
            val usable = size.height - PADDING_DP * 2 - HEADER_DP
            val capacity = (usable / ROW_DP).toInt()
            val shown = if (alarms.size <= capacity) alarms
            else alarms.take(((usable - MORE_DP) / ROW_DP).toInt().coerceAtLeast(1))
            views.setViewVisibility(R.id.alarmWidgetEmpty, if (alarms.isEmpty()) View.VISIBLE else View.GONE)

            shown.forEach { alarm ->
                val row = RemoteViews(context.packageName, R.layout.widget_alarm_row)
                val active = alarm.isActive(now)
                row.setTextViewText(R.id.alarmRowTime, AlarmFormat.time(context, alarm.hour, alarm.minute))
                val sub = buildList {
                    add(
                        if (alarm.snoozeUntil > now) {
                            context.getString(R.string.alarm_snoozing_short, NextAlarm.formatTime(context, alarm.snoozeUntil))
                        } else {
                            AlarmFormat.repeat(context, alarm, now)
                        },
                    )
                    if (alarm.label.isNotBlank()) add(alarm.label)
                }.joinToString(" · ")
                row.setTextViewText(R.id.alarmRowSub, sub)
                // 本物のスイッチ (API 31 から RemoteViews で使える)。「ON」の文字より向きが読める。
                row.setCompoundButtonChecked(R.id.alarmRowSwitch, active)
                row.setOnCheckedChangeResponse(
                    R.id.alarmRowSwitch,
                    RemoteViews.RemoteResponse.fromPendingIntent(AlarmReceiver.toggleIntent(context, alarm.id)),
                )
                row.setOnClickPendingIntent(R.id.alarmRow, activity(context, REQ_EDIT_BASE + alarm.id, alarm.id))

                val text = palette?.text ?: context.getColor(R.color.widget_text)
                val subColor = palette?.subText ?: context.getColor(R.color.widget_text_sub)
                // OFF の行は薄くする。RemoteViews では alpha を直接いじれないので、文字色で表す。
                row.setTextColor(R.id.alarmRowTime, if (active) text else fade(text))
                row.setTextColor(R.id.alarmRowSub, if (active) subColor else fade(subColor))
                views.addView(R.id.alarmWidgetRows, row)
            }

            if (shown.size < alarms.size) {
                val more = RemoteViews(context.packageName, R.layout.widget_alarm_more)
                more.setTextViewText(R.id.alarmMore, context.getString(R.string.alarm_widget_more, alarms.size - shown.size))
                more.setOnClickPendingIntent(R.id.alarmMore, AlarmScheduler.listPendingIntent(context))
                palette?.let { more.setTextColor(R.id.alarmMore, it.subText) }
                views.addView(R.id.alarmWidgetRows, more)
            }

            palette?.let {
                views.setTextColor(R.id.alarmWidgetTitle, it.text)
                views.setTextColor(R.id.alarmWidgetNext, it.subText)
                views.setTextColor(R.id.alarmWidgetAdd, it.text)
                views.setTextColor(R.id.alarmWidgetEmpty, it.subText)
            }
            return views
        }

        private fun fade(color: Int): Int = (color and 0x00FFFFFF) or 0x66000000

        /** 編集画面 (id が null なら新規追加) を開く。アラームごとに別の requestCode にする。 */
        private fun activity(context: Context, requestCode: Int, id: Int?): PendingIntent {
            val intent = Intent(context, AlarmEditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            id?.let { intent.putExtra(AlarmEditActivity.EXTRA_ID, it) }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private const val REQ_ADD = 30
        private const val REQ_EDIT_BASE = 1000

        private const val PADDING_DP = 10f
        private const val HEADER_DP = 34f
        private const val ROW_DP = 44f
        private const val MORE_DP = 26f

        /** 既定の 3x2 相当。 */
        private val DEFAULT_ALARM_LIST_SIZE = SizeF(220f, 160f)
    }
}
