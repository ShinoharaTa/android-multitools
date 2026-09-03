package net.shino3.qsmultitools

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * タイル 3 種の共通処理。
 *
 * ACTIVE_TILE は宣言していない (= 通常モード)。設定アプリ側で値が変わることがあるので、
 * クイック設定パネルを開くたびに読み直したい。通常モードでもバインドされるのはパネル表示中だけなので、
 * 常駐にはならない。
 */
abstract class BaseTileService : TileService() {

    /** 現在の状態をタイルへ反映する。 */
    protected abstract fun refresh()

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    protected fun render(state: Int, label: String, subtitle: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = label
        tile.subtitle = subtitle
        tile.stateDescription = subtitle
        tile.contentDescription = "$label: $subtitle"
        tile.updateTile()
    }

    /** 権限が足りないときの導線。セットアップ画面を開く。 */
    protected fun openSetup() {
        launchAndCollapse(Intent(this, MainActivity::class.java), REQ_SETUP)
    }

    /**
     * startActivityAndCollapse(Intent) は targetSdk 34 以上だと UnsupportedOperationException を投げる。
     * minSdk 34 なので PendingIntent 版だけを使う。
     */
    protected fun launchAndCollapse(intent: Intent, requestCode: Int) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val pending = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } catch (e: Exception) {
            toast("画面を開けませんでした: ${e.javaClass.simpleName}")
        }
    }

    /** ロック中に実行したくない操作をくるむ。 */
    protected fun runUnlocked(block: () -> Unit) {
        if (isSecure) unlockAndRun(block) else block()
    }

    protected fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val REQ_SETUP = 1
        const val REQ_ALARM = 2
    }
}
