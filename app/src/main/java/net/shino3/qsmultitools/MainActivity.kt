package net.shino3.qsmultitools

import android.app.Activity
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * セットアップ画面。各機能が「利用可能 / 権限不足 / 非対応」のどれなのかを見せて、
 * 足りないものを埋めるための導線だけを置く。設定はここでは持たない。
 */
class MainActivity : Activity() {

    private lateinit var usbStatus: TextView
    private lateinit var usbGrantBlock: View
    private lateinit var usbGrantCommand: TextView

    private lateinit var timeoutStatus: TextView
    private lateinit var timeoutCurrent: TextView
    private lateinit var timeoutGrantBlock: View
    private lateinit var timeoutAdbCommand: TextView

    private lateinit var alarmStatus: TextView
    private lateinit var alarmDetail: TextView

    private lateinit var widgetStatus: TextView
    private lateinit var widgetCurrent: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbStatus = findViewById(R.id.usbStatus)
        usbGrantBlock = findViewById(R.id.usbGrantBlock)
        usbGrantCommand = findViewById(R.id.usbGrantCommand)
        timeoutStatus = findViewById(R.id.timeoutStatus)
        timeoutCurrent = findViewById(R.id.timeoutCurrent)
        timeoutGrantBlock = findViewById(R.id.timeoutGrantBlock)
        timeoutAdbCommand = findViewById(R.id.timeoutAdbCommand)
        alarmStatus = findViewById(R.id.alarmStatus)
        alarmDetail = findViewById(R.id.alarmDetail)
        widgetStatus = findViewById(R.id.widgetStatus)
        widgetCurrent = findViewById(R.id.widgetCurrent)

        usbGrantCommand.text = UsbDebug.grantCommand(this)
        timeoutAdbCommand.text = ScreenTimeout.appOpsCommand(this)

        findViewById<Button>(R.id.btnCopyGrantCommand).setOnClickListener {
            copy("WRITE_SECURE_SETTINGS", UsbDebug.grantCommand(this))
        }
        findViewById<Button>(R.id.btnCopyAppOpsCommand).setOnClickListener {
            copy("WRITE_SETTINGS", ScreenTimeout.appOpsCommand(this))
        }
        findViewById<Button>(R.id.btnOpenWriteSettings).setOnClickListener { openWriteSettings() }
        findViewById<Button>(R.id.btnTestAlarm).setOnClickListener { openAlarms() }
        findViewById<Button>(R.id.btnAddWidget).setOnClickListener { requestPinWidget() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }

        setUpAddTileButtons()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---- 状態表示 -------------------------------------------------------

    private fun refresh() {
        val usb = UsbDebug.status(this)
        render(usbStatus, usb)
        if (usb == FeatureStatus.AVAILABLE) {
            usbStatus.text = getString(
                R.string.usb_current,
                getString(R.string.status_available),
                if (UsbDebug.isEnabled(this)) "ON" else "OFF",
            )
        }
        usbGrantBlock.visibility = if (usb == FeatureStatus.AVAILABLE) View.GONE else View.VISIBLE

        val timeout = ScreenTimeout.status(this)
        render(timeoutStatus, timeout)
        timeoutCurrent.text =
            getString(R.string.timeout_current, ScreenTimeout.format(ScreenTimeout.current(this)))
        timeoutGrantBlock.visibility = if (timeout == FeatureStatus.AVAILABLE) View.GONE else View.VISIBLE

        val alarm = Alarms.resolve(this)
        render(alarmStatus, if (alarm != null) FeatureStatus.AVAILABLE else FeatureStatus.UNSUPPORTED)
        alarmDetail.text = alarm?.let { getString(R.string.alarm_detail, it.method) }
            ?: getString(R.string.alarm_not_found)

        // ウィジェットは権限を使わないので、常に利用可能。
        render(widgetStatus, FeatureStatus.AVAILABLE)
        val next = NextAlarm.info(this)
        widgetCurrent.text = if (next == null) {
            getString(R.string.widget_current_none)
        } else {
            getString(
                R.string.widget_current,
                "${NextAlarm.formatDay(this, next.triggerTime)} " +
                    NextAlarm.formatTime(this, next.triggerTime),
            )
        }
    }

    private fun render(view: TextView, status: FeatureStatus) {
        val (label, color) = when (status) {
            FeatureStatus.AVAILABLE -> R.string.status_available to R.color.status_ok
            FeatureStatus.NEEDS_PERMISSION -> R.string.status_needs_permission to R.color.status_warn
            FeatureStatus.UNSUPPORTED -> R.string.status_unsupported to R.color.status_ng
        }
        view.text = getString(R.string.status_bullet, getString(label))
        view.setTextColor(getColor(color))
    }

    // ---- 導線 -----------------------------------------------------------

    private fun copy(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        // Android 13 以降はシステム側がコピー通知を出すので、こちらでは何も出さない。
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun openWriteSettings() {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        if (!start(withPackage) && !start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))) {
            toast("許可画面を開けませんでした")
        }
    }

    private fun openAlarms() {
        val target = Alarms.resolve(this)
        if (target == null || !start(target.intent)) {
            toast("アラーム一覧を開けませんでした")
        }
    }

    private fun start(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun requestPinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        val provider = ComponentName(this, NextAlarmWidgetProvider::class.java)
        val requested = manager != null &&
            manager.isRequestPinAppWidgetSupported &&
            runCatching { manager.requestPinAppWidget(provider, null, null) }.getOrDefault(false)
        if (!requested) {
            toast(getString(R.string.widget_pin_unsupported))
        }
    }

    // ---- クイック設定への追加 -------------------------------------------

    private fun setUpAddTileButtons() {
        val tiles = listOf(
            Triple(UsbDebugTileService::class.java, R.string.tile_usb_debug, R.drawable.ic_tile_usb_debug),
            Triple(ScreenTimeoutTileService::class.java, R.string.tile_screen_timeout, R.drawable.ic_tile_screen_timeout),
            Triple(AlarmTileService::class.java, R.string.tile_alarm, R.drawable.ic_tile_alarm),
        )
        val buttons = listOf(R.id.btnAddTileUsb, R.id.btnAddTileTimeout, R.id.btnAddTileAlarm)

        tiles.forEachIndexed { index, (cls, labelRes, iconRes) ->
            findViewById<Button>(buttons[index]).apply {
                text = getString(R.string.btn_add_tile, getString(labelRes))
                setOnClickListener { requestAddTile(cls, getString(labelRes), iconRes) }
            }
        }
    }

    private fun requestAddTile(cls: Class<out TileService>, label: String, iconRes: Int) {
        val statusBar = getSystemService(StatusBarManager::class.java)
        if (statusBar == null) {
            toast("クイック設定に追加できませんでした")
            return
        }
        try {
            statusBar.requestAddTileService(
                ComponentName(this, cls),
                label,
                Icon.createWithResource(this, iconRes),
                mainExecutor,
            ) { result -> toast(describeAddTileResult(label, result)) }
        } catch (e: Exception) {
            toast("追加をリクエストできませんでした: ${e.javaClass.simpleName}")
        }
    }

    private fun describeAddTileResult(label: String, result: Int): String = when (result) {
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "$label を追加しました"
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "$label は追加済みです"
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "$label の追加はキャンセルされました"
        else -> "$label を追加できませんでした。パネルの編集画面から手動で追加してください"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
