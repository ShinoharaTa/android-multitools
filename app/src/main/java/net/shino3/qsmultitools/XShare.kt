package net.shino3.qsmultitools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri

/**
 * X (旧 Twitter) の共有リンクの横取り。
 *
 * Web ページの「X でポスト」ボタンは `https://twitter.com/intent/tweet?text=…&url=…` のような URL を開いて
 * X アプリに文言を渡している。これを受け取って文言を組み立て直し、Android の共有メニューに渡す。
 * Web の Share ボタン (`navigator.share`) を押したときと同じ振る舞いで、どの SNS に出すかは共有メニューで選ぶ。
 *
 * このアプリを X のドメインの開き先にすると、共有以外のリンク (投稿やプロフィール) も X アプリで開かなくなる。
 * そこで X のリンクはすべてここで受け、共有以外は X アプリを明示して渡し直す ([forwardIntent])。
 */
object XShare {

    /** 受け取るドメイン。マニフェストの intent-filter と揃えること。 */
    val HOSTS = listOf("x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")

    /** 「デフォルトで開く」で選んでもらいたいドメイン。www 付きなどは無くても困らない。 */
    val REQUIRED_HOSTS = listOf("x.com", "twitter.com")

    const val X_PACKAGE = "com.twitter.android"

    /** 動作確認に開く共有 URL。 */
    const val SAMPLE_URL = "https://x.com/intent/post?text=QS%20%E3%83%9F%E3%83%8B%E3%83%84%E3%83%BC%E3%83%AB%E3%81%AE%E5%8B%95%E4%BD%9C%E7%A2%BA%E8%AA%8D&url=https%3A%2F%2Fexample.com%2F&hashtags=test"

    private val SHARE_PATHS = listOf("/intent/tweet", "/intent/post", "/share")

    fun isXLink(uri: Uri?): Boolean = uri?.host?.lowercase() in HOSTS

    /**
     * 共有リンクなら、共有メニューに渡す文言。共有リンクでなければ null。
     *
     * 組み立ては X の投稿画面と同じ順: `本文 URL #タグ1 #タグ2 via @via`。
     * 本文に URL がすでに含まれていれば重ねない。古い形式の `/home?status=` も受ける。
     */
    fun compose(uri: Uri): String? {
        if (!isXLink(uri)) return null
        val path = uri.path.orEmpty().trimEnd('/').lowercase()
        val legacyStatus = path == "/home" && uri.getQueryParameter("status") != null
        if (path !in SHARE_PATHS && !legacyStatus) return null

        val text = (uri.getQueryParameter("text") ?: uri.getQueryParameter("status")).orEmpty().trim()
        val url = uri.getQueryParameter("url").orEmpty().trim()
        val hashtags = uri.getQueryParameter("hashtags").orEmpty()
            .split(',')
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
        val via = uri.getQueryParameter("via").orEmpty().trim().removePrefix("@")

        val parts = buildList {
            if (text.isNotEmpty()) add(text)
            if (url.isNotEmpty() && !text.contains(url)) add(url)
            if (hashtags.isNotEmpty()) add(hashtags.joinToString(" ") { "#$it" })
            if (via.isNotEmpty()) add("via @$via")
        }
        return parts.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /** 共有メニューを開く Intent。Web Share と同じく ACTION_SEND の text/plain。 */
    fun shareIntent(text: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text),
            null,
        )

    /**
     * 共有以外の X のリンクを本来の開き先へ渡し直す Intent。
     * X アプリがあればそれを明示し (確認済みの開き先でなくても、パッケージを指定すれば開ける)、
     * 無ければブラウザを明示する。明示しないと、また自分に戻ってきてしまう。
     */
    fun forwardIntent(context: Context, uri: Uri): Intent? {
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (resolves(context, Intent(view).setPackage(X_PACKAGE))) return view.setPackage(X_PACKAGE)
        return browserPackage(context)?.let { view.setPackage(it) }
    }

    /** 既定のブラウザ。既定が決まっていなければ、見つかった最初のブラウザ。自分は除く。 */
    private fun browserPackage(context: Context): String? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        val default = runCatching { pm.resolveActivity(probe, flags) }.getOrNull()?.activityInfo?.packageName
        // 既定が無いときは選択ダイアログ (パッケージ名 "android") が返る。
        if (default != null && default != "android" && default != context.packageName) return default
        return runCatching { pm.queryIntentActivities(probe, flags) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName }
            .firstOrNull { it != context.packageName }
    }

    private fun resolves(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        ).isNotEmpty()
    }.getOrDefault(false)

    /**
     * ドメインごとに、このアプリが開き先になっているか。
     * 「デフォルトで開く」でユーザーが選んだもの (SELECTED) だけが true。確認済み (VERIFIED) にはならない。
     */
    fun hostStates(context: Context): Map<String, Boolean> {
        val state: DomainVerificationUserState? = runCatching {
            context.getSystemService(DomainVerificationManager::class.java)
                ?.getDomainVerificationUserState(context.packageName)
        }.getOrNull()
        val map = state?.hostToStateMap.orEmpty()
        return REQUIRED_HOSTS.associateWith { host ->
            map[host] == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                map[host] == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }
    }

    fun status(context: Context): FeatureStatus =
        if (hostStates(context).values.all { it }) FeatureStatus.AVAILABLE else FeatureStatus.NEEDS_PERMISSION

    /** このアプリの「デフォルトで開く」設定画面。 */
    fun openByDefaultSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${context.packageName}"))
}
