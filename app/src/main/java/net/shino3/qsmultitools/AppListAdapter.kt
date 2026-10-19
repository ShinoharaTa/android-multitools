package net.shino3.qsmultitools

import android.content.Context
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * インストール済みアプリの一覧。ショートカットの設定画面とアラームの開き先の設定画面で共用する。
 *
 * アイコンは表示するぶんだけ読み、一度読んだものは使い回す。
 * [badge] を渡すと、該当するアプリの名前の下に注記を出す。
 */
class AppListAdapter(
    private val context: Context,
    private val badge: (ResolveInfo) -> String? = { null },
) : BaseAdapter() {

    private var all: List<ResolveInfo> = emptyList()
    private var shown: List<ResolveInfo> = emptyList()
    private var query: String = ""
    private val icons = HashMap<String, Drawable?>()

    fun replaceAll(apps: List<ResolveInfo>) {
        all = apps
        applyQuery()
    }

    fun filter(text: String) {
        query = text.trim().lowercase()
        applyQuery()
    }

    private fun applyQuery() {
        shown = if (query.isEmpty()) {
            all
        } else {
            all.filter { InstalledApps.label(context, it).lowercase().contains(query) }
        }
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size

    override fun getItem(position: Int): ResolveInfo = shown[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val info = shown[position]
        val component = "${info.activityInfo.packageName}/${info.activityInfo.name}"

        view.findViewById<TextView>(R.id.appLabel).text = InstalledApps.label(context, info)
        val note = badge(info)
        view.findViewById<TextView>(R.id.appBadge).apply {
            text = note
            visibility = if (note == null) View.GONE else View.VISIBLE
        }
        view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(
            icons.getOrPut(component) {
                runCatching { info.loadIcon(context.packageManager) }.getOrNull()
            },
        )
        return view
    }
}
