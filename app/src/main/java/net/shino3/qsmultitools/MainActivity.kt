package net.shino3.qsmultitools

import android.app.Activity
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
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

    private lateinit var previewRoot: ViewGroup
    private lateinit var previewInner: ViewGroup
    private lateinit var previewHeader: TextView
    private lateinit var previewTime: TextView
    private lateinit var previewDate: TextView

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
        previewRoot = findViewById(R.id.widgetRoot)
        previewInner = findViewById(R.id.widgetInner)
        previewHeader = findViewById(R.id.widgetHeader)
        previewTime = findViewById(R.id.widgetTime)
        previewDate = findViewById(R.id.widgetDate)

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
        setUpStyleSpinners()
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
        updatePreview()
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

    // ---- ウィジェットの見た目 -------------------------------------------

    private fun setUpStyleSpinners() {
        val style = WidgetStyle.load(this)
        bindSpinner(R.id.spinnerTheme, WidgetTheme.entries, style.theme) { theme ->
            WidgetStyle.load(this).copy(theme = theme)
        }
        bindSpinner(R.id.spinnerBorder, WidgetBorder.entries, style.border) { border ->
            WidgetStyle.load(this).copy(border = border)
        }
    }

    private fun <T : Enum<T>> bindSpinner(
        spinnerId: Int,
        values: List<T>,
        selected: T,
        toStyle: (T) -> WidgetStyle,
    ) {
        val labels = values.map { getString(labelOf(it)) }
        findViewById<Spinner>(spinnerId).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels)
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(values.indexOf(selected), false)
            // リスナーは初期選択を入れ終わってから付ける。付けてから setSelection すると
            // 画面を開いた瞬間に保存が走ってしまう。
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    WidgetStyle.save(this@MainActivity, toStyle(values[position]))
                    updatePreview()
                    NextAlarmWidgetProvider.refresh(this@MainActivity)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    private fun labelOf(value: Enum<*>): Int = when (value) {
        is WidgetTheme -> value.labelRes
        is WidgetBorder -> value.labelRes
        else -> error("未対応の選択肢: ${'$'}value")
    }

    /**
     * 見本にウィジェットと同じ計算を当てる。
     * 実物と同じ [metricsFor] を使うので、行が省かれる挙動もそのまま見える。
     */
    private fun updatePreview() {
        val style = WidgetStyle.load(this)
        val info = NextAlarm.info(this)
        val time = info?.let { NextAlarm.formatTime(this, it.triggerTime) }
            ?: getString(R.string.widget_no_alarm)
        val day = info?.let { NextAlarm.formatDay(this, it.triggerTime) }
        val metrics = metricsFor(PREVIEW_SIZE, time.length)

        previewTime.text = time
        previewTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, metrics.timeSp)
        previewHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, metrics.headerSp)
        previewDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, metrics.dateSp)
        previewHeader.visibility = if (metrics.showHeader) View.VISIBLE else View.GONE
        if (day != null && metrics.showDate) {
            previewDate.text = day
            previewDate.visibility = View.VISIBLE
        } else {
            previewDate.visibility = View.GONE
        }

        val border = dp(style.border.widthDp)
        previewRoot.setPadding(border, border, border, border)
        previewInner.setBackgroundResource(
            if (border > 0) R.drawable.bg_widget_inner else R.drawable.bg_widget,
        )
        val padding = dp(metrics.paddingDp)
        previewInner.setPadding(padding, padding, padding, padding)

        val palette = style.palette
        previewRoot.backgroundTintList = when {
            border == 0 -> ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            palette == null -> null
            else -> ColorStateList.valueOf(palette.border)
        }
        previewInner.backgroundTintList = palette?.let { ColorStateList.valueOf(it.background) }
        previewTime.setTextColor(palette?.text ?: getColor(R.color.widget_text))
        previewHeader.setTextColor(palette?.subText ?: getColor(R.color.widget_text_sub))
        previewDate.setTextColor(palette?.subText ?: getColor(R.color.widget_text_sub))
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

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

    companion object {
        /** 見本の枠の大きさ。レイアウト側の 170dp x 76dp と合わせてある。 */
        private val PREVIEW_SIZE = SizeF(170f, 76f)
    }
}
