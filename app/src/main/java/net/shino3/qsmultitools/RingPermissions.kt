package net.shino3.qsmultitools

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager

/**
 * アラームとタイマーを確実に鳴らすために揃えておきたい 3 つの許可。
 *
 * どれが欠けても一応は鳴るが、通知が無ければ残り時間も鳴動の操作も見えず、
 * 全画面通知が無ければロック中に完了画面が開かず、最適化が効いていると One UI が
 * アプリを眠らせて遅れることがある。ホーム画面の注意書きとツール画面の両方がこれを見る。
 */
object RingPermissions {

    data class Status(val notifications: Boolean, val fullScreen: Boolean, val battery: Boolean) {
        val ready: Boolean get() = notifications && fullScreen && battery
    }

    fun check(context: Context): Status = Status(
        notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        fullScreen = context.getSystemService(NotificationManager::class.java)
            ?.canUseFullScreenIntent() == true,
        battery = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
    )
}
