# 設計メモ

実装前に確認した「現在の Android API で何がどこまでできるか」と、その結果選んだ形。

## 1. 各機能の実現方法

### USB デバッグ ON/OFF

`Settings.Global.ADB_ENABLED` (API 17 から公開されている定数) を 0/1 で書き換える。
`AdbService` がこの設定を監視していて、値の変化で adbd が起動・停止する。

書き込みには `WRITE_SECURE_SETTINGS` が必要。protection level は `signature|privileged` なので
通常のインストールでは付かない。`adb shell pm grant` で一度付与すれば、アプリを消すまで残る。
「USB デバッグを OFF にしたら二度と ON に戻せない」という事態にはならない。

Knox など OEM 側の管理機能や `DISALLOW_DEBUGGING_FEATURES` のユーザー制限が有効だと、
`putInt` が成功したように見えて値が戻ることがある。書いた後に読み戻して確認し、
食い違ったらトーストで知らせる。

### 画面タイムアウト切替

`Settings.System.SCREEN_OFF_TIMEOUT` (ミリ秒)。書き込みには `WRITE_SETTINGS` が必要だが、
これは appop なので `Settings.ACTION_MANAGE_WRITE_SETTINGS` の許可画面から本人が許可できる。
adb は要らない。

候補は 15秒 / 30秒 / 1分 / 2分 / 5分 / 10分 / 30分 / 消灯しない から本人が選ぶ (既定は 15秒 以外)。
全部を巡回させるとタップ回数が増えるし、端末によって欲しい値が違うため。
次の値は「今より大きい最初の候補、無ければ先頭」で決める。この規則なら現在値が候補になくても
素直に動く。1 つも選ばれていなければ既定に戻す。

### アラーム画面を開く

`AlarmClock.ACTION_SHOW_ALARMS` (API 19 から)。これは「アラーム一覧を開く」ための標準 Intent で、
`ACTION_SET_ALARM` (追加) とは別物。Galaxy 標準の時計アプリもこれを受ける。

Samsung 固有の component 名や undocumented な extra には依存しない。
標準 Intent が解決できなかったときだけ、`com.sec.android.app.clockpackage` →
`com.google.android.deskclock` の順にランチャー Intent へフォールバックする。

Android 11 以降のパッケージ可視性があるので、解決可否を判定するにはマニフェストに
`<queries>` で `SHOW_ALARMS` と時計アプリのパッケージ名を書いておく必要がある。

**暗黙 Intent のまま投げない。** `resolveActivity()` は受け手が複数あって既定が無いと
アプリ選択ダイアログ (ResolverActivity) を返す。それをタイルから `startActivityAndCollapse` で
投げると、パネルが閉じる動きに巻き込まれて何も開かないように見える。Galaxy に Google 時計も
入れている構成で起きうる。そこで `queryIntentActivities()` で候補を取り、Galaxy 標準の時計アプリが
あればそれ、無ければ先頭を自分で選んで、明示コンポーネントにしてから投げる。

**本当の原因は権限だった。** Galaxy (One UI 9 / Android 17) の実機ログを取ったところ、
タイルから投げた Intent が system_server で弾かれていた。

```
Permission Denial: starting Intent { act=SHOW_ALARMS
  cmp=com.sec.android.app.clockpackage/.alarm.ui.activity.AlarmCTSHandleActivity }
  requires com.android.alarm.permission.SET_ALARM
```

Samsung の受け口 `AlarmCTSHandleActivity` は `android:permission` で
`com.android.alarm.permission.SET_ALARM` を要求している。`AlarmClock` の javadoc にも
「SHOW_ALARMS 等を受けるアプリはこの権限で保護せよ」と書いてあり、Samsung はそれに従っている。
Google 時計は保護していないので、宣言なしでも開けてしまい気付きにくかった。

この権限は protection level が normal なので、マニフェストに書けばインストール時に自動で付く。
SecurityException は PendingIntent を発火する system_server 側で起きるため、
こちらの try-catch には入らず、何も起きないように見えていた。

**それでも駄目な端末のために、開き先を本人が指定できるようにした。** 指定があれば
自動判定より優先し、指定アプリが `SHOW_ALARMS` を受けるなら `setPackage` 付きで一覧へ直行、
受けないならランチャー Activity を起動する。ウィジェットでは時計アプリ側の `showIntent` よりも
指定を優先する。時計アプリが用意した PendingIntent が端末によっては期待どおり動かないため。
指定アプリが消えていれば自動判定に落ちる。

## 2. 必要な permission

| permission | 用途 | 付与方法 |
| --- | --- | --- |
| `WRITE_SECURE_SETTINGS` | `Settings.Global.ADB_ENABLED` の書き込み | `adb shell pm grant`。マニフェストに宣言していないと grant 自体が失敗する |
| `WRITE_SETTINGS` | `Settings.System.SCREEN_OFF_TIMEOUT` の書き込み | 設定アプリの「システム設定の変更」、または `adb shell appops set` |
| `BIND_QUICK_SETTINGS_TILE` | `TileService` を SystemUI にバインドさせる | サービス側の `android:permission` に書くだけ。要求はしない |

| `USE_EXACT_ALARM` | タイマーの発火 (`setExactAndAllowWhileIdle`) | インストール時に自動で付く。Play では用途が制限されるが、ストア公開しないので関係ない |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 鳴らしている間の前景サービス | 宣言するだけ |
| `USE_FULL_SCREEN_INTENT` | ロック中に完了画面を開く | 既定で付くが、設定アプリから外せる。`canUseFullScreenIntent()` で確認する |
| `POST_NOTIFICATIONS` | 残り時間と鳴動の通知 | 実行時に許可を求める |
| `RECEIVE_BOOT_COMPLETED` | 再起動後にタイマーを張り直す | 宣言するだけ |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 最適化を外す確認ダイアログを出す | 本人がダイアログで許可する |

アラームの画面を開くだけなら権限は要らない (`SET_ALARM` は normal なので自動で付く)。

## 3. 最近の Android / One UI での制約

- **`startActivityAndCollapse(Intent)` は targetSdk 34 以上で `UnsupportedOperationException` を投げる。**
  代替の `startActivityAndCollapse(PendingIntent)` は API 34 から。
  minSdk を 34 に置くことで新旧の分岐がまるごと不要になる。One UI 6 以降の Galaxy が対象なので実害はない。
- **`STATE_UNAVAILABLE` のタイルには `onClick` が来ない。** 権限不足を `UNAVAILABLE` で表すと
  セットアップ画面へ誘導できなくなるので、権限が無いときも `INACTIVE` のままにして
  サブタイトルに「セットアップが必要」と出す。
- **`StatusBarManager.requestAddTileService` は API 33 から。** クイック設定への追加を
  アプリ内のボタンからできる。結果はコールバックで返る (追加 / 追加済み / キャンセル / エラー)。
- **`PackageManager.resolveActivity(Intent, Int)` は API 33 で deprecated。**
  `ResolveInfoFlags` を取る新しい版を使う。
- **タイルのラベルはクイック設定パネルで簡単に切り詰められる。**
  「画面タイムアウト」は 2 列レイアウトで既に省略されるので、状態はラベルではなく
  `Tile.setSubtitle` (API 29 から) と Active/Inactive の色で見せる。
  `setStateDescription` と `contentDescription` にも同じ内容を入れてある。

## 4. 時計アプリのアラームを一覧できない理由

「時計アプリに設定済みのアラームを一覧にして、個別に ON/OFF する」ウィジェットは、公開 API では作れない。
そこでアラームそのものをこのアプリで持つことにした (8 章)。
調べた結果は次のとおり。

**時計アプリのアラーム DB は外から読めない。**
Google 時計の `com.google.android.deskclock.provider` は存在するが、外部からの query に対して
明示的に `UnsupportedOperationException: No external queries` を投げる。アプリより権限の強い
shell uid から叩いても同じだった。Samsung 時計の `content://com.sec.android.app.clockpackage/alarm`
は古い端末で読めた時期があるが非公開で、One UI の更新で壊れる類のもの。仮に読めても
書き込み (ON/OFF) はまず通らない。

**標準 Intent にも該当するものが無い。**
`android.provider.AlarmClock` にあるのは `SET_ALARM` (新規作成) / `SHOW_ALARMS` (一覧を開く) /
`DISMISS_ALARM` / `SNOOZE_ALARM` / `SET_TIMER` / `SHOW_TIMERS` / `DISMISS_TIMER` だけ。
一覧の取得も、既存アラームの有効・無効の切り替えも用意されていない。

**Samsung の時計プロバイダも読めない。** 実機で確認したところ、
`com.sec.android.app.clockpackage.provider.SearchProvider` は
`com.samsung.android.provider.indexing.permission.ACCESS_PROVIDER` を要求し、
shell uid からも弾かれた。一覧を読む道はやはり無い。

**Samsung は就寝モードの起床通知も「次のアラーム」として登録する。** 実機の `dumpsys alarm` に
`*walarm*:com.sec.android.app.clockpackage.alarm.SCHEDULE_WAKEUP_NOTIFY` が `setAlarmClock()` で
登録されていた。時計アプリ上で就寝モードのトグルが OFF でも残っていることがあり、
その場合ウィジェットは有効なアラームではない時刻を出す。ただしこれはシステム全体の値であって、
ステータスバーのアラーム表示も `next_alarm_formatted` も同じ時刻を指す。
`AlarmClockInfo` からは発火時刻と PendingIntent しか取れず、種別を見分ける術がないので直せない。

**取れるのは「次の 1 件」だけ。**
`AlarmManager.getNextAlarmClock()` は `setAlarmClock()` で立てられたアラームなら
どのアプリのものでも返す。権限は要らない。Galaxy 標準の時計アプリもこの経路に乗る。
返ってくるのは発火時刻と、その時計アプリが用意した `showIntent` (アラーム画面を開く PendingIntent)。

なので、ウィジェットは「次のアラームの時刻を出して、タップでその時計アプリのアラーム画面へ送る」
という形にした。一覧とトグルは端末の時計アプリ側に任せる。

更新契機は `AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED`。これは Android 8 以降の
暗黙ブロードキャスト制限の**例外リストに入っている**ので、マニフェスト宣言の receiver で受けられる。
`updatePeriodMillis` は 0 にしてあり、定期更新も常駐もしない。

`ACTION_DISMISS_ALARM` + `SEARCH_MODE=next` で「次の 1 回だけスキップ」は理屈の上では可能だが、
Google 時計で試したところ発火直前 (upcoming ウィンドウ内) 以外は無反応だった。
Samsung の挙動も未確認なので、当てにできる機能としては採用していない。

## 5. ウィジェットの可変サイズとテーマ

### 文字サイズ

`AppWidgetManager.getAppWidgetOptions()` が返す寸法から毎回計算する。API 31 以降は
`OPTION_APPWIDGET_SIZES` に取り得るサイズが並ぶので、その中で最小のものを使う。
どの向きでも中身が収まるようにするため。無ければ `MIN_WIDTH` / `MIN_HEIGHT` に落とす。
リサイズ時は `onAppWidgetOptionsChanged` が来るので、そこで描き直す。

高さは「見出し + 時刻 + 日付」を時刻に対する比 (0.42 / 1.0 / 0.46) で積み上げた合計で割り、
幅は時刻の文字数から必要幅を見積もって割る。小さい方を採用する。
高さが足りないときは見出し (68dp 未満) → 日付 (46dp 未満) の順に落とす。

1 行が占める高さの係数は 1.45。`includeFontPadding` を切った状態の実測が
英数字で約 1.17、日本語で約 1.43 だったので、どの文字が来ても溢れないよう日本語側に寄せてある。

### 背景と枠線

枠線の太さを実行時に変えたいので、外枠 (`widgetRoot`) と中身 (`widgetInner`) の 2 枚重ねにして、
外枠の padding をそのまま枠線として見せる。`RemoteViews.setViewPadding` で太さを、
`setColorStateList(viewId, "setBackgroundTintList", ...)` (API 31 以降) で色を変える。
枠線があるときだけ中身の角丸を `system_app_widget_inner_radius` に差し替える。

背景ごとに drawable を用意する方法も Bitmap を生成して敷く方法も採らなかった。
前者は「背景 5 種 x 枠線 4 段階」で 20 個に増えるし、後者はリサイズのたびに
実寸の Bitmap を作り直すことになる。tint なら drawable 2 個で足りる。

「システムに合わせる」のときは色を一切指定しない。指定しなければレイアウトの `@color/widget_*` が
そのまま効き、`values-night` との出し分けはウィジェットを描くホーム画面アプリの設定に従う。
結果として端末のダークモード切替へ自動で追従する。明示指定したときだけ tint と `setTextColor` を呼ぶ。

設定はウィジェット単位ではなく端末に 1 つ。設定用 Activity (`android:configure`) を置くと
追加のたびに設定画面を挟むことになるので、セットアップ画面にまとめて、
変更時に `NextAlarmWidgetProvider.refresh()` で全ウィジェットを描き直している。

## 6. アプリのショートカット

### アプリ一覧とアイコン

MAIN + LAUNCHER の `queryIntentActivities` で取る。Android 11 以降のパッケージ可視性があるので、
マニフェストの `<queries>` に同じ intent を書いてある。これでランチャーに出るアプリが見えるようになり、
Play の制限対象である `QUERY_ALL_PACKAGES` を使わずに済む。

アイコンは `getActivityIcon()` の Drawable。RemoteViews には Drawable を渡せないので Bitmap に焼く。
アダプティブアイコンは全面が塗られていて、そのまま描くと四角いままになるので、
ランチャーと同じように角を丸めてから描いている。Bitmap は binder 越しに渡るため
320px (ARGB で約 400KB) を上限にして、それを超える表示サイズでは ImageView 側で拡大させる。

### アイコンの大きさとラベル

ホーム画面のアイコンと大きさが揃わない、という話への対応。

**ランチャーの設定は読めない。** One UI も Pixel ランチャーも、アイコンサイズやラベル表示の
設定はランチャー自身の内部設定で、他アプリからは見えない。`settings list secure/system` にも
出てこないことを実機 (エミュレータ) で確認した。`ActivityManager.getLauncherLargeIconSize()` は
`android.R.dimen.app_icon_size` (48dp) を返すだけで、ランチャーが実際に描く大きさとは無関係。
ウィジェットのホストが自分の描画メトリクスを教える API も無い。

そこで自動判定はあきらめ、ショートカットごとに dp で選ばせている。dp で出しているのは、
「隣のアイコンと並べて合う値」を本人が数回試して決められるようにするため。
既定は 56dp。端末の標準値は 48dp だが、Pixel ランチャーの実測ではアイコンが約 57dp で
描かれていて、48dp では明らかに小さく見えた。

指定サイズがセルに入らないときは縮める。ラベルを出すとアイコンが 16dp 未満まで潰れる場合は
ラベルの方をあきらめる。はみ出させないことを優先している。

### 設定の持ち方

ショートカットは「どのアプリを開くか」がウィジェットごとに違うので、
`android:configure` で設定用 Activity を持たせ、設定は appWidgetId をキーにして保存する。
`widgetFeatures="reconfigurable"` を付けてあるので、置いたあとでも開き直せる。
`onDeleted` で設定も消す。

見た目もショートカットごとに持たせた。既定は「透明 + 枠線なし」。アプリのアイコンを
ホーム画面にそのまま置きたいことが多く、カード背景が付くと浮くため。
「次のアラーム」ウィジェットの見た目が端末に 1 つなのは、そちらは複数置く理由が薄いからで、
使い分けている。

`requestPinAppWidget` で追加した場合、ホーム画面アプリによっては設定 Activity が
自動で開かない。そのため未設定のウィジェットは「未設定」と表示し、
タップすると設定画面に飛ぶようにしてある。

### ディープリンク (削除済み)

URI を指定してアプリ内リンクを開く機能を一度入れたが、使わないという判断で外した。
対象アプリに Intent が届いたあと、アプリ側が「この URL は自分で扱わない」と判断して
ブラウザに投げ直すことがあり、こちらでは制御できないのも理由。

## 7. タイマー

時計アプリの `ACTION_SET_TIMER` に任せる案もあったが、自前で数えて鳴らすことにした。
残り時間をウィジェットに出すには、動いているタイマーの終了時刻をこちらが知っている必要がある。
しかし時計アプリの状態は外から読めない (4 章と同じ理由)。

### 状態の持ち方

状態は `Timer` オブジェクトだけが持ち、SharedPreferences に `IDLE` / `RUNNING` / `RINGING` と終了時刻を置く。
ウィジェット・通知・完了画面はそれを読んで描くだけ。操作はすべて `TimerReceiver` に集めた。
ウィジェットのボタン、通知のボタン、アラームの発火、再起動がここに届く。

終了時刻は `elapsedRealtime` 基準で持つ。時刻合わせの影響を受けないため。
ただし再起動で 0 に戻るので、壁時計の終了時刻も併せて保存しておく。
`BOOT_COMPLETED` を受けたら壁時計から残りを出し直して張り直す。
再起動中に終わっていた場合は鳴らさず、通知だけ出す。Android 15 以降は `BOOT_COMPLETED` から
mediaPlayback の FGS を起動できないため。

ボタンの PendingIntent は、長さを data URI (`timer://start/180000`) に入れて区別する。
extra の違いだけでは同一視され、最後に作ったものに上書きされてしまう。

### 発火

`setAlarmClock` は使わない。使うと `getNextAlarmClock()` にタイマーが出てしまい、
「次のアラーム」ウィジェットとステータスバーの時計アイコンが乗っ取られる。
`setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` なら Doze 中でも発火する。
発火時には一時的な許可リスト入りもするので、そのまま前景サービスを起動できる。
権限は `USE_EXACT_ALARM` で足り、`SCHEDULE_EXACT_ALARM` のような本人の許可操作は要らない。

### 鳴らす

鳴らしている間だけ `RingService` (FGS, mediaPlayback) を動かす。アラームと共用で、詳しくは 8 章。
既定のアラーム音を `MediaPlayer` でループ再生し、`AudioAttributes.USAGE_ALARM` を付ける。
バイブにも `VibrationAttributes.USAGE_ALARM` を付ける。FGS の通知は全画面インテント付きで出す。
ロック中・画面オフなら `TimerRingingActivity` が直接開き (`showWhenLocked` / `turnScreenOn`)、
使用中ならヘッドアップ通知になる。通知のチャンネル自体は無音にしてある。
音はサービスが鳴らすので、チャンネルからも鳴ると二重になるため。

FGS を起動できなかったときの保険として、アラーム音付きのチャンネル (`FLAG_INSISTENT`) で
同じ通知を出す。音は通知を消すまで鳴り続ける。

自動停止モードでは、サービス内の `Handler` で指定時間後に止める。止めたら「終わりました」の通知を残す。

### 残り時間の表示

ウィジェットも通知も `Chronometer` のカウントダウンに描かせる
(RemoteViews の `setChronometer` + `setChronometerCountDown`、通知の `setUsesChronometer` + `setChronometerCountDown`)。
毎秒の更新も常駐もしない。描き直すのは状態が変わったときとリサイズのときだけ。
鳴動中は同じ Chronometer を `+%s` 形式の数え上げに切り替えて、鳴ってからの経過を出す。

### 延長

- 動作中の `+1分`: 終了時刻を後ろにずらして張り直す
- 鳴動中の `+N分`: 音を止め、「今から N 分」で `RUNNING` に戻す

延長の直後に古いアラームが届くことがある。そのため `fire()` では残り時間を見て、
まだ 1 秒以上残っていれば張り直すだけにしてある。

## 8. 自前のアラーム

時計アプリのアラームは外から一覧も ON/OFF もできない (4 章)。そこでアラームをこのアプリで持つ。
鳴らす仕組みはタイマーのものを共用する。

### データ

1 件は「時・分・曜日のビット列・ラベル・ON/OFF・スヌーズの再鳴動時刻」。曜日が 0 なら 1 回きり。
全件を JSON にして SharedPreferences に置く。件数は多くて十数件なので、DB は要らない。

保存先は **device protected storage** (`createDeviceProtectedStorageContext`)。
再起動した直後は、一度ロックを解除するまで通常の保存領域が読めない。Galaxy は既定で深夜に自動再起動
することがあり、通常の保存領域に置くと、朝ロックを解除するまでアラームが張られない。
receiver・サービス・鳴動画面を `directBootAware` にして `LOCKED_BOOT_COMPLETED` を受け、
ロック解除前でも張り直して鳴らせるようにした。タイマーの状態と設定も同じ領域に移した
(0.3.0-test1 からの更新時に `moveSharedPreferencesFrom` で移す)。

### 張り方

AlarmManager に張るのは、全アラームの中で次に鳴る 1 回だけ。同じ時刻のものはまとめる。
何かが変わるたびに計算し直して張り直す。張った時刻と、そのときに鳴らすアラームの ID も保存する。
発火したときに「どれが鳴ったか」を、時刻から逆算しなくて済むようにするため。

タイマーと違い、こちらは `setAlarmClock` を使う。最も確実に Doze を抜けるうえ、
ステータスバーの時計アイコンと `getNextAlarmClock()` にも載る。
つまり既存の「次のアラーム」ウィジェットに、時計アプリのものと同じように出る。

再起動・時刻合わせ・タイムゾーン変更では張り直す。「7:00」が指す瞬間が変わるため。
再起動の後始末は `LOCKED_BOOT_COMPLETED` と `BOOT_COMPLETED` の両方で届く。2 回目をやり直すと、
その間に鳴り始めたアラームを「再起動で止まったもの」と取り違える。そこで `Settings.Global.BOOT_COUNT` を
覚えておき、同じ起動の 2 回目は張り直しだけにしている。

電源が切れている間に過ぎたアラームは、鳴らさずに「鳴らせなかった」通知を出す。
1 回きりのものは OFF にする。放っておくと翌日の同じ時刻に鳴ってしまうため。

### 鳴った後

- 発火: 1 回きりのものはこの時点で OFF にし、スヌーズも消す。こうしておくと、
  鳴っている最中に次の 1 回を張り直しても、今鳴っているものをもう一度拾わない
- スヌーズ: 鳴っているもの全部に「今 + N 分」の再鳴動時刻を付ける。スヌーズ中は OFF でも鳴る
  (1 回きりのものは発火時に OFF になっているため)
- 停止: 鳴っている扱いを解くだけ。繰り返しのものは次の回に、1 回きりのものは OFF のまま
- 自動スヌーズ: 「N分で自動スヌーズ」なら、上限まで鳴ったところでスヌーズと同じ扱いにする。
  止めずに寝続けた場合に、黙って鳴り止むよりは鳴り直す方がよいため

### 鳴らす仕組みの共用

`RingService` はアラームとタイマーの両方を鳴らす。自分では状態を持たず、`RingSource` (ALARM / TIMER) に
「今鳴らすべきか」を毎回聞く。両方が同時に鳴っても音とバイブは 1 本だけにし、前景サービスの通知は
アラームを優先する。もう片方の鳴動通知も別に出す。状態を変えた側は必ず `RingService.update()` を呼ぶ。
鳴らすものが無ければサービスを止め、あれば起動するか描き直す。

`startForegroundService` を頼んでから `onStartCommand` が走るまでの間に止められることがある。
そのときも `startForeground` は一度呼ばないと落とされる。鳴動の通知を使うと完了画面が一瞬開いてしまうので、
全画面インテントの無い仮の通知を出してすぐに止める。

既定のアラーム音が読めないとき (着信音の保存先がロック解除前で読めない等) は、
`ToneGenerator` の電子音を `STREAM_ALARM` で鳴らす。無音にはしない。

### 一覧ウィジェット

`RemoteViewsService` によるコレクションは使わず、行を `addView` で並べる。入りきらない分は
「他 N 件」にまとめる。スクロールはできないが、行ごとの ON/OFF ボタンに普通の PendingIntent が
使えるので、仕組みが単純になる。

ウィジェットには「あと N 分」を出さない。ウィジェットは時間とともに自動では描き直されないので、
相対表示はすぐ古くなる (エミュレータで 9 時間放置して確認した)。次のアラームは「明日 7:00」のような
絶対時刻で出す。「今日 / 明日」は日付が変わると古くなるが、`ACTION_DATE_CHANGED` は Android 8 以降
マニフェストの receiver に届かない。そこで毎日 0:00:01 に非 wakeup の `RTC` アラームを張り、
両方のウィジェットを描き直す。鳴らすものではないので、寝ている間に過ぎても次に起きたときに届けばよい。

ボタンの色は tint で付ける。tint は下地の色のアルファに掛かるので、半透明の下地に tint すると薄くなりすぎる。
tint するときは不透明な下地 (`bg_widget_pill`) に差し替えてから色を付ける。

### アラームタイルとの関係

アラーム一覧の画面は `SHOW_ALARMS` も受ける。アラームタイルの「開き先」でこのアプリを選べば、
ここに直行する。`AlarmClock` の javadoc に従い、受け口は `SET_ALARM` で保護した。
自動判定 (`Alarms.explicit`) ではこのアプリを選ばない。これまでどおり Galaxy の時計アプリが開く。

## 9. プロジェクト構成

```
app/src/main/
├── AndroidManifest.xml          権限宣言 / <queries> / TileService 3 つ / ウィジェット 4 つ
├── java/net/shino3/qsmultitools/
│   ├── Features.kt              UsbDebug / ScreenTimeout / Alarms の判定と操作。UI を持たない
│   ├── BaseTileService.kt       タイル共通処理 (状態反映、activity 起動、ロック解除、トースト)
│   ├── UsbDebugTileService.kt
│   ├── ScreenTimeoutTileService.kt
│   ├── AlarmTileService.kt
│   ├── NextAlarmWidgetProvider.kt  次のアラームウィジェット
│   ├── WidgetAppearance.kt      ウィジェットのテーマ / 枠線 / 文字サイズ計算
│   ├── AppShortcut.kt           ショートカットの設定 / アプリ一覧 / アイコン
│   ├── AppShortcutWidgetProvider.kt
│   ├── AppShortcutConfigActivity.kt  ショートカットの設定画面
│   ├── Timer.kt                 タイマーの状態と操作 / 設定 / 通知
│   ├── TimerReceiver.kt         ボタン・発火・再起動の受け口
│   ├── RingService.kt           鳴らしている間だけ動く FGS (アラームとタイマーで共用)
│   ├── Alarm.kt                 アラームのデータ / 保存 / 張り方 / 設定 / 通知
│   ├── AlarmReceiver.kt         発火・スヌーズ・停止・ON/OFF・再起動・時刻変更の受け口
│   ├── AlarmListActivity.kt     アラーム一覧と鳴り方の設定
│   ├── AlarmEditActivity.kt     追加・編集
│   ├── AlarmRingingActivity.kt  鳴動画面 (スヌーズ / 停止)
│   ├── AlarmWidgetProvider.kt   アラーム一覧ウィジェット
│   ├── Storage.kt               ロック解除前でも読める保存領域
│   ├── TimerRingingActivity.kt  完了画面 (停止 / 延長)
│   ├── TimerWidgetProvider.kt   タイマーウィジェット
│   ├── Ui.kt                    edge-to-edge の inset 処理
│   └── MainActivity.kt          セットアップ画面
└── res/                         XML レイアウト、vector drawable、文字列
```

方針として次を守っている。

- 依存ライブラリはゼロ。`android.app.Activity` と XML レイアウトだけで組む。
  AndroidX も Compose も入れない
- 状態判定のロジックは `Features.kt` に集約し、タイルとセットアップ画面の両方が同じものを見る
- 権限や端末差で失敗しうる箇所は全て `runCatching` で包み、アプリが落ちないようにする
- targetSdk 35 以降は edge-to-edge が強制される。DeviceDefault のアクションバーは本文に
  重なってしまうので、アクションバーは使わず、見出しは画面の中に置いて
  `padForSystemBars()` で inset を padding にする
- 設定を書いたら必ず読み戻して確認する
- タイルは通常モード。バインドされるのはクイック設定パネルの表示中だけで常駐しない
