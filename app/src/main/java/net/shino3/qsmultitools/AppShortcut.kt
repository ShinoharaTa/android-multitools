package net.shino3.qsmultitools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.SizeF

/**
 * ショートカットウィジェット 1 個ぶんの設定。
 *
 * 見た目はウィジェットごとに持つ。アプリのアイコンをそのまま置きたい人と、
 * カードとして置きたい人が同居できるようにするため。
 */
data class AppShortcut(
    /** 起動するランチャー Activity。アイコンとラベルもここから取る。 */
    val component: ComponentName,
    /** 空でなければ ACTION_VIEW でこの URI を開く。ディープリンク用。 */
    val uri: String?,
    val style: WidgetStyle,
) {
    companion object {
        private const val PREFS = "app_shortcuts"

        /** アイコンをそのまま置く見た目が既定。 */
        val DEFAULT_STYLE = WidgetStyle(WidgetTheme.TRANSPARENT, WidgetBorder.NONE)

        fun load(context: Context, appWidgetId: Int): AppShortcut? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val flat = prefs.getString(key(appWidgetId, "component"), null) ?: return null
            val component = ComponentName.unflattenFromString(flat) ?: return null
            return AppShortcut(
                component = component,
                uri = prefs.getString(key(appWidgetId, "uri"), null),
                style = WidgetStyle(
                    theme = prefs.getString(key(appWidgetId, "theme"), null)
                        .toEnumOr(DEFAULT_STYLE.theme),
                    border = prefs.getString(key(appWidgetId, "border"), null)
                        .toEnumOr(DEFAULT_STYLE.border),
                ),
            )
        }

        fun save(context: Context, appWidgetId: Int, shortcut: AppShortcut) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(appWidgetId, "component"), shortcut.component.flattenToString())
                .putString(key(appWidgetId, "uri"), shortcut.uri?.takeIf { it.isNotBlank() })
                .putString(key(appWidgetId, "theme"), shortcut.style.theme.name)
                .putString(key(appWidgetId, "border"), shortcut.style.border.name)
                .apply()
        }

        fun delete(context: Context, appWidgetIds: IntArray) {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            appWidgetIds.forEach { id ->
                listOf("component", "uri", "theme", "border").forEach { editor.remove(key(id, it)) }
            }
            editor.apply()
        }

        private fun key(appWidgetId: Int, name: String) = "${appWidgetId}_$name"

        private inline fun <reified T : Enum<T>> String?.toEnumOr(fallback: T): T =
            this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
    }
}

/**
 * インストール済みアプリの一覧とアイコン。
 *
 * Android 11 以降のパッケージ可視性があるので、マニフェストの `<queries>` に
 * MAIN + LAUNCHER の intent を書いてある。QUERY_ALL_PACKAGES は使わない。
 */
object InstalledApps {

    /** ランチャーに出るアプリを名前順で返す。重いので UI スレッドから呼ばないこと。 */
    fun launchable(context: Context): List<ResolveInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = runCatching {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        }.getOrDefault(emptyList())
        return found.sortedBy { label(context, it).lowercase() }
    }

    fun label(context: Context, info: ResolveInfo): String =
        runCatching { info.loadLabel(context.packageManager).toString() }
            .getOrDefault(info.activityInfo.packageName)

    fun label(context: Context, component: ComponentName): String? = runCatching {
        val pm = context.packageManager
        pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0L))
            .loadLabel(pm).toString()
    }.getOrNull()

    /** ランチャー Activity のアイコン。取れなければアプリのアイコンに落とす。 */
    fun icon(context: Context, component: ComponentName): Drawable? {
        val pm = context.packageManager
        return runCatching { pm.getActivityIcon(component) }
            .recoverCatching { pm.getApplicationIcon(component.packageName) }
            .getOrNull()
    }

    /**
     * RemoteViews には Drawable を渡せないので Bitmap に焼く。
     *
     * アダプティブアイコンは全面が塗られていて、そのまま描くと四角いままになる。
     * ランチャーと同じように角を丸めてから描く。
     */
    fun toBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        // RemoteViews は binder 越しに渡るので、大きすぎる Bitmap は載らない。
        // 320px (ARGB で約 400KB) を上限にして、それ以上は ImageView 側で拡大させる。
        val size = sizePx.coerceIn(24, 320)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            val radius = size * 0.24f
            canvas.clipPath(
                Path().apply {
                    addRoundRect(
                        RectF(0f, 0f, size.toFloat(), size.toFloat()),
                        radius,
                        radius,
                        Path.Direction.CW,
                    )
                },
            )
        }
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * タップしたときに飛ばす Intent。
     *
     * URI が設定されていればそのアプリで開くことを試し、開けなければパッケージ指定を外す。
     * それも駄目なら普通にアプリを起動する。
     */
    fun launchIntent(context: Context, shortcut: AppShortcut): Intent? {
        val pm = context.packageManager
        val uri = shortcut.uri?.takeIf { it.isNotBlank() }
        if (uri != null) {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            if (parsed != null) {
                val inApp = Intent(Intent.ACTION_VIEW, parsed).setPackage(shortcut.component.packageName)
                if (resolves(pm, inApp)) return inApp
                val anywhere = Intent(Intent.ACTION_VIEW, parsed)
                if (resolves(pm, anywhere)) return anywhere
            }
        }
        val direct = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(shortcut.component)
        if (resolves(pm, direct)) return direct
        // アプリが更新されて Activity 名が変わった場合の保険。
        return pm.getLaunchIntentForPackage(shortcut.component.packageName)
    }

    fun isInstalled(context: Context, shortcut: AppShortcut): Boolean =
        launchIntent(context, shortcut) != null

    private fun resolves(pm: PackageManager, intent: Intent): Boolean = runCatching {
        pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null
    }.getOrDefault(false)
}

/** ショートカットウィジェットのアイコンとラベルの寸法。 */
data class ShortcutMetrics(
    val iconDp: Float,
    val labelSp: Float,
    val showLabel: Boolean,
    val paddingDp: Float,
)

/**
 * 幅と高さからアイコンの大きさを決める。
 *
 * ラベルは高さ 60dp 未満だと出さない。1x1 に置いたときはアイコンだけにして、
 * 使える面積を全部アイコンに回したいため。
 */
fun shortcutMetricsFor(size: SizeF): ShortcutMetrics {
    val padding = (minOf(size.width, size.height) * 0.10f).coerceIn(3f, 12f)
    val showLabel = size.height >= 60f
    val labelSp = (size.height * 0.11f).coerceIn(9f, 18f)
    val labelBlock = if (showLabel) labelSp * 1.45f + 2f else 0f
    val icon = minOf(
        size.width - padding * 2f,
        size.height - padding * 2f - labelBlock,
    ).coerceIn(16f, 160f)
    return ShortcutMetrics(icon, labelSp, showLabel, padding)
}

/** 既定の 1x1 相当。ホーム画面アプリが寸法を教えてくれないときに使う。 */
val DEFAULT_SHORTCUT_SIZE = SizeF(72f, 72f)
