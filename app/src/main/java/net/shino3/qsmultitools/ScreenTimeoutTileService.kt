package net.shino3.qsmultitools

import android.service.quicksettings.Tile

/** タップするたびに画面消灯までの時間を、選ばれている候補で巡回させる。 */
class ScreenTimeoutTileService : BaseTileService() {

    override fun refresh() {
        val label = getString(R.string.tile_screen_timeout)
        if (ScreenTimeout.status(this) != FeatureStatus.AVAILABLE) {
            render(Tile.STATE_INACTIVE, label, "セットアップが必要")
            return
        }
        render(Tile.STATE_ACTIVE, label, ScreenTimeout.format(ScreenTimeout.current(this)))
    }

    override fun onClick() {
        super.onClick()
        if (ScreenTimeout.status(this) != FeatureStatus.AVAILABLE) {
            toast("「システム設定の変更」の許可が必要です")
            openSetup()
            return
        }
        ScreenTimeout.applyNext(this)
            .onFailure { e -> toast("変更できませんでした: ${e.message ?: e.javaClass.simpleName}") }
        refresh()
    }
}
