package net.shino3.qsmultitools

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * ホーム画面。アラーム / タイマー / ツールと設定 の 3 枚のカード。
 *
 * アラームのカードは次に鳴るものと件数を出し、タップで一覧、ボタンで追加に直行する。
 * タイマーのカードにはウィジェットと同じ操作パネルをそのまま置き、ここから開始・延長・停止できる。
 * ツールのカードはタイルとウィジェットの設定と権限への入口で、許可が足りなければ上に注意書きを出す。
 */
class MainActivity : Activity() {

    private lateinit var permBanner: View
    private lateinit var alarmNext: TextView
    private lateinit var alarmCount: TextView
    private lateinit var toolsSummary: TextView
    private lateinit var timerPanel: TimerPanel

    private val alarmsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshAlarms()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.mainScroll).padForSystemBars()

        // 0.3.0-test1 までタイマーの設定は通常の保存領域に置いていた。ロック解除前でも読める側へ移す。
        migrateToBootSafePrefs("timer", "timer_settings")

        permBanner = findViewById(R.id.homePermBanner)
        alarmNext = findViewById(R.id.homeAlarmNext)
        alarmCount = findViewById(R.id.homeAlarmCount)
        toolsSummary = findViewById(R.id.homeToolsSummary)
        timerPanel = TimerPanel(this, findViewById(R.id.timerPanel), compact = true)

        permBanner.setOnClickListener { open(ToolsActivity::class.java) }
        findViewById<View>(R.id.homeAlarmCard).setOnClickListener { open(AlarmListActivity::class.java) }
        findViewById<Button>(R.id.btnHomeAlarmAdd).setOnClickListener { open(AlarmEditActivity::class.java) }
        findViewById<View>(R.id.homeTimerCard).setOnClickListener { open(TimerActivity::class.java) }
        findViewById<View>(R.id.homeToolsCard).setOnClickListener { open(ToolsActivity::class.java) }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(alarmsChanged, IntentFilter(AlarmScheduler.ACTION_CHANGED), RECEIVER_NOT_EXPORTED)
        timerPanel.start()
    }

    override fun onResume() {
        super.onResume()
        refreshAlarms()
        refreshTools()
    }

    override fun onStop() {
        timerPanel.stop()
        unregisterReceiver(alarmsChanged)
        super.onStop()
    }

    private fun refreshAlarms() {
        val now = System.currentTimeMillis()
        val alarms = AlarmStore.all(this)
        val upcoming = alarms.mapNotNull { it.nextTrigger(now) }.minOrNull()
        alarmNext.text = if (upcoming == null) {
            getString(R.string.alarm_next_none)
        } else {
            getString(
                R.string.alarm_next,
                "${NextAlarm.formatDay(this, upcoming)} ${NextAlarm.formatTime(this, upcoming)}",
                AlarmFormat.until(this, upcoming, now),
            )
        }
        alarmCount.text = if (alarms.isEmpty()) {
            getString(R.string.alarm_list_empty)
        } else {
            getString(R.string.own_alarm_count, alarms.size, alarms.count { it.enabled || it.snoozeUntil > now })
        }
    }

    private fun refreshTools() {
        val perms = RingPermissions.check(this)
        permBanner.visibility = if (perms.ready) View.GONE else View.VISIBLE

        // ツール側の状態を 1 行にまとめる。足りないものがあればそれを優先して見せる。
        val issues = buildList {
            if (UsbDebug.status(this@MainActivity) == FeatureStatus.NEEDS_PERMISSION) add(getString(R.string.tile_usb_debug))
            if (ScreenTimeout.status(this@MainActivity) == FeatureStatus.NEEDS_PERMISSION) add(getString(R.string.tile_screen_timeout))
            if (!perms.ready) add(getString(R.string.tools_issue_ring))
        }
        toolsSummary.text = if (issues.isEmpty()) {
            getString(R.string.tools_summary_ok)
        } else {
            getString(R.string.tools_summary_issues, issues.joinToString(" / "))
        }
    }

    private fun open(target: Class<out Activity>) {
        startActivity(Intent(this, target))
    }
}
