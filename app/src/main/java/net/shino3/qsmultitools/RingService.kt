package net.shino3.qsmultitools

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 鳴らす側。アラームとタイマーで 1 つを共用する。
 *
 * 状態は持たない。鳴らすべきかどうかは毎回 [RingSource] に聞く。
 * アラームとタイマーが同時に鳴っても、音とバイブは 1 本だけにする。
 */
enum class RingSource(val notifyId: Int) {
    // 先に書いたものが前景サービスの通知になる。起きるためのアラームの方を優先する。
    ALARM(AlarmStore.NOTIFY_RINGING) {
        override fun isRinging(context: Context) = AlarmStore.ringingIds(context).isNotEmpty()
        override fun notification(context: Context, fallback: Boolean) =
            AlarmNotifications.ringing(context, fallback)
        override fun limitMs(context: Context) = AlarmSettings.ringMode(context).limitMs
        override fun onLimit(context: Context) = AlarmScheduler.snooze(context)
        override fun vibrate(context: Context) = AlarmSettings.vibrate(context)
    },
    TIMER(Timer.NOTIFY_RINGING) {
        override fun isRinging(context: Context) = Timer.state(context).phase == Timer.Phase.RINGING
        override fun notification(context: Context, fallback: Boolean) =
            TimerNotifications.ringing(context, fallback)
        override fun limitMs(context: Context) = Timer.ringLimitMs(context)
        override fun onLimit(context: Context) = Timer.finishedSilently(context)
        override fun vibrate(context: Context) = TimerSettings.vibrate(context)
    },
    ;

    abstract fun isRinging(context: Context): Boolean
    abstract fun notification(context: Context, fallback: Boolean): Notification

    /** 鳴らし続ける上限。null なら止めるまで鳴らす。 */
    abstract fun limitMs(context: Context): Long?

    /** 上限まで鳴ったときの後始末。 */
    abstract fun onLimit(context: Context)
    abstract fun vibrate(context: Context): Boolean
}

/**
 * 鳴らしている間だけ動く前景サービス。音とバイブを持つ。
 *
 * 型は mediaPlayback。exact alarm の発火から起動するので、バックグラウンドからの FGS 起動制限の
 * 例外に入る。起動できなかったときは、アラーム音付きのチャンネルで通知だけ出して鳴らす ([update])。
 *
 * 既定のアラーム音が読めないとき (ロック解除前で着信音の保存先が読めない等) は、
 * ToneGenerator の電子音で代わりに鳴らす。無音にはしない。
 */
class RingService : Service() {

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val limits = mutableMapOf<RingSource, Runnable>()

    private val beep = object : Runnable {
        override fun run() {
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_MS)
            handler.postDelayed(this, BEEP_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refresh()
        return START_STICKY
    }

    private fun refresh() {
        val active = RingSource.entries.filter { it.isRinging(this) }
        val manager = getSystemService(NotificationManager::class.java)

        if (active.isEmpty()) {
            // 起動を頼んでから onStartCommand までの間に止められた場合。startForegroundService で
            // 起動された以上は startForeground を一度呼ばないと落とされるので、全画面インテントの無い
            // 仮の通知で済ませてすぐ止める。鳴動の通知を使うと完了画面が一瞬開いてしまう。
            startForeground(
                PLACEHOLDER_ID,
                TimerNotifications.placeholder(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val primary = active.first()
        startForeground(
            primary.notifyId,
            primary.notification(this, fallback = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        active.drop(1).forEach { runCatching { manager?.notify(it.notifyId, it.notification(this, false)) } }
        RingSource.entries.filter { it !in active }.forEach { source ->
            manager?.cancel(source.notifyId)
            limits.remove(source)?.let(handler::removeCallbacks)
        }

        // 鳴り始めたものにだけ上限を仕掛ける。鳴り続けているものの上限は延ばさない。
        active.filter { it !in limits }.forEach { source ->
            val ms = source.limitMs(this) ?: return@forEach
            val runnable = Runnable {
                limits.remove(source)
                source.onLimit(this)
            }
            limits[source] = runnable
            handler.postDelayed(runnable, ms)
        }

        if (player == null && tone == null) play()
        val wantVibrate = active.any { it.vibrate(this) }
        if (wantVibrate && vibrator == null) startVibration()
        if (!wantVibrate && vibrator != null) {
            vibrator?.cancel()
            vibrator = null
        }
    }

    private fun play() {
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(TimerNotifications.alarmAudioAttributes())
                setDataSource(this@RingService, TimerNotifications.alarmSound())
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        if (player == null) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            handler.post(beep)
        }
    }

    private fun startVibration() {
        vibrator = getSystemService(Vibrator::class.java)?.also {
            it.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        limits.clear()
        player?.runCatching { stop(); release() }
        player = null
        tone?.release()
        tone = null
        vibrator?.cancel()
        vibrator = null
        val manager = getSystemService(NotificationManager::class.java)
        RingSource.entries.filter { !it.isRinging(this) }.forEach { manager?.cancel(it.notifyId) }
        super.onDestroy()
    }

    companion object {

        private const val PLACEHOLDER_ID = 103
        private const val BEEP_MS = 800
        private const val BEEP_INTERVAL_MS = 1_500L

        /**
         * 状態を変えたら必ず呼ぶ。鳴らすべきものがあればサービスを起動 (または描き直し) し、
         * 無ければ止める。
         */
        fun update(context: Context) {
            val active = RingSource.entries.filter { it.isRinging(context) }
            val manager = context.getSystemService(NotificationManager::class.java)
            if (active.isEmpty()) {
                context.stopService(Intent(context, RingService::class.java))
                // 保険の通知で鳴らしていた場合はサービスが無いので、通知を直接消して音を止める。
                RingSource.entries.forEach { manager?.cancel(it.notifyId) }
                return
            }
            RingSource.entries.filter { it !in active }.forEach { manager?.cancel(it.notifyId) }
            try {
                context.startForegroundService(Intent(context, RingService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException など。音付きの通知で代わりに鳴らす。
                active.forEach { source ->
                    runCatching { manager?.notify(source.notifyId, source.notification(context, fallback = true)) }
                }
            }
        }
    }
}
