package net.shino3.qsmultitools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import android.provider.Settings

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
 */
object ScreenTimeout {

    /** 巡回する候補 (ミリ秒)。昇順であること。 */
    val STEPS = intArrayOf(30_000, 120_000, 600_000, 1_800_000)

    fun status(context: Context): FeatureStatus =
        if (Settings.System.canWrite(context)) FeatureStatus.AVAILABLE else FeatureStatus.NEEDS_PERMISSION

    fun current(context: Context): Int =
        runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrDefault(STEPS[0])

    /**
     * 次の候補。現在値が候補に無くても「今より大きい最初の候補」に落ちるので、
     * 15 秒なら 30 秒、5 分なら 10 分、Int.MAX_VALUE (消灯しない) なら先頭に戻る。
     */
    fun next(current: Int): Int = STEPS.firstOrNull { it > current } ?: STEPS[0]

    fun applyNext(context: Context): Result<Int> {
        val target = next(current(context))
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
 * 本命は Android 標準の [AlarmClock.ACTION_SHOW_ALARMS] (API 19+)。Galaxy 標準の時計アプリも
 * これを受けるので、Samsung 固有の component 名や undocumented な extra には基本的に依存しない。
 * 解決できなかったときだけ時計アプリのランチャー Intent にフォールバックする。
 */
object Alarms {

    private const val SAMSUNG_CLOCK = "com.sec.android.app.clockpackage"
    private const val AOSP_CLOCK = "com.google.android.deskclock"

    data class Target(val intent: Intent, val method: String)

    fun resolve(context: Context): Target? {
        val pm = context.packageManager

        val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        resolve(pm, showAlarms)?.let {
            return Target(showAlarms, "SHOW_ALARMS → ${it.activityInfo.packageName}")
        }

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

    /** resolveActivity(Intent, Int) は API 33 で deprecated。新しい ResolveInfoFlags 版を使う。 */
    private fun resolve(pm: PackageManager, intent: Intent): ResolveInfo? =
        pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
}
