package net.shino3.qsmultitools

import android.app.AlarmManager
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
 * 「次のアラーム」ホーム画面ウィジェット。
 *
 * 定期更新 (updatePeriodMillis) は使わない。アラームが変わると
 * [AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED] が飛んでくるので、それで描き直す。
 * このブロードキャストは Android 8 以降の暗黙ブロードキャスト制限の例外に入っているため、
 * マニフェスト宣言の receiver で受けられる。常駐は必要ない。
 *
 * 文字サイズはウィジェットの実寸から毎回計算するので、リサイズすると追従する。
 * 見た目 ([WidgetStyle]) はアプリのセットアップ画面から変更する。
 */
class NextAlarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
    }

    /** リサイズされたとき。寸法が変わったので文字サイズを計算し直す。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        render(context, manager, appWidgetId)
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
            ids.forEach { render(context, manager, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            runCatching {
                manager.updateAppWidget(appWidgetId, buildViews(context, sizeOf(manager, appWidgetId)))
            }
        }

        /**
         * ホーム画面アプリが報告しているウィジェットの寸法 (dp)。
         *
         * API 31 以降は OPTION_APPWIDGET_SIZES に取り得るサイズが並ぶので、その中で最小のものを使う。
         * どの向きでも中身が収まるようにするため。無ければ MIN_WIDTH / MIN_HEIGHT に落とす。
         */
        private fun sizeOf(manager: AppWidgetManager, appWidgetId: Int): SizeF {
            val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull()
                ?: return DEFAULT_WIDGET_SIZE

            val sizes = runCatching {
                options.getParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    SizeF::class.java,
                )
            }.getOrNull()
            sizes?.filter { it.width > 0f && it.height > 0f }
                ?.minByOrNull { it.width * it.height }
                ?.let { return it }

            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            return if (width > 0 && height > 0) {
                SizeF(width.toFloat(), height.toFloat())
            } else {
                DEFAULT_WIDGET_SIZE
            }
        }

        private fun buildViews(context: Context, size: SizeF): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_next_alarm)
            val info = NextAlarm.info(context)

            val time = info?.let { NextAlarm.formatTime(context, it.triggerTime) }
                ?: context.getString(R.string.widget_no_alarm)
            val day = info?.let { NextAlarm.formatDay(context, it.triggerTime) }

            val metrics = metricsFor(size, time.length)
            applyText(views, time, day, metrics)
            applyStyle(context, views, WidgetStyle.load(context), metrics)

            // タップ先は、そのアラームを持っている時計アプリが用意した PendingIntent が最優先。
            // 無ければ (アラーム未設定など) 通常のアラーム一覧を開く。
            val target = info?.showIntent ?: alarmListIntent(context)
            if (target != null) {
                views.setOnClickPendingIntent(R.id.widgetRoot, target)
            }
            return views
        }

        private fun applyText(views: RemoteViews, time: String, day: String?, metrics: Metrics) {
            views.setTextViewText(R.id.widgetTime, time)
            views.setTextViewTextSize(R.id.widgetTime, TypedValue.COMPLEX_UNIT_SP, metrics.timeSp)
            views.setTextViewTextSize(R.id.widgetHeader, TypedValue.COMPLEX_UNIT_SP, metrics.headerSp)
            views.setTextViewTextSize(R.id.widgetDate, TypedValue.COMPLEX_UNIT_SP, metrics.dateSp)

            views.setViewVisibility(
                R.id.widgetHeader,
                if (metrics.showHeader) View.VISIBLE else View.GONE,
            )
            if (day != null && metrics.showDate) {
                views.setTextViewText(R.id.widgetDate, day)
                views.setViewVisibility(R.id.widgetDate, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widgetDate, View.GONE)
            }
        }

        private fun applyStyle(
            context: Context,
            views: RemoteViews,
            style: WidgetStyle,
            metrics: Metrics,
        ) {
            views.applyFrame(context, style, R.id.widgetRoot, R.id.widgetInner, metrics.paddingDp)

            // SYSTEM のときは文字色も指定しない。レイアウトの values / values-night に任せる。
            val palette = style.palette ?: return
            views.setTextColor(R.id.widgetTime, palette.text)
            views.setTextColor(R.id.widgetHeader, palette.subText)
            views.setTextColor(R.id.widgetDate, palette.subText)
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
