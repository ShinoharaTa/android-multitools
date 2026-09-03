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

## 4. プロジェクト構成

```
app/src/main/
├── AndroidManifest.xml          権限宣言 / <queries> / TileService 3 つ
├── java/net/shino3/qsmultitools/
│   ├── Features.kt              UsbDebug / ScreenTimeout / Alarms の判定と操作。UI を持たない
│   ├── BaseTileService.kt       タイル共通処理 (状態反映、activity 起動、ロック解除、トースト)
│   ├── UsbDebugTileService.kt
│   ├── ScreenTimeoutTileService.kt
│   ├── AlarmTileService.kt
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
