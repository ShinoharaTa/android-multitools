package net.shino3.qsmultitools

import android.service.quicksettings.Tile

/** タップするたびに USB デバッグを ON/OFF する。 */
class UsbDebugTileService : BaseTileService() {

    override fun refresh() {
        val label = getString(R.string.tile_usb_debug)
        if (!UsbDebug.hasPermission(this)) {
            // STATE_UNAVAILABLE にすると onClick が来なくなり、セットアップ画面へ誘導できない。
            // だから権限が無いときも INACTIVE のままにして、タップでセットアップを開く。
            render(Tile.STATE_INACTIVE, label, "セットアップが必要")
            return
        }
        val on = UsbDebug.isEnabled(this)
        render(if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, label, if (on) "ON" else "OFF")
    }

    override fun onClick() {
        super.onClick()
        if (!UsbDebug.hasPermission(this)) {
            toast("USB デバッグの切り替えには adb からの権限付与が必要です")
            openSetup()
            return
        }
        runUnlocked {
            UsbDebug.toggle(this)
                .onSuccess { on -> toast("USB デバッグ: ${if (on) "ON" else "OFF"}") }
                .onFailure { e -> toast("切り替えられませんでした: ${e.message ?: e.javaClass.simpleName}") }
            refresh()
        }
    }
}
