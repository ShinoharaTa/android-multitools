package net.shino3.qsmultitools

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

/** 各機能がいま使えるかどうか。セットアップ画面とタイルの両方でこれを見る。 */
enum class FeatureStatus {
    /** そのまま使える */
    AVAILABLE,

    /** 端末は対応しているが権限が足りない */
    NEEDS_PERMISSION,

    /** この端末では実現手段がない */
    UNSUPPORTED,
}

/**
 * USB デバッグ (Settings.Global.ADB_ENABLED)。
 *
 * 書き込みには WRITE_SECURE_SETTINGS が要る。これは signature|privileged なので通常のインストールでは
 * 付かず、adb shell pm grant で一度だけ付与する。付与された権限はアプリを消すまで残るため、
 * USB デバッグを OFF にした後でもタイルから ON に戻せる。
 */
object UsbDebug {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun status(context: Context): FeatureStatus =
        if (hasPermission(context)) FeatureStatus.AVAILABLE else FeatureStatus.NEEDS_PERMISSION

    fun isEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        }.getOrDefault(0) == 1

    /**
     * ON/OFF を反転させる。成功したら反転後の値を返す。
     *
     * putInt が true を返しても Knox などの OEM 制御で握りつぶされることがあるので、
     * 書いた後に必ず読み戻して実際に変わったかを確認する。
     */
    fun toggle(context: Context): Result<Boolean> {
        val target = !isEnabled(context)
        return runCatching {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                if (target) 1 else 0,
            )
            check(isEnabled(context) == target) { "設定が反映されませんでした" }
            target
        }
    }

    fun grantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
}

/**
 * 画面消灯までの時間 (Settings.System.SCREEN_OFF_TIMEOUT)。
 *
 * 書き込みには WRITE_SETTINGS が要る。これは appop なので
 * Settings.ACTION_MANAGE_WRITE_SETTINGS の許可画面から本人が許可できる。
 *
 * 巡回する候補は本人が選べる。全部入れるとタップ回数が増えるし、
 * 端末によって欲しい値が違うため。
 */
object ScreenTimeout {

    /** 選べる候補 (ミリ秒)。昇順であること。Int.MAX_VALUE は「消灯しない」。 */
    val CANDIDATES = intArrayOf(
        15_000,
        30_000,
        60_000,
        120_000,
        300_000,
        600_000,
        1_800_000,
        Int.MAX_VALUE,
    )

    /** 既定では 15 秒だけ外しておく。短すぎて巡回に混ざると邪魔になりやすい。 */
    private val DEFAULT_ENABLED = CANDIDATES.filter { it != 15_000 }.toSet()

    private const val PREFS = "screen_timeout"
    private const val KEY_STEPS = "steps"

    fun status(context: Context): FeatureStatus =
        if (Settings.System.canWrite(context)) FeatureStatus.AVAILABLE else FeatureStatus.NEEDS_PERMISSION

    /** 巡回に使う候補。1 つも選ばれていなければ既定に戻す。 */
    fun steps(context: Context): List<Int> {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_STEPS, null)
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in CANDIDATES }
            ?.sorted()
        return if (saved.isNullOrEmpty()) DEFAULT_ENABLED.sorted() else saved
    }

    fun setSteps(context: Context, values: Set<Int>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_STEPS, values.map { it.toString() }.toSet())
            .apply()
    }

    fun current(context: Context): Int =
        runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrDefault(steps(context).first())

    /**
     * 次の候補。現在値が候補に無くても「今より大きい最初の候補」に落ちるので素直に動く。
     * 一番大きい候補にいるときは先頭に戻る。
     */
    fun next(current: Int, steps: List<Int>): Int =
        steps.firstOrNull { it > current } ?: steps.first()

    fun applyNext(context: Context): Result<Int> {
        val target = next(current(context), steps(context))
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, target)
            check(current(context) == target) { "設定が反映されませんでした" }
            target
        }
    }

    fun format(ms: Int): String = when {
        ms >= 24 * 60 * 60 * 1000 -> "消灯しない"
        ms >= 60 * 60 * 1000 -> "${ms / (60 * 60 * 1000)}時間"
        ms >= 60 * 1000 -> "${ms / (60 * 1000)}分"
        else -> "${ms / 1000}秒"
    }

    fun appOpsCommand(context: Context): String =
        "adb shell appops set ${context.packageName} WRITE_SETTINGS allow"
}

/**
 * アラーム一覧を開く。
 *
 * 本命は Android 標準の [AlarmClock.ACTION_SHOW_ALARMS] (API 19+)。ただし暗黙 Intent のまま
 * 投げると、受け手が複数ある端末 (Galaxy に Google 時計も入れている等) ではアプリ選択ダイアログに
 * なり、クイック設定パネルが閉じる動きと重なって何も開かないように見えることがある。
 * そこで受け手を自分で選んで明示コンポーネントにしてから投げる。Galaxy 標準の時計アプリがあれば
 * それを優先する。
 *
 * それでも駄目な端末のために、開くアプリを本人が指定できる ([setConfigured])。
 * 指定があれば自動判定より優先し、タイルとウィジェットの両方がそれを使う。
 */
object Alarms {

    private const val SAMSUNG_CLOCK = "com.sec.android.app.clockpackage"
    private const val AOSP_CLOCK = "com.google.android.deskclock"
    private const val OWN_PACKAGE = "net.shino3.qsmultitools"
    private const val PREFS = "alarm_target"
    private const val KEY_COMPONENT = "component"

    data class Target(val intent: Intent, val method: String)

    /** 本人が指定した開き先。null なら自動。 */
    fun configured(context: Context): ComponentName? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMPONENT, null)
            ?.let { ComponentName.unflattenFromString(it) }

    fun setConfigured(context: Context, component: ComponentName?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_COMPONENT, component?.flattenToString())
            .apply()
    }

    fun resolve(context: Context): Target? {
        val pm = context.packageManager

        configured(context)?.let { chosen ->
            // 指定アプリが SHOW_ALARMS を受けるならアラーム一覧に直行できる。
            explicit(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(chosen.packageName))
                ?.let { return Target(it, "SHOW_ALARMS → ${chosen.packageName} (指定)") }
            // 受けないなら普通にそのアプリを起動する。
            val launch = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(chosen)
            if (resolves(pm, launch)) return Target(launch, "起動 → ${chosen.flattenToShortString()} (指定)")
            pm.getLaunchIntentForPackage(chosen.packageName)
                ?.let { return Target(it, "起動 → ${chosen.packageName} (指定)") }
            // 指定アプリが消えている。自動判定に落ちる。
        }

        explicit(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS))
            ?.let { return Target(it, "SHOW_ALARMS → ${it.component?.packageName}") }

        // ここに来るのは標準 Intent を受ける時計アプリが無い端末。既知の時計アプリを直接開く。
        for (pkg in listOf(SAMSUNG_CLOCK, AOSP_CLOCK)) {
            pm.getLaunchIntentForPackage(pkg)?.let {
                return Target(it, "フォールバック → $pkg")
            }
        }
        return null
    }

    fun status(context: Context): FeatureStatus =
        if (resolve(context) != null) FeatureStatus.AVAILABLE else FeatureStatus.UNSUPPORTED

    /** SHOW_ALARMS を受けるアプリのパッケージ名。設定画面で「対応」印を付けるのに使う。 */
    fun handlerPackages(context: Context): Set<String> =
        runCatching {
            context.packageManager.queryIntentActivities(
                Intent(AlarmClock.ACTION_SHOW_ALARMS),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())

    /**
     * 暗黙 Intent の受け手を自分で 1 つ選び、明示コンポーネントにして返す。
     * 候補が無ければ null。Galaxy 標準の時計アプリがあればそれを取る。
     *
     * resolveActivity() に任せると、受け手が複数のときは選択ダイアログ (ResolverActivity) が
     * 返ってくる。それをタイルから投げるとパネルが閉じる動きに巻き込まれて消えるので使わない。
     */
    private fun explicit(pm: PackageManager, intent: Intent): Intent? {
        val candidates = runCatching {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        }.getOrDefault(emptyList())
        // 自分 (自前アラームの一覧) は自動では選ばない。選びたいときは開き先で明示的に指定してもらう。
        // 指定されたときは setPackage 付きで来るので、候補は自分だけになり、ここで弾かれない。
        val others = candidates.filter { it.activityInfo.packageName != OWN_PACKAGE || intent.`package` != null }
        val pick = others.firstOrNull { it.activityInfo.packageName == SAMSUNG_CLOCK }
            ?: others.firstOrNull()
            ?: return null
        return Intent(intent).setClassName(pick.activityInfo.packageName, pick.activityInfo.name)
    }

    private fun resolves(pm: PackageManager, intent: Intent): Boolean = runCatching {
        pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null
    }.getOrDefault(false)
}

/**
 * 次に鳴るアラーム。
 *
 * [AlarmManager.getNextAlarmClock] は「どのアプリが `setAlarmClock()` で立てたか」に関係なく
 * 端末全体の次の 1 件を返す。権限は要らない。Galaxy 標準の時計アプリもこの経路に乗るので、
 * Samsung 固有の ContentProvider を覗かなくても次のアラームだけは正確に取れる。
 *
 * 逆に、ここから取れるのは「次の 1 件の時刻」と「その時計アプリを開く PendingIntent」だけ。
 * アラームの一覧や個別の ON/OFF を扱う公開 API は存在しない (docs/DESIGN.md 参照)。
 */
object NextAlarm {

    fun info(context: Context): AlarmManager.AlarmClockInfo? =
        runCatching { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock }.getOrNull()

    /** 端末の 12/24 時間表示設定に従って時刻を整形する。 */
    fun formatTime(context: Context, triggerTime: Long): String =
        DateFormat.getTimeFormat(context).format(Date(triggerTime))

    /** 今日 / 明日 / それ以外は曜日つきの日付。日をまたいでも表示が古くならないようにする。 */
    fun formatDay(context: Context, triggerTime: Long): String {
        val target = Instant.ofEpochMilli(triggerTime).atZone(ZoneId.systemDefault()).toLocalDate()
        return when (ChronoUnit.DAYS.between(LocalDate.now(), target)) {
            0L -> context.getString(R.string.day_today)
            1L -> context.getString(R.string.day_tomorrow)
            else -> DateUtils.formatDateTime(
                context,
                triggerTime,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
            )
        }
    }
}
