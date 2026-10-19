package net.shino3.qsmultitools

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.Toast

/**
 * targetSdk 35 以降はウィンドウがシステムバーの下まで広がる (edge-to-edge)。
 * 何もしないと先頭の要素がステータスバーに隠れるので、ルートに inset ぶんの padding を足す。
 *
 * リスナーは何度も呼ばれるため、元の padding を取っておいて毎回そこから足し直す。
 */
fun View.padForSystemBars() {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        view.setPadding(
            base[0] + bars.left,
            base[1] + bars.top,
            base[2] + bars.right,
            base[3] + bars.bottom,
        )
        insets
    }
    requestApplyInsets()
}

/** ホーム画面へのウィジェット追加をリクエストする。対応していないホーム画面アプリならトーストで案内する。 */
fun Activity.requestPinWidget(providerClass: Class<*>) {
    val manager = getSystemService(AppWidgetManager::class.java)
    val provider = ComponentName(this, providerClass)
    val requested = manager != null &&
        manager.isRequestPinAppWidgetSupported &&
        runCatching { manager.requestPinAppWidget(provider, null, null) }.getOrDefault(false)
    if (!requested) {
        Toast.makeText(this, getString(R.string.widget_pin_unsupported), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 横に並べて等分するボタンの LayoutParams。左右に space_xs/2 ずつ空けて、隣と space_xs 離す。
 * 上下にも space_xs/2 を足し、行を重ねたときも同じ間隔になるようにする。
 */
fun Context.gappedWeight(): LinearLayout.LayoutParams {
    val half = resources.getDimensionPixelSize(R.dimen.space_xs) / 2
    return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(half, half, half, half)
    }
}
