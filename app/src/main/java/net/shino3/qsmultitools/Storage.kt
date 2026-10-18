package net.shino3.qsmultitools

import android.content.Context
import android.content.SharedPreferences

/**
 * 端末のロック解除前でも読める SharedPreferences (device protected storage)。
 *
 * 再起動した直後、まだ一度もロックを解除していない間は通常の保存領域が読めない。
 * Galaxy は既定で深夜に自動再起動することがあるので、アラームとタイマーの状態はこちらに置き、
 * ロック解除前でも鳴らせるようにする。中身はアラームの時刻程度で、暗号化を緩めて困るものはない。
 */
fun Context.bootSafePrefs(name: String): SharedPreferences =
    createDeviceProtectedStorageContext().getSharedPreferences(name, Context.MODE_PRIVATE)

/**
 * 以前は通常の保存領域に置いていた設定を device protected storage へ移す。
 * 移し終えたもの、もともと無いものは何もしない。ロック解除後に呼ぶこと。
 */
fun Context.migrateToBootSafePrefs(vararg names: String) {
    val target = createDeviceProtectedStorageContext()
    names.forEach { runCatching { target.moveSharedPreferencesFrom(this, it) } }
}

/**
 * この起動で初めて呼ばれたときだけ true。
 *
 * 再起動の後始末は LOCKED_BOOT_COMPLETED と BOOT_COMPLETED の両方で走らせたいが、
 * 2 回目でやり直すと、その間に鳴り始めたものを「再起動で止まったもの」と取り違える。
 * Settings.Global.BOOT_COUNT を覚えておき、同じ起動の 2 回目は飛ばす。
 */
fun Context.firstTimeThisBoot(key: String): Boolean {
    val bootCount = runCatching {
        android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT)
    }.getOrNull() ?: return true
    val prefs = bootSafePrefs("boot")
    if (prefs.getInt(key, -1) == bootCount) return false
    prefs.edit().putInt(key, bootCount).commit()
    return true
}
