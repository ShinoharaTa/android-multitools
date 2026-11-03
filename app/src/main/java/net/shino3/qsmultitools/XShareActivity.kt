package net.shino3.qsmultitools

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * X のリンクの受け口。画面は持たない (Theme.NoDisplay)。
 *
 * - 共有リンク: 文言を組み立てて Android の共有メニューを開く
 * - それ以外: X アプリ (無ければブラウザ) へそのまま渡す
 *
 * どちらも渡したらすぐ閉じる。NoDisplay の Activity は onResume までに finish しないと落とされる。
 */
class XShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val next = when {
            uri == null -> null
            else -> XShare.compose(uri)?.let { XShare.shareIntent(it) } ?: XShare.forwardIntent(this, uri)
        }
        if (next == null || runCatching { startActivity(next) }.isFailure) {
            Toast.makeText(this, R.string.xshare_open_failed, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
