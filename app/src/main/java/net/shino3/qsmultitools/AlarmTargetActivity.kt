package net.shino3.qsmultitools

import android.app.Activity
import android.content.ComponentName
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView

/**
 * アラームタイルとウィジェットのタップで開くアプリを選ぶ画面。
 *
 * 標準の SHOW_ALARMS で時計アプリに辿り着けない端末のための逃げ道。
 * 選んだ結果は端末に 1 つだけ保存し、タイルとウィジェットの両方がそれを使う。
 */
class AlarmTargetActivity : Activity() {

    private lateinit var adapter: AppListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_target)
        val list = findViewById<ListView>(R.id.alarmTargetList)
        list.padForSystemBars()
        // 見出しと設定はリストのヘッダー。一覧と一緒にスクロールする。
        val header = layoutInflater.inflate(R.layout.header_alarm_target, list, false)
        list.addHeaderView(header, null, false)

        val current = Alarms.configured(this)
        header.findViewById<TextView>(R.id.alarmTargetCurrent).text = getString(
            R.string.alarm_target_current,
            current?.let { InstalledApps.label(this, it) ?: it.packageName }
                ?: getString(R.string.alarm_target_auto),
        )

        header.findViewById<Button>(R.id.btnAlarmTargetAuto).setOnClickListener { commit(null) }

        val handlers = Alarms.handlerPackages(this)
        adapter = AppListAdapter(this) { info ->
            if (info.activityInfo.packageName in handlers) getString(R.string.alarm_target_badge) else null
        }
        list.adapter = adapter
        // ヘッダーがあるので position はヘッダーぶんずれる。リスト側に項目を引かせる。
        list.onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
            val info = parent.getItemAtPosition(position) as? ResolveInfo ?: return@OnItemClickListener
            commit(ComponentName(info.activityInfo.packageName, info.activityInfo.name))
        }

        header.findViewById<EditText>(R.id.alarmTargetSearch).addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            },
        )

        Thread {
            val apps = InstalledApps.launchable(this)
            // SHOW_ALARMS を受けるアプリを先頭に寄せる。探す手間を省くため。
            val sorted = apps.sortedByDescending { it.activityInfo.packageName in handlers }
            runOnUiThread {
                if (!isFinishing) {
                    adapter.replaceAll(sorted)
                    header.findViewById<View>(R.id.alarmTargetLoading).visibility = View.GONE
                }
            }
        }.start()
    }

    private fun commit(component: ComponentName?) {
        Alarms.setConfigured(this, component)
        // ウィジェットのタップ先も変わるので描き直す。タイルは次に開いたときに読み直す。
        NextAlarmWidgetProvider.refresh(this)
        finish()
    }
}
