package net.shino3.qsmultitools

import android.service.quicksettings.Tile

/** タップすると時計アプリのアラーム一覧を開く。 */
class AlarmTileService : BaseTileService() {

    override fun refresh() {
        val label = getString(R.string.tile_alarm)
        val target = Alarms.resolve(this)
        if (target == null) {
            render(Tile.STATE_UNAVAILABLE, label, "時計アプリなし")
            return
        }
        render(Tile.STATE_INACTIVE, label, "一覧を開く")
    }

    override fun onClick() {
        super.onClick()
        val target = Alarms.resolve(this)
        if (target == null) {
            toast("アラーム一覧を開けるアプリが見つかりません")
            return
        }
        launchAndCollapse(target.intent, REQ_ALARM)
    }
}
