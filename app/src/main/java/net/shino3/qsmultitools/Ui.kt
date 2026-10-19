package net.shino3.qsmultitools

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/**
 * targetSdk 35 以降はウィンドウがシステムバーの下まで広がる (edge-to-edge)。
 * 何もしないと先頭の要素がステータスバーに隠れるので、ルートに inset ぶんの padding を足す。
 *
 * ステータスバーは透明にしてあるので、スクロールした本文がその下に透けて時計と重なる
 * (ScrollView は clipToPadding="false" で padding の領域にも描くため)。そこで画面の最前面に、
 * ステータスバーの高さぶんだけ画面背景と同じ色の帯 ([STATUS_BAR_SCRIM_TAG]) を敷いて隠す。
 * 下端 (ジェスチャーバー側) は本文を透かしたままでよい。
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
        view.statusBarScrim()?.let { scrim ->
            if (scrim.layoutParams.height != bars.top) {
                scrim.layoutParams = scrim.layoutParams.apply { height = bars.top }
            }
        }
        insets
    }
    requestApplyInsets()
}

/** 画面の content に敷いたステータスバーの帯。無ければ作って最前面に足す。 */
private fun View.statusBarScrim(): View? {
    val content = rootView.findViewById<FrameLayout>(android.R.id.content) ?: return null
    content.findViewWithTag<View>(STATUS_BAR_SCRIM_TAG)?.let { return it }
    val scrim = View(context).apply {
        tag = STATUS_BAR_SCRIM_TAG
        setBackgroundColor(context.getColor(R.color.bg))
        // 帯は見た目だけ。タップは下の本文へ素通しさせない (ステータスバーの領域なので押す物は無い)。
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    content.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
    return scrim
}

private const val STATUS_BAR_SCRIM_TAG = "status_bar_scrim"

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
