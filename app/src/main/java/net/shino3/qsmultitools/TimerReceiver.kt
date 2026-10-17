package net.shino3.qsmultitools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * タイマーの操作をすべてここで受ける。ウィジェットのボタン、通知のボタン、アラームの発火、再起動。
 *
 * ボタンの PendingIntent は長さごとに別物にしたいので、長さを data の URI に入れて区別させる。
 * extra の違いだけでは PendingIntent が同一視され、最後に作ったものに上書きされてしまう。
 */
class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val durationMs = intent.data?.lastPathSegment?.toLongOrNull()
        when (intent.action) {
            ACTION_START -> durationMs?.let { Timer.start(context, it) }
            ACTION_EXTEND -> durationMs?.let { Timer.extend(context, it) }
            ACTION_CANCEL -> Timer.cancel(context)
            ACTION_FIRE -> Timer.fire(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Timer.onBoot(context)
        }
    }

    companion object {
        const val ACTION_START = "net.shino3.qsmultitools.TIMER_START"
        const val ACTION_EXTEND = "net.shino3.qsmultitools.TIMER_EXTEND"
        const val ACTION_CANCEL = "net.shino3.qsmultitools.TIMER_CANCEL"
        const val ACTION_FIRE = "net.shino3.qsmultitools.TIMER_FIRE"

        fun startIntent(context: Context, durationMs: Long): PendingIntent =
            pending(context, ACTION_START, durationMs)

        fun extendIntent(context: Context, durationMs: Long): PendingIntent =
            pending(context, ACTION_EXTEND, durationMs)

        fun cancelIntent(context: Context): PendingIntent = pending(context, ACTION_CANCEL, null)

        private fun pending(context: Context, action: String, durationMs: Long?): PendingIntent {
            val intent = Intent(context, TimerReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("timer://${action.substringAfterLast('_').lowercase()}/${durationMs ?: 0}"))
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
