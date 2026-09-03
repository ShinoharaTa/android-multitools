package net.shino3.qsmultitools

import android.view.View
import android.view.WindowInsets

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
