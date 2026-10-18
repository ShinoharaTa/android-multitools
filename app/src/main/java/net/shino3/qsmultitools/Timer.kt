package net.shino3.qsmultitools

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock

/**
 * 自前のタイマー。同時に動かすのは 1 本だけ。
 *
 * 状態は SharedPreferences (ロック解除前でも読める側, [bootSafePrefs]) に置く。ウィジェット・通知・完了画面はどれもここを読んで描くだけで、
 * 状態を持つのはこのオブジェクトだけにしてある。
 *
 * 終了時刻は elapsedRealtime 基準で持つ。時刻合わせやタイムゾーン変更の影響を受けないため。
 * 再起動で elapsedRealtime は 0 に戻るので、壁時計基準の終了時刻も併せて保存しておき、
 * 起動直後だけそちらで残り時間を計算し直す ([onBoot])。
 */
object Timer {

    enum class Phase { IDLE, RUNNING, RINGING }

    data class State(
        val phase: Phase,
        /** RUNNING / RINGING のとき、鳴る (鳴った) 時刻。elapsedRealtime 基準。 */
        val endElapsed: Long,
        /** 同じ時刻の壁時計表現。再起動をまたぐときだけ使う。 */
        val endWall: Long,
        /** 最初に開始したときの長さ。表示用。延長しても変えない。 */
        val totalMs: Long,
    ) {
        val remainingMs: Long get() = (endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    private const val PREFS = "timer"
    private const val KEY_PHASE = "phase"
    private const val KEY_END_ELAPSED = "end_elapsed"
    private const val KEY_END_WALL = "end_wall"
    private const val KEY_TOTAL = "total"

    fun state(context: Context): State {
        val prefs = context.bootSafePrefs(PREFS)
        val phase = prefs.getString(KEY_PHASE, null)
            ?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: Phase.IDLE
        return State(
            phase = phase,
            endElapsed = prefs.getLong(KEY_END_ELAPSED, 0),
            endWall = prefs.getLong(KEY_END_WALL, 0),
            totalMs = prefs.getLong(KEY_TOTAL, 0),
        )
    }

    private fun save(context: Context, state: State) {
        // 状態の書き込みの直後にアラームを張るので、apply ではなく commit で確実に残す。
        context.bootSafePrefs(PREFS).edit()
            .putString(KEY_PHASE, state.phase.name)
            .putLong(KEY_END_ELAPSED, state.endElapsed)
            .putLong(KEY_END_WALL, state.endWall)
            .putLong(KEY_TOTAL, state.totalMs)
            .commit()
    }

    // ---- 操作 -----------------------------------------------------------

    /** 新しく開始する。動いていても鳴っていても、今のものは捨てて置き換える。 */
    fun start(context: Context, durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val state = State(
            phase = Phase.RUNNING,
            endElapsed = now + durationMs,
            endWall = System.currentTimeMillis() + durationMs,
            totalMs = durationMs,
        )
        save(context, state)
        schedule(context, state)
        // 鳴っているところに新しく始めた場合は、これで音が止まる。
        RingService.update(context)
        changed(context)
    }

    /**
     * 延長する。動いているなら終了時刻を後ろにずらし、鳴っているなら止めて「今から N 分」で再開する。
     * 止まっているときは何もしない。
     */
    fun extend(context: Context, durationMs: Long) {
        val current = state(context)
        when (current.phase) {
            Phase.IDLE -> return
            Phase.RINGING -> {
                val state = current.copy(
                    phase = Phase.RUNNING,
                    endElapsed = SystemClock.elapsedRealtime() + durationMs,
                    endWall = System.currentTimeMillis() + durationMs,
                )
                save(context, state)
                schedule(context, state)
                RingService.update(context)
            }
            Phase.RUNNING -> {
                val state = current.copy(
                    endElapsed = current.endElapsed + durationMs,
                    endWall = current.endWall + durationMs,
                )
                save(context, state)
                schedule(context, state)
            }
        }
        changed(context)
    }

    /** 動いていても鳴っていても止めて、待機に戻す。 */
    fun cancel(context: Context) {
        unschedule(context)
        save(context, State(Phase.IDLE, 0, 0, 0))
        RingService.update(context)
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_RUNNING)
        changed(context)
    }

    /** アラームが発火したとき。鳴らし始める。 */
    fun fire(context: Context) {
        val current = state(context)
        if (current.phase != Phase.RUNNING) return
        // 延長の直後に古いアラームが届いた場合に備えて、まだ時間が残っていれば張り直すだけにする。
        if (current.remainingMs > 1_000) {
            schedule(context, current)
            return
        }
        save(context, current.copy(phase = Phase.RINGING))
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_RUNNING)
        RingService.update(context)
        changed(context)
    }

    /** 自動停止で鳴り終わったとき。完了通知だけ残して待機に戻す。 */
    fun finishedSilently(context: Context) {
        val total = state(context).totalMs
        save(context, State(Phase.IDLE, 0, 0, 0))
        RingService.update(context)
        TimerNotifications.postFinished(context, total, missed = false)
        changed(context)
    }

    /**
     * 再起動の直後。elapsedRealtime がリセットされているので壁時計から残りを出し直す。
     * 再起動中に終わっていたら、鳴らさずに「終わっていた」通知だけ出す。
     * BOOT_COMPLETED からは mediaPlayback の FGS を起動できない (Android 15) ため。
     */
    fun onBoot(context: Context) {
        val current = state(context)
        if (current.phase == Phase.IDLE) {
            changed(context)
            return
        }
        val remaining = current.endWall - System.currentTimeMillis()
        if (current.phase == Phase.RUNNING && remaining > 0) {
            val state = current.copy(endElapsed = SystemClock.elapsedRealtime() + remaining)
            save(context, state)
            schedule(context, state)
        } else {
            save(context, State(Phase.IDLE, 0, 0, 0))
            TimerNotifications.postFinished(context, current.totalMs, missed = true)
        }
        changed(context)
    }

    // ---- アラーム -------------------------------------------------------

    /**
     * setAlarmClock は使わない。使うと getNextAlarmClock() に出てしまい、
     * 「次のアラーム」ウィジェットとステータスバーの時計アイコンがタイマーに乗っ取られる。
     * setExactAndAllowWhileIdle なら Doze 中でも鳴り、発火時に FGS を起動してよい一時許可も付く。
     * 権限は USE_EXACT_ALARM (インストール時に自動で付く) で足りる。
     */
    private fun schedule(context: Context, state: State) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            state.endElapsed,
            firePendingIntent(context),
        )
        TimerNotifications.postRunning(context, state)
    }

    private fun unschedule(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(firePendingIntent(context))
    }

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_FIRE,
            Intent(context, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 状態が変わったことを、描画している側すべてに知らせる。 */
    private fun changed(context: Context) {
        TimerWidgetProvider.refresh(context)
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    /** 完了画面が「もう鳴っていない」ことを知るためのアプリ内ブロードキャスト。 */
    const val ACTION_CHANGED = "net.shino3.qsmultitools.TIMER_CHANGED"

    const val NOTIFY_RUNNING = 100
    const val NOTIFY_RINGING = 101
    const val NOTIFY_FINISHED = 102

    private const val REQ_FIRE = 200

    // ---- 表示 -----------------------------------------------------------

    /** 「3分」「1分30秒」「1時間」のような長さの表記。 */
    fun formatDuration(ms: Long): String {
        val totalSec = (ms + 500) / 1000
        val h = totalSec / 3600
        val m = totalSec % 3600 / 60
        val s = totalSec % 60
        return buildString {
            if (h > 0) append("${h}時間")
            if (m > 0) append("${m}分")
            if (s > 0 || isEmpty()) append("${s}秒")
        }
    }

    /** 延長ボタンに出す短い表記。「+1分」 */
    fun formatExtend(ms: Long): String = "+" + formatDuration(ms)

    /** 鳴っている時間の上限。止めるまで鳴らすモードでは null。 */
    fun ringLimitMs(context: Context): Long? = TimerSettings.ringMode(context).limitMs

    /** バッテリーの最適化が外れているか。外れていないと One UI はアプリを眠らせることがある。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
}

/** 鳴り終わりの扱い。 */
enum class RingMode(val labelRes: Int, val limitMs: Long?) {
    UNTIL_STOPPED(R.string.ring_until_stopped, null),
    SEC_15(R.string.ring_15s, 15_000),
    SEC_30(R.string.ring_30s, 30_000),
    MIN_1(R.string.ring_1m, 60_000),
    MIN_3(R.string.ring_3m, 180_000),
    MIN_5(R.string.ring_5m, 300_000),
}

/** 開始ボタンの候補と鳴り方。セットアップ画面から変える。 */
object TimerSettings {

    /** 開始ボタンに出せる候補 (ミリ秒)。昇順であること。 */
    val PRESET_CANDIDATES = longArrayOf(
        30_000,
        60_000,
        120_000,
        180_000,
        300_000,
        600_000,
        900_000,
        1_800_000,
        3_600_000,
    )

    private val DEFAULT_PRESETS = setOf(180_000L, 300_000L, 600_000L)

    /** 延長ボタン。完了画面には全部、ウィジェットと通知には先頭 2 つを出す。 */
    val EXTENDS = longArrayOf(60_000, 180_000, 300_000)

    /** 動いている間の延長ボタン。 */
    const val RUNNING_EXTEND_MS = 60_000L

    private const val PREFS = "timer_settings"
    private const val KEY_PRESETS = "presets"
    private const val KEY_RING_MODE = "ring_mode"
    private const val KEY_VIBRATE = "vibrate"

    fun presets(context: Context): List<Long> {
        val saved = context.bootSafePrefs(PREFS)
            .getStringSet(KEY_PRESETS, null)
            ?.mapNotNull { it.toLongOrNull() }
            ?.filter { it in PRESET_CANDIDATES }
            ?.sorted()
        return if (saved.isNullOrEmpty()) DEFAULT_PRESETS.sorted() else saved
    }

    fun setPresets(context: Context, values: Set<Long>) {
        context.bootSafePrefs(PREFS).edit()
            .putStringSet(KEY_PRESETS, values.map { it.toString() }.toSet())
            .apply()
    }

    fun ringMode(context: Context): RingMode =
        context.bootSafePrefs(PREFS).getString(KEY_RING_MODE, null)
            ?.let { runCatching { RingMode.valueOf(it) }.getOrNull() } ?: RingMode.UNTIL_STOPPED

    fun setRingMode(context: Context, mode: RingMode) {
        context.bootSafePrefs(PREFS).edit()
            .putString(KEY_RING_MODE, mode.name)
            .apply()
    }

    fun vibrate(context: Context): Boolean =
        context.bootSafePrefs(PREFS).getBoolean(KEY_VIBRATE, true)

    fun setVibrate(context: Context, value: Boolean) {
        context.bootSafePrefs(PREFS).edit()
            .putBoolean(KEY_VIBRATE, value)
            .apply()
    }
}

/**
 * タイマーの通知。
 *
 * - 動作中: 静かな常駐通知。残り時間は Chronometer のカウントダウンで OS に描かせるので、
 *   毎秒の更新はしない。
 * - 鳴動中: 全画面インテント付き。ロック中・画面オフなら完了画面がそのまま開き、
 *   使用中ならヘッドアップ通知として出る。音は [RingService] が鳴らすのでチャンネルは無音。
 * - 終了: 自動停止で鳴り終わった、または再起動中に終わっていたときに残す。
 */
object TimerNotifications {

    private const val CHANNEL_RUNNING = "timer_running"
    private const val CHANNEL_RINGING = "timer_ringing"
    const val CHANNEL_FALLBACK = "ring_fallback"
    private const val CHANNEL_FINISHED = "timer_finished"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // テスト版 0.3.0-test1 で作っていたタイマー専用の予備チャンネル。共用に置き換えた。
        manager.deleteNotificationChannel("timer_fallback")
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_RUNNING,
                    context.getString(R.string.channel_timer_running),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
                NotificationChannel(
                    CHANNEL_RINGING,
                    context.getString(R.string.channel_timer_ringing),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    setShowBadge(false)
                },
                // FGS を起動できなかったときの保険。チャンネル自体にアラーム音を持たせる。
                // タイマーとアラームで共用する。
                NotificationChannel(
                    CHANNEL_FALLBACK,
                    context.getString(R.string.channel_ring_fallback),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(alarmSound(), alarmAudioAttributes())
                    enableVibration(true)
                    setBypassDnd(true)
                },
                NotificationChannel(
                    CHANNEL_FINISHED,
                    context.getString(R.string.channel_timer_finished),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { setSound(null, null) },
            ),
        )
    }

    fun postRunning(context: Context, state: Timer.State) {
        ensureChannels(context)
        val notification = Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.timer_running_title, Timer.formatDuration(state.totalMs)))
            .setContentText(context.getString(R.string.timer_running_text))
            .setWhen(state.endWall)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(openApp(context))
            .addAction(action(context, TimerReceiver.extendIntent(context, TimerSettings.RUNNING_EXTEND_MS),
                Timer.formatExtend(TimerSettings.RUNNING_EXTEND_MS)))
            .addAction(action(context, TimerReceiver.cancelIntent(context), context.getString(R.string.timer_cancel)))
            .build()
        notify(context, Timer.NOTIFY_RUNNING, notification)
    }

    /** [RingService] が鳴らす前に止められたときだけ使う、中身の無い通知。 */
    fun placeholder(context: Context): Notification {
        ensureChannels(context)
        return Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.app_name))
            .build()
    }

    /** 鳴動中の通知。FGS の通知としても、保険の通知としても使う。 */
    fun ringing(context: Context, fallback: Boolean): Notification {
        ensureChannels(context)
        val state = Timer.state(context)
        val fullScreen = PendingIntent.getActivity(
            context,
            REQ_RINGING_SCREEN,
            Intent(context, TimerRingingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, if (fallback) CHANNEL_FALLBACK else CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.timer_done_title))
            .setContentText(context.getString(R.string.timer_done_text, Timer.formatDuration(state.totalMs)))
            .setWhen(state.endWall)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            // FGS の通知は状態が変わるたびに出し直すので、そのたびに鳴らないようにする。
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(action(context, TimerReceiver.cancelIntent(context), context.getString(R.string.timer_stop)))
        TimerSettings.EXTENDS.take(2).forEach { ms ->
            builder.addAction(action(context, TimerReceiver.extendIntent(context, ms), Timer.formatExtend(ms)))
        }
        return builder.build().apply {
            // 保険のときはチャンネルの音を止めるまで繰り返させる。
            if (fallback) flags = flags or Notification.FLAG_INSISTENT
        }
    }

    fun postFinished(context: Context, totalMs: Long, missed: Boolean) {
        ensureChannels(context)
        val text = context.getString(
            if (missed) R.string.timer_finished_missed else R.string.timer_finished_text,
            Timer.formatDuration(totalMs),
        )
        val notification = Notification.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.timer_done_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, Timer.NOTIFY_FINISHED, notification)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        // 通知の許可が無いと黙って捨てられるだけで例外にはならないが、念のため握りつぶす。
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(id, notification) }
    }

    private fun action(context: Context, intent: PendingIntent, label: String): Notification.Action =
        Notification.Action.Builder(null, label, intent).build()

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_OPEN_APP,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun alarmSound(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private const val REQ_RINGING_SCREEN = 210
    private const val REQ_OPEN_APP = 211
}
