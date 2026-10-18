package net.shino3.qsmultitools

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar

/**
 * 自前のアラーム 1 件。
 *
 * [days] は曜日のビット列。`DayOfWeek.value - 1` 番目のビットが立っていればその曜日に鳴る
 * (月曜 = bit0 … 日曜 = bit6)。0 なら繰り返さない 1 回きりのアラーム。
 *
 * [snoozeUntil] はスヌーズ中の再鳴動時刻 (壁時計)。過ぎていれば無視する。
 * スヌーズ中は ON/OFF に関係なく鳴る。1 回きりのアラームは鳴った時点で OFF にするため。
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val label: String,
    val enabled: Boolean,
    val snoozeUntil: Long = 0,
) {
    val repeating: Boolean get() = days != 0

    /** 鳴る予定があるか。ON か、スヌーズ中。一覧とウィジェットのスイッチはこれを表す。 */
    fun isActive(nowMs: Long): Boolean = enabled || snoozeUntil > nowMs

    fun repeatsOn(day: DayOfWeek): Boolean = days and (1 shl (day.value - 1)) != 0

    /** 次に鳴る時刻 (壁時計)。鳴らないなら null。 */
    fun nextTrigger(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (snoozeUntil > nowMs) return snoozeUntil
        if (!enabled) return null
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        // 今日から 7 日後まで見れば、どの曜日指定でも必ず 1 回は当たる。
        for (offset in 0..7L) {
            val date = now.toLocalDate().plusDays(offset)
            if (repeating && !repeatsOn(date.dayOfWeek)) continue
            // 夏時間の切り替えで存在しない時刻は、ZonedDateTime が後ろへずらしてくれる。
            val at = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)
            if (at.isAfter(now)) return at.toInstant().toEpochMilli()
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hour", hour)
        .put("minute", minute)
        .put("days", days)
        .put("label", label)
        .put("enabled", enabled)
        .put("snoozeUntil", snoozeUntil)

    companion object {
        const val EVERY_DAY = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000

        fun fromJson(json: JSONObject) = Alarm(
            id = json.getInt("id"),
            hour = json.getInt("hour"),
            minute = json.getInt("minute"),
            days = json.optInt("days", 0),
            label = json.optString("label", ""),
            enabled = json.optBoolean("enabled", true),
            snoozeUntil = json.optLong("snoozeUntil", 0),
        )
    }
}

/**
 * アラームの保存先。ロック解除前でも読めるよう [bootSafePrefs] に JSON で置く。
 *
 * 鳴っている最中のアラーム ID と、AlarmManager に張っている「次の 1 回」もここに持つ。
 * 発火したときに「どれが鳴ったのか」を、時刻を計算し直さずに知るため。
 */
object AlarmStore {

    private const val PREFS = "alarms"
    private const val KEY_LIST = "list"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_SCHEDULED_AT = "scheduled_at"
    private const val KEY_SCHEDULED_IDS = "scheduled_ids"
    private const val KEY_RINGING_IDS = "ringing_ids"
    private const val KEY_RINGING_SINCE = "ringing_since"

    const val NOTIFY_RINGING = 110
    const val NOTIFY_MISSED = 111

    private fun prefs(context: Context) = context.bootSafePrefs(PREFS)

    fun all(context: Context): List<Alarm> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { Alarm.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
    }

    fun get(context: Context, id: Int): Alarm? = all(context).firstOrNull { it.id == id }

    fun saveAll(context: Context, alarms: List<Alarm>) {
        val array = JSONArray()
        alarms.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_LIST, array.toString()).commit()
    }

    fun newId(context: Context): Int {
        val prefs = prefs(context)
        val id = prefs.getInt(KEY_NEXT_ID, 1)
        prefs.edit().putInt(KEY_NEXT_ID, id + 1).commit()
        return id
    }

    fun scheduled(context: Context): Pair<Long, Set<Int>> {
        val prefs = prefs(context)
        return prefs.getLong(KEY_SCHEDULED_AT, 0) to
            (prefs.getStringSet(KEY_SCHEDULED_IDS, null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet())
    }

    fun setScheduled(context: Context, at: Long, ids: Set<Int>) {
        prefs(context).edit()
            .putLong(KEY_SCHEDULED_AT, at)
            .putStringSet(KEY_SCHEDULED_IDS, ids.map { it.toString() }.toSet())
            .commit()
    }

    fun ringingIds(context: Context): Set<Int> =
        prefs(context).getStringSet(KEY_RINGING_IDS, null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()

    /** 鳴り始めた時刻 (壁時計)。完了画面の経過表示に使う。 */
    fun ringingSince(context: Context): Long = prefs(context).getLong(KEY_RINGING_SINCE, 0)

    fun setRinging(context: Context, ids: Set<Int>, since: Long) {
        prefs(context).edit()
            .putStringSet(KEY_RINGING_IDS, ids.map { it.toString() }.toSet())
            .putLong(KEY_RINGING_SINCE, since)
            .commit()
    }
}

/**
 * アラームの登録と、鳴った後の扱い。
 *
 * AlarmManager に張るのは「全アラームの中で次に鳴る 1 回」だけ。同じ時刻のものはまとめて 1 回で鳴らす。
 * 何かが変わるたびに [reschedule] で張り直す。
 *
 * 登録には `setAlarmClock` を使う。Doze を最も強く貫通し、ステータスバーの時計アイコンと
 * `getNextAlarmClock()` にも載る。つまり「次のアラーム」ウィジェットにもそのまま出る。
 */
object AlarmScheduler {

    // ---- 編集 -----------------------------------------------------------

    fun upsert(context: Context, alarm: Alarm) {
        val others = AlarmStore.all(context).filter { it.id != alarm.id }
        AlarmStore.saveAll(context, others + alarm)
        changed(context)
    }

    fun delete(context: Context, id: Int) {
        AlarmStore.saveAll(context, AlarmStore.all(context).filter { it.id != id })
        val ringing = AlarmStore.ringingIds(context)
        if (id in ringing) AlarmStore.setRinging(context, ringing - id, AlarmStore.ringingSince(context))
        changed(context)
        RingService.update(context)
    }

    /** ON/OFF を切り替える。OFF にしたときはスヌーズも取り消す。 */
    fun setEnabled(context: Context, id: Int, enabled: Boolean) {
        val alarm = AlarmStore.get(context, id) ?: return
        upsert(context, alarm.copy(enabled = enabled, snoozeUntil = 0))
    }

    /**
     * スイッチの切り替え。「鳴る予定があるか」を反転させる。
     * スヌーズ中 (1 回きりは発火時に OFF になっている) にスイッチを切れば、スヌーズが取り消される。
     * ON にしたのにスヌーズが消える、という事故を起こさないため。
     */
    fun toggle(context: Context, id: Int) {
        val alarm = AlarmStore.get(context, id) ?: return
        setEnabled(context, id, !alarm.isActive(System.currentTimeMillis()))
    }

    // ---- 鳴動 -----------------------------------------------------------

    /**
     * AlarmManager から呼ばれる。張っておいた 1 回ぶんのアラームを鳴らし始める。
     *
     * 1 回きりのアラームはこの時点で OFF にし、スヌーズはこの時点で消す。
     * こうしておくと「鳴っている最中に次の 1 回を張り直す」ときに、今鳴っているものを
     * もう一度拾ってしまうことがない。
     */
    fun fire(context: Context) {
        val now = System.currentTimeMillis()
        val (at, ids) = AlarmStore.scheduled(context)
        // 張り直しの直後に古い発火が届いた場合。まだ先なら張り直すだけ。
        if (ids.isEmpty() || at - now > EARLY_TOLERANCE_MS) {
            reschedule(context)
            return
        }
        val fired = AlarmStore.all(context).map { alarm ->
            if (alarm.id !in ids) alarm
            else alarm.copy(enabled = alarm.enabled && alarm.repeating, snoozeUntil = 0)
        }
        AlarmStore.saveAll(context, fired)

        val alreadyRinging = AlarmStore.ringingIds(context)
        val since = if (alreadyRinging.isEmpty()) at else AlarmStore.ringingSince(context)
        AlarmStore.setRinging(context, alreadyRinging + ids, since)
        changed(context)
        RingService.update(context)
    }

    /** 鳴っているものをすべて N 分後に鳴らし直す。 */
    fun snooze(context: Context) {
        val ringing = AlarmStore.ringingIds(context)
        if (ringing.isEmpty()) return
        val until = System.currentTimeMillis() + AlarmSettings.snoozeMs(context)
        AlarmStore.saveAll(
            context,
            AlarmStore.all(context).map { if (it.id in ringing) it.copy(snoozeUntil = until) else it },
        )
        AlarmStore.setRinging(context, emptySet(), 0)
        changed(context)
        RingService.update(context)
    }

    /** 鳴っているものを止める。繰り返しのアラームは次の回に、1 回きりは OFF のまま。 */
    fun dismiss(context: Context) {
        AlarmStore.setRinging(context, emptySet(), 0)
        changed(context)
        RingService.update(context)
    }

    /** スヌーズ中のアラームの再鳴動を取り消す。 */
    fun cancelSnooze(context: Context, id: Int) {
        val alarm = AlarmStore.get(context, id) ?: return
        upsert(context, alarm.copy(snoozeUntil = 0))
    }

    /**
     * 再起動の直後。AlarmManager の登録は再起動で消えるので張り直す。
     *
     * 電源が切れている間に鳴るはずだったものがあれば、鳴らさずに「鳴らせなかった」通知を出す。
     * 1 回きりのものは、そのまま放っておくと明日の同じ時刻に鳴ってしまうので OFF にする。
     */
    fun onBoot(context: Context) {
        val now = System.currentTimeMillis()
        val (at, ids) = AlarmStore.scheduled(context)
        if (ids.isNotEmpty() && at in (now - MISSED_WINDOW_MS)..now) {
            val all = AlarmStore.all(context)
            val missed = all.filter { it.id in ids }
            AlarmStore.saveAll(
                context,
                all.map {
                    if (it.id in ids) it.copy(enabled = it.enabled && it.repeating, snoozeUntil = 0) else it
                },
            )
            if (missed.isNotEmpty()) AlarmNotifications.postMissed(context, at, missed)
        }
        // 鳴っている途中で再起動した場合。サービスはもう居ないので、鳴っている扱いを解く。
        AlarmStore.setRinging(context, emptySet(), 0)
        changed(context)
    }

    /** 全アラームから次の 1 回を探して張り直す。無ければ取り消す。 */
    fun reschedule(context: Context) {
        val now = System.currentTimeMillis()
        val next = AlarmStore.all(context)
            .mapNotNull { alarm -> alarm.nextTrigger(now)?.let { alarm.id to it } }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val earliest = next.minOfOrNull { it.second }
        if (earliest == null) {
            manager.cancel(firePendingIntent(context))
            AlarmStore.setScheduled(context, 0, emptySet())
            return
        }
        val ids = next.filter { it.second == earliest }.map { it.first }.toSet()
        AlarmStore.setScheduled(context, earliest, ids)
        manager.setAlarmClock(
            AlarmManager.AlarmClockInfo(earliest, listPendingIntent(context)),
            firePendingIntent(context),
        )
    }

    /**
     * 日付が変わった直後にウィジェットを描き直す予約。「今日 / 明日」の表示を古くしないため。
     *
     * ACTION_DATE_CHANGED は Android 8 以降マニフェストの receiver に届かないので、自分で張る。
     * 鳴らすものではないので RTC (非 wakeup) の set で十分。寝ている間に過ぎても、
     * 次に端末が起きたときに届けば間に合う。
     */
    fun scheduleMidnightRefresh(context: Context) {
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java)?.set(
            AlarmManager.RTC,
            midnight + 1_000,
            PendingIntent.getBroadcast(
                context,
                REQ_MIDNIGHT,
                Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_MIDNIGHT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    /** アラームが変わったことを、描画している側すべてに知らせる。 */
    private fun changed(context: Context) {
        reschedule(context)
        scheduleMidnightRefresh(context)
        AlarmWidgetProvider.refresh(context)
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    /** 一覧画面や完了画面が描き直すためのアプリ内ブロードキャスト。 */
    const val ACTION_CHANGED = "net.shino3.qsmultitools.ALARMS_CHANGED"

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_FIRE,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** ステータスバーの時計アイコンや「次のアラーム」から開く先。 */
    fun listPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQ_LIST,
            Intent(context, AlarmListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val REQ_FIRE = 300
    private const val REQ_LIST = 301
    private const val REQ_MIDNIGHT = 302

    /** 予定より早く届いた発火を「まだ先」とみなす幅。setAlarmClock は正確なので小さくてよい。 */
    private const val EARLY_TOLERANCE_MS = 30_000L

    /** 再起動の前に鳴らせなかったものを通知する範囲。これより古いものは黙って捨てる。 */
    private const val MISSED_WINDOW_MS = 12 * 60 * 60 * 1000L
}

/** アラームの鳴り終わり。上限まで鳴ったら自動でスヌーズする。 */
enum class AlarmRingMode(val labelRes: Int, val limitMs: Long?) {
    UNTIL_STOPPED(R.string.ring_until_stopped, null),
    MIN_1(R.string.alarm_ring_1m, 60_000),
    MIN_3(R.string.alarm_ring_3m, 180_000),
    MIN_5(R.string.alarm_ring_5m, 300_000),
    MIN_10(R.string.alarm_ring_10m, 600_000),
}

object AlarmSettings {

    /** スヌーズの長さの候補 (ミリ秒)。 */
    val SNOOZE_CANDIDATES = longArrayOf(300_000, 600_000, 900_000, 1_200_000, 1_800_000)
    private const val DEFAULT_SNOOZE = 600_000L

    private const val PREFS = "alarm_settings"
    private const val KEY_SNOOZE = "snooze"
    private const val KEY_RING_MODE = "ring_mode"
    private const val KEY_VIBRATE = "vibrate"

    private fun prefs(context: Context) = context.bootSafePrefs(PREFS)

    fun snoozeMs(context: Context): Long =
        prefs(context).getLong(KEY_SNOOZE, DEFAULT_SNOOZE).takeIf { it in SNOOZE_CANDIDATES } ?: DEFAULT_SNOOZE

    fun setSnoozeMs(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_SNOOZE, value).apply()
    }

    fun ringMode(context: Context): AlarmRingMode =
        prefs(context).getString(KEY_RING_MODE, null)
            ?.let { runCatching { AlarmRingMode.valueOf(it) }.getOrNull() } ?: AlarmRingMode.UNTIL_STOPPED

    fun setRingMode(context: Context, mode: AlarmRingMode) {
        prefs(context).edit().putString(KEY_RING_MODE, mode.name).apply()
    }

    fun vibrate(context: Context): Boolean = prefs(context).getBoolean(KEY_VIBRATE, true)

    fun setVibrate(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATE, value).apply()
    }
}

/** 表示用の整形。 */
object AlarmFormat {

    /** 端末の 12/24 時間表示設定に従った「7:00」。 */
    fun time(context: Context, hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return DateFormat.getTimeFormat(context).format(calendar.time)
    }

    /** 日曜始まりの並び。日本のカレンダーに合わせる。 */
    val WEEK_ORDER = listOf(
        DayOfWeek.SUNDAY,
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
    )

    private val SHORT = mapOf(
        DayOfWeek.SUNDAY to "日",
        DayOfWeek.MONDAY to "月",
        DayOfWeek.TUESDAY to "火",
        DayOfWeek.WEDNESDAY to "水",
        DayOfWeek.THURSDAY to "木",
        DayOfWeek.FRIDAY to "金",
        DayOfWeek.SATURDAY to "土",
    )

    fun dayShort(day: DayOfWeek): String = SHORT.getValue(day)

    /** 「毎日」「平日」「週末」「月 水 金」。1 回きりなら「今日」「明日」。 */
    fun repeat(context: Context, alarm: Alarm, nowMs: Long = System.currentTimeMillis()): String =
        when (alarm.days) {
            Alarm.EVERY_DAY -> context.getString(R.string.alarm_every_day)
            Alarm.WEEKDAYS -> context.getString(R.string.alarm_weekdays)
            Alarm.WEEKEND -> context.getString(R.string.alarm_weekend)
            0 -> {
                val next = alarm.copy(enabled = true, snoozeUntil = 0).nextTrigger(nowMs)
                val date = next?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
                if (date == LocalDate.now()) context.getString(R.string.day_today)
                else context.getString(R.string.day_tomorrow)
            }
            else -> WEEK_ORDER.filter { alarm.repeatsOn(it) }.joinToString(" ") { dayShort(it) }
        }

    /** 「あと 8時間12分」。 */
    fun until(context: Context, triggerMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val minutes = ((triggerMs - nowMs + 59_999) / 60_000).coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        val text = when {
            h > 0 && m > 0 -> "${h}時間${m}分"
            h > 0 -> "${h}時間"
            else -> "${m}分"
        }
        return context.getString(R.string.alarm_until, text)
    }
}

object AlarmNotifications {

    private const val CHANNEL_RINGING = "alarm_ringing"
    private const val CHANNEL_MISSED = "alarm_missed"

    private fun ensureChannels(context: Context) {
        TimerNotifications.ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                // 音は RingService が鳴らすのでチャンネルは無音。
                NotificationChannel(
                    CHANNEL_RINGING,
                    context.getString(R.string.channel_alarm_ringing),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_MISSED,
                    context.getString(R.string.channel_alarm_missed),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    /** 鳴動中の通知。FGS の通知としても、保険の通知としても使う。 */
    fun ringing(context: Context, fallback: Boolean): Notification {
        ensureChannels(context)
        val ringing = AlarmStore.ringingIds(context)
        val alarms = AlarmStore.all(context).filter { it.id in ringing }
        val since = AlarmStore.ringingSince(context).takeIf { it > 0 } ?: System.currentTimeMillis()
        val title = context.getString(R.string.alarm_ringing_title, NextAlarm.formatTime(context, since))
        val text = alarms.map { it.label }.filter { it.isNotBlank() }.joinToString(" / ")
            .ifBlank { context.getString(R.string.alarm_ringing_text) }

        val fullScreen = PendingIntent.getActivity(
            context,
            REQ_RINGING_SCREEN,
            Intent(context, AlarmRingingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snoozeLabel = context.getString(
            R.string.alarm_snooze_n,
            Timer.formatDuration(AlarmSettings.snoozeMs(context)),
        )
        return Notification.Builder(context, if (fallback) TimerNotifications.CHANNEL_FALLBACK else CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_tile_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(since)
            .setShowWhen(true)
            .setOngoing(true)
            // setOnlyAlertOnce は付けない (Timer.kt の同じ箇所を参照)。
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(Notification.Action.Builder(null, snoozeLabel, AlarmReceiver.snoozeIntent(context)).build())
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.timer_stop),
                    AlarmReceiver.dismissIntent(context),
                ).build(),
            )
            .build()
            .apply { if (fallback) flags = flags or Notification.FLAG_INSISTENT }
    }

    fun postMissed(context: Context, at: Long, alarms: List<Alarm>) {
        ensureChannels(context)
        val labels = alarms.map { it.label }.filter { it.isNotBlank() }.joinToString(" / ")
        val text = context.getString(R.string.alarm_missed_text, NextAlarm.formatTime(context, at)) +
            if (labels.isNotEmpty()) " ($labels)" else ""
        val notification = Notification.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_tile_alarm)
            .setContentTitle(context.getString(R.string.alarm_missed_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(AlarmScheduler.listPendingIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(AlarmStore.NOTIFY_MISSED, notification) }
    }

    private const val REQ_RINGING_SCREEN = 310
}
