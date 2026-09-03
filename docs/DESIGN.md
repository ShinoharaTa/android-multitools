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

候補は 30秒 / 2分 / 10分 / 30分。次の値は「今より大きい最初の候補、無ければ先頭」で決める。
この規則なら現在値が候補になくても素直に動く。15 秒なら 30 秒へ、5 分なら 10 分へ、
`Int.MAX_VALUE` (消灯しない) なら 30 秒へ戻る。

### アラーム画面を開く

`AlarmClock.ACTION_SHOW_ALARMS` (API 19 から)。これは「アラーム一覧を開く」ための標準 Intent で、
`ACTION_SET_ALARM` (追加) とは別物。Galaxy 標準の時計アプリもこれを受ける。

Samsung 固有の component 名や undocumented な extra には依存しない。
標準 Intent が解決できなかったときだけ、`com.sec.android.app.clockpackage` →
`com.google.android.deskclock` の順にランチャー Intent へフォールバックする。

Android 11 以降のパッケージ可視性があるので、解決可否を判定するにはマニフェストに
`<queries>` で `SHOW_ALARMS` と時計アプリのパッケージ名を書いておく必要がある。

## 2. 必要な permission

| permission | 用途 | 付与方法 |
| --- | --- | --- |
| `WRITE_SECURE_SETTINGS` | `Settings.Global.ADB_ENABLED` の書き込み | `adb shell pm grant`。マニフェストに宣言していないと grant 自体が失敗する |
| `WRITE_SETTINGS` | `Settings.System.SCREEN_OFF_TIMEOUT` の書き込み | 設定アプリの「システム設定の変更」、または `adb shell appops set` |
| `BIND_QUICK_SETTINGS_TILE` | `TileService` を SystemUI にバインドさせる | サービス側の `android:permission` に書くだけ。要求はしない |

アラームには権限が要らない。

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

## 4. アラーム一覧ウィジェットを作らなかった理由

「設定済みアラームの一覧を出して個別に ON/OFF する」ウィジェットは、公開 API では作れない。
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

## 6. プロジェクト構成

```
app/src/main/
├── AndroidManifest.xml          権限宣言 / <queries> / TileService 3 つ
├── java/net/shino3/qsmultitools/
│   ├── Features.kt              UsbDebug / ScreenTimeout / Alarms の判定と操作。UI を持たない
│   ├── BaseTileService.kt       タイル共通処理 (状態反映、activity 起動、ロック解除、トースト)
│   ├── UsbDebugTileService.kt
│   ├── ScreenTimeoutTileService.kt
│   ├── AlarmTileService.kt
│   ├── NextAlarmWidgetProvider.kt  次のアラームウィジェット
│   ├── WidgetAppearance.kt      ウィジェットのテーマ / 枠線 / 文字サイズ計算
│   └── MainActivity.kt          セットアップ画面
└── res/                         XML レイアウト、vector drawable、文字列
```

方針として次を守っている。

- 依存ライブラリはゼロ。`android.app.Activity` と XML レイアウトだけで組む。
  AndroidX も Compose も入れない
- 状態判定のロジックは `Features.kt` に集約し、タイルとセットアップ画面の両方が同じものを見る
- 権限や端末差で失敗しうる箇所は全て `runCatching` で包み、アプリが落ちないようにする
- 設定を書いたら必ず読み戻して確認する
- タイルは通常モード。バインドされるのはクイック設定パネルの表示中だけで常駐しない
