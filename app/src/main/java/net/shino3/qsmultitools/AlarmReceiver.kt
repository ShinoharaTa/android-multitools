package net.shino3.qsmultitools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * アラームの操作と、システムからの知らせをすべてここで受ける。
 *
 * - 発火 / スヌーズ / 停止 / ウィジェットからの ON/OFF
 * - 再起動 (ロック解除前も含む): AlarmManager の登録が消えるので張り直す
 * - 時刻合わせ・タイムゾーン変更: 「7:00」の指す瞬間が変わるので張り直す
 * - 日付の変わり目: ウィジェットの「今日 / 明日」を描き直す
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> AlarmScheduler.fire(context)
            ACTION_SNOOZE -> AlarmScheduler.snooze(context)
            ACTION_DISMISS -> AlarmScheduler.dismiss(context)
            ACTION_TOGGLE -> intent.data?.lastPathSegment?.toIntOrNull()?.let { AlarmScheduler.toggle(context, it) }
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            -> if (context.firstTimeThisBoot("alarm")) AlarmScheduler.onBoot(context) else AlarmScheduler.reschedule(context)
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                AlarmScheduler.reschedule(context)
                AlarmScheduler.scheduleMidnightRefresh(context)
                AlarmWidgetProvider.refresh(context)
            }
            Intent.ACTION_LOCALE_CHANGED -> AlarmWidgetProvider.refresh(context)
            // 日付が変わった。「今日 / 明日」を出しているウィジェットをまとめて描き直す。
            ACTION_MIDNIGHT -> {
                AlarmScheduler.scheduleMidnightRefresh(context)
                AlarmWidgetProvider.refresh(context)
                runCatching { NextAlarmWidgetProvider.refresh(context) }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "net.shino3.qsmultitools.ALARM_FIRE"
        const val ACTION_SNOOZE = "net.shino3.qsmultitools.ALARM_SNOOZE"
        const val ACTION_DISMISS = "net.shino3.qsmultitools.ALARM_DISMISS"
        const val ACTION_TOGGLE = "net.shino3.qsmultitools.ALARM_TOGGLE"
        const val ACTION_MIDNIGHT = "net.shino3.qsmultitools.ALARM_MIDNIGHT"

        fun snoozeIntent(context: Context) = pending(context, ACTION_SNOOZE, 0)
        fun dismissIntent(context: Context) = pending(context, ACTION_DISMISS, 0)
        fun toggleIntent(context: Context, id: Int) = pending(context, ACTION_TOGGLE, id)

        /** アラームごとに別の PendingIntent にするため、ID を data URI に入れる。 */
        private fun pending(context: Context, action: String, id: Int): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("alarm://${action.substringAfterLast('_').lowercase()}/$id"))
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
