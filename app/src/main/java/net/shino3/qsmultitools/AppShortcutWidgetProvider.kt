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
 * アプリを開くショートカットウィジェット。
 *
 * どのアプリを開くかはウィジェットごとに [AppShortcutConfigActivity] で決める。
 * アイコンとラベルは対象アプリのものをそのまま使い、リサイズすると大きさが追従する。
 *
 * 更新の必要があるのは設定を変えたときとリサイズしたときだけなので、
 * updatePeriodMillis は 0。アプリの入れ替えに追従するために
 * PACKAGE_ADDED / PACKAGE_REMOVED / PACKAGE_REPLACED も受ける。
 */
class AppShortcutWidgetProvider : AppWidgetProvider() {

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

    /** ウィジェットが外されたら、その設定も残さず消す。 */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        AppShortcut.delete(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_LOCALE_CHANGED,
            -> refresh(context)
        }
    }

    companion object {

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, AppShortcutWidgetProvider::class.java))
            }.getOrNull() ?: return
            ids.forEach { render(context, manager, it) }
        }

        fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            runCatching {
                manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId, sizeOf(manager, appWidgetId)))
            }
        }

        private fun sizeOf(manager: AppWidgetManager, appWidgetId: Int): SizeF {
            val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }.getOrNull()
                ?: return DEFAULT_SHORTCUT_SIZE

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
                DEFAULT_SHORTCUT_SIZE
            }
        }

        private fun buildViews(context: Context, appWidgetId: Int, size: SizeF): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_app_shortcut)
            val metrics = shortcutMetricsFor(size)
            val shortcut = AppShortcut.load(context, appWidgetId)

            views.setViewLayoutWidth(R.id.shortcutIcon, metrics.iconDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.shortcutIcon, metrics.iconDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setTextViewTextSize(R.id.shortcutLabel, TypedValue.COMPLEX_UNIT_SP, metrics.labelSp)

            val style = shortcut?.style ?: AppShortcut.DEFAULT_STYLE
            views.applyFrame(context, style, R.id.shortcutRoot, R.id.shortcutInner, metrics.paddingDp)
            style.palette?.let { views.setTextColor(R.id.shortcutLabel, it.text) }

            val icon = shortcut?.let { InstalledApps.icon(context, it.component) }
            if (shortcut == null || icon == null) {
                // 未設定、またはアプリが消えた。タップで設定画面へ送る。
                views.setImageViewResource(R.id.shortcutIcon, R.drawable.ic_shortcut_unset)
                views.setTextViewText(R.id.shortcutLabel, context.getString(R.string.shortcut_unset))
                views.setViewVisibility(R.id.shortcutLabel, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.shortcutRoot, configIntent(context, appWidgetId))
                return views
            }

            val iconPx = context.toPx(metrics.iconDp)
            views.setImageViewBitmap(R.id.shortcutIcon, InstalledApps.toBitmap(icon, iconPx))
            views.setTextViewText(
                R.id.shortcutLabel,
                InstalledApps.label(context, shortcut.component) ?: shortcut.component.packageName,
            )
            views.setViewVisibility(
                R.id.shortcutLabel,
                if (metrics.showLabel) View.VISIBLE else View.GONE,
            )

            val launch = InstalledApps.launchIntent(context, shortcut)
            val pending = launch?.let { target ->
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching {
                    PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        target,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }.getOrNull()
            } ?: configIntent(context, appWidgetId)
            views.setOnClickPendingIntent(R.id.shortcutRoot, pending)
            return views
        }

        private fun configIntent(context: Context, appWidgetId: Int): PendingIntent? {
            val intent = Intent(context, AppShortcutConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                PendingIntent.getActivity(
                    context,
                    CONFIG_REQUEST_BASE + appWidgetId,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }.getOrNull()
        }

        private const val CONFIG_REQUEST_BASE = 1000
    }
}
