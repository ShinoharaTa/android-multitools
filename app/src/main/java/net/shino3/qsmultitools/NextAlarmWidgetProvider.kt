package net.shino3.qsmultitools

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * 「次のアラーム」ホーム画面ウィジェット。
 *
 * 定期更新 (updatePeriodMillis) は使わない。アラームが変わると
 * [AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED] が飛んでくるので、それで描き直す。
 * このブロードキャストは Android 8 以降の暗黙ブロードキャスト制限の例外に入っているため、
 * マニフェスト宣言の receiver で受けられる。常駐は必要ない。
 */
class NextAlarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { manager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> refresh(context)
        }
    }

    companion object {

        /** 置かれている全ウィジェットを描き直す。1 つも無ければ何もしない。 */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, NextAlarmWidgetProvider::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return

            val views = buildViews(context)
            ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_next_alarm)
            val info = NextAlarm.info(context)

            if (info == null) {
                views.setTextViewText(R.id.widgetTime, context.getString(R.string.widget_no_alarm))
                views.setViewVisibility(R.id.widgetDate, View.GONE)
            } else {
                views.setTextViewText(R.id.widgetTime, NextAlarm.formatTime(context, info.triggerTime))
                views.setTextViewText(R.id.widgetDate, NextAlarm.formatDay(context, info.triggerTime))
                views.setViewVisibility(R.id.widgetDate, View.VISIBLE)
            }

            // タップ先は、そのアラームを持っている時計アプリが用意した PendingIntent が最優先。
            // 無ければ (アラーム未設定など) 通常のアラーム一覧を開く。
            val target = info?.showIntent ?: alarmListIntent(context)
            if (target != null) {
                views.setOnClickPendingIntent(R.id.widgetRoot, target)
            }
            return views
        }

        private fun alarmListIntent(context: Context): PendingIntent? {
            val intent = Alarms.resolve(context)?.intent ?: return null
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                PendingIntent.getActivity(
                    context,
                    REQ_WIDGET_ALARM,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }.getOrNull()
        }

        private const val REQ_WIDGET_ALARM = 10
    }
}
