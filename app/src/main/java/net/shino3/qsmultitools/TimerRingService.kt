package net.shino3.qsmultitools

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 鳴らしている間だけ動く前景サービス。音とバイブを持つ。
 *
 * 型は mediaPlayback。アラームの発火 (setExactAndAllowWhileIdle) から起動するので、
 * バックグラウンドからの FGS 起動制限の例外に入る。起動できなかったときは、
 * アラーム音付きのチャンネルで通知だけ出して鳴らす ([start] の catch)。
 */
class TimerRingService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable {
        Timer.finishedSilently(this)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            Timer.NOTIFY_RINGING,
            TimerNotifications.ringing(this, fallback = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        // サービスが殺されて再起動されたときに、もう鳴らす必要がなければ何もせず終わる。
        if (Timer.state(this).phase != Timer.Phase.RINGING) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (player == null) play()
        handler.removeCallbacks(autoStop)
        Timer.ringLimitMs(this)?.let { handler.postDelayed(autoStop, it) }
        return START_STICKY
    }

    private fun play() {
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(TimerNotifications.alarmAudioAttributes())
                setDataSource(this@TimerRingService, TimerNotifications.alarmSound())
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()

        if (TimerSettings.vibrate(this)) {
            vibrator = getSystemService(Vibrator::class.java)?.also {
                it.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        player?.runCatching { stop(); release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        getSystemService(NotificationManager::class.java)?.cancel(Timer.NOTIFY_RINGING)
        super.onDestroy()
    }

    companion object {

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, TimerRingService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException など。音付きの通知で代わりに鳴らす。
                runCatching {
                    context.getSystemService(NotificationManager::class.java)
                        ?.notify(Timer.NOTIFY_RINGING, TimerNotifications.ringing(context, fallback = true))
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TimerRingService::class.java))
            // 保険の通知で鳴らしていた場合はサービスが無いので、通知を直接消して音を止める。
            context.getSystemService(NotificationManager::class.java)?.cancel(Timer.NOTIFY_RINGING)
        }
    }
}
