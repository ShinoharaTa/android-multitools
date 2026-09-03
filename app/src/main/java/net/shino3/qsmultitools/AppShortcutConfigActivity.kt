package net.shino3.qsmultitools

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView

/**
 * ショートカットウィジェットの設定画面。
 *
 * ウィジェットを置いたときにホーム画面アプリから呼ばれる。
 * `widgetFeatures="reconfigurable"` にしてあるので、置いたあとでも開き直せる。
 */
class AppShortcutConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var uriInput: EditText
    private lateinit var themeSpinner: Spinner
    private lateinit var borderSpinner: Spinner
    private lateinit var adapter: AppListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 途中でやめた場合にウィジェットが置かれないよう、先に取り消しを立てておく。
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_shortcut_config)
        findViewById<View>(R.id.configRoot).padForSystemBars()
        uriInput = findViewById(R.id.shortcutUri)
        themeSpinner = findViewById(R.id.shortcutTheme)
        borderSpinner = findViewById(R.id.shortcutBorder)

        // 置き直しのときは今の設定を出しておく。
        val current = AppShortcut.load(this, appWidgetId)
        uriInput.setText(current?.uri.orEmpty())
        val style = current?.style ?: AppShortcut.DEFAULT_STYLE
        fillSpinner(themeSpinner, WidgetTheme.entries.map { getString(it.labelRes) }, style.theme.ordinal)
        fillSpinner(borderSpinner, WidgetBorder.entries.map { getString(it.labelRes) }, style.border.ordinal)

        adapter = AppListAdapter()
        val list = findViewById<ListView>(R.id.shortcutAppList)
        list.adapter = adapter
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            commit(adapter.getItem(position))
        }

        findViewById<EditText>(R.id.shortcutSearch).addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            },
        )

        loadApps()
    }

    /** アプリ一覧の読み込みとラベル取得は数百件ぶん走るので、UI スレッドから外す。 */
    private fun loadApps() {
        Thread {
            val apps = InstalledApps.launchable(this)
            runOnUiThread {
                if (!isFinishing) {
                    adapter.replaceAll(apps)
                    findViewById<View>(R.id.shortcutLoading).visibility = View.GONE
                }
            }
        }.start()
    }

    private fun commit(info: ResolveInfo) {
        val shortcut = AppShortcut(
            component = ComponentName(info.activityInfo.packageName, info.activityInfo.name),
            uri = uriInput.text?.toString()?.trim(),
            style = WidgetStyle(
                theme = WidgetTheme.entries[themeSpinner.selectedItemPosition],
                border = WidgetBorder.entries[borderSpinner.selectedItemPosition],
            ),
        )
        AppShortcut.save(this, appWidgetId, shortcut)

        // 置き直しのときはホーム画面アプリが onUpdate を呼ばないことがあるので、自分で描き直す。
        AppWidgetManager.getInstance(this)?.let {
            AppShortcutWidgetProvider.render(this, it, appWidgetId)
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    private fun fillSpinner(spinner: Spinner, labels: List<String>, selected: Int) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(selected, false)
    }

    /** アイコンは表示するぶんだけ読む。一度読んだものは使い回す。 */
    private inner class AppListAdapter : BaseAdapter() {

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
                all.filter { InstalledApps.label(this@AppShortcutConfigActivity, it).lowercase().contains(query) }
            }
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size

        override fun getItem(position: Int): ResolveInfo = shown[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val info = shown[position]
            val component = "${info.activityInfo.packageName}/${info.activityInfo.name}"

            view.findViewById<TextView>(R.id.appLabel).text =
                InstalledApps.label(this@AppShortcutConfigActivity, info)
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(
                icons.getOrPut(component) {
                    runCatching { info.loadIcon(packageManager) }.getOrNull()
                },
            )
            return view
        }
    }
}
