package net.shino3.qsmultitools

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.SizeF
import android.widget.RemoteViews

/**
 * ウィジェットの背景テーマ。
 *
 * [SYSTEM] だけは色を一切指定しない。指定しなければレイアウトの `@color/widget_*` が
 * そのまま効き、values-night との出し分けはウィジェットを描くホーム画面アプリ側の
 * 設定に従う。つまり端末のダークモード切替に自動で追従する。
 */
enum class WidgetTheme(val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    BLACK(R.string.theme_black),
    TRANSPARENT(R.string.theme_transparent),
}

/** 枠線の太さ。 */
enum class WidgetBorder(val labelRes: Int, val widthDp: Float) {
    NONE(R.string.border_none, 0f),
    THIN(R.string.border_thin, 1f),
    MEDIUM(R.string.border_medium, 2f),
    THICK(R.string.border_thick, 3f),
}

/** 明示指定するときの色一式。SYSTEM のときは null になる。 */
data class Palette(val background: Int, val text: Int, val subText: Int, val border: Int)

/** 幅と高さから決まる文字サイズと、入りきらない行の間引き。 */
data class Metrics(
    val timeSp: Float,
    val headerSp: Float,
    val dateSp: Float,
    val showHeader: Boolean,
    val showDate: Boolean,
    val paddingDp: Float,
)

data class WidgetStyle(val theme: WidgetTheme, val border: WidgetBorder) {

    val palette: Palette?
        get() = when (theme) {
            WidgetTheme.SYSTEM -> null
            WidgetTheme.LIGHT -> Palette(0xFFFDFDFD.toInt(), 0xFF1B1D21.toInt(), 0xFF5F6368.toInt(), 0x33000000)
            WidgetTheme.DARK -> Palette(0xFF1B1D21.toInt(), 0xFFF1F3F5.toInt(), 0xFFB4B8BE.toInt(), 0x33FFFFFF)
            WidgetTheme.BLACK -> Palette(Color.BLACK, 0xFFF1F3F5.toInt(), 0xFFB4B8BE.toInt(), 0x40FFFFFF)
            // 壁紙の上に直接載るので、文字は白 + 枠線は明るめにして輪郭を出す。
            WidgetTheme.TRANSPARENT -> Palette(Color.TRANSPARENT, Color.WHITE, 0xFFE3E5E8.toInt(), 0x80FFFFFF.toInt())
        }

    companion object {
        private const val PREFS = "widget_style"
        private const val KEY_THEME = "theme"
        private const val KEY_BORDER = "border"

        val DEFAULT = WidgetStyle(WidgetTheme.SYSTEM, WidgetBorder.NONE)

        fun load(context: Context): WidgetStyle {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WidgetStyle(
                theme = prefs.getString(KEY_THEME, null).toEnum(DEFAULT.theme),
                border = prefs.getString(KEY_BORDER, null).toEnum(DEFAULT.border),
            )
        }

        fun save(context: Context, style: WidgetStyle) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_THEME, style.theme.name)
                .putString(KEY_BORDER, style.border.name)
                .apply()
        }

        /** 保存値が壊れていても既定値に落とす。 */
        private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
            this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
    }
}

/**
 * ウィジェットの実寸 (dp) から文字サイズを決める。
 *
 * 高さは「ヘッダー + 時刻 + 日付」を時刻に対する比で積み上げた合計で割る。
 * 幅は時刻の文字数から必要幅を見積もって割る。小さい方を採用し、
 * 収まらない行はヘッダー → 日付の順に落とす。
 */
fun metricsFor(size: SizeF, timeTextLength: Int): Metrics {
    val padding = (size.height * 0.10f).coerceIn(4f, 14f)
    val showHeader = size.height >= 68f
    val showDate = size.height >= 46f

    val stack = 1f +
        (if (showHeader) HEADER_RATIO else 0f) +
        (if (showDate) DATE_RATIO else 0f)
    val byHeight = (size.height - padding * 2f) / (LINE_HEIGHT * stack)
    val byWidth = (size.width - padding * 2f) / (timeTextLength.coerceAtLeast(1) * CHAR_WIDTH_RATIO)

    val timeSp = minOf(byHeight, byWidth).coerceIn(11f, 120f)
    return Metrics(
        timeSp = timeSp,
        headerSp = (timeSp * HEADER_RATIO).coerceAtLeast(8f),
        dateSp = (timeSp * DATE_RATIO).coerceAtLeast(9f),
        showHeader = showHeader,
        showDate = showDate,
        paddingDp = padding,
    )
}

/** 既定の 2x1 相当。ホーム画面アプリが寸法を教えてくれないときに使う。 */
val DEFAULT_WIDGET_SIZE = SizeF(140f, 72f)

private const val HEADER_RATIO = 0.42f
private const val DATE_RATIO = 0.46f
// TextView 1 行が実際に占める高さ / textSize。includeFontPadding を切った実測で
// 英数字は約 1.17、日本語は約 1.43 だった。どの文字が来ても溢れないよう日本語側に寄せてある。
private const val LINE_HEIGHT = 1.45f
private const val CHAR_WIDTH_RATIO = 0.58f

/**
 * 外枠 (`root`) と中身 (`inner`) の 2 枚重ねに背景テーマと枠線を当てる。
 *
 * 枠線の太さは外枠の padding として出す。色は tint で変える。
 * SYSTEM のときは色を指定しないので、レイアウトの `@color/widget_*` が
 * ホーム画面アプリ側の light / dark 設定に従って解決される。
 * 文字色はウィジェットごとに対象が違うので、ここでは触らずに呼び出し側で当てる。
 */
fun RemoteViews.applyFrame(
    context: Context,
    style: WidgetStyle,
    rootId: Int,
    innerId: Int,
    contentPaddingDp: Float,
) {
    val borderPx = context.toPx(style.border.widthDp)
    setViewPadding(rootId, borderPx, borderPx, borderPx, borderPx)

    // 枠線があるぶん中身は内側なので、角丸を一段小さい drawable に差し替える。
    setInt(
        innerId,
        "setBackgroundResource",
        if (borderPx > 0) R.drawable.bg_widget_inner else R.drawable.bg_widget,
    )

    val padding = context.toPx(contentPaddingDp)
    setViewPadding(innerId, padding, padding, padding, padding)

    val palette = style.palette
    if (palette == null) {
        // 枠線が 0 のときだけは外枠が縁から覗かないように消しておく。
        if (borderPx == 0) tintBackground(rootId, Color.TRANSPARENT)
        return
    }
    tintBackground(rootId, if (borderPx > 0) palette.border else Color.TRANSPARENT)
    tintBackground(innerId, palette.background)
}

fun RemoteViews.tintBackground(viewId: Int, color: Int) =
    setColorStateList(viewId, "setBackgroundTintList", ColorStateList.valueOf(color))

fun Context.toPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()
