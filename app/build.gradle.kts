plugins {
    // AGP 9 以降は Kotlin サポートが組み込みなので kotlin-android プラグインは要らない。
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.shino3.qsmultitools"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.shino3.qsmultitools"
        // Android 14 で startActivityAndCollapse(Intent) が targetSdk 34+ に対して例外を投げるようになり、
        // 代替の PendingIntent 版も API 34 から。下限を 34 に置けば deprecated 版の分岐がまるごと要らなくなる。
        // One UI 6 以降の Galaxy が対象なのでこれで足りる。
        minSdk = 34
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 個人用でストア公開しないので、リリース版もデバッグ用の鍵で署名する。
            // テスト版 (debug) と同じ鍵なので、入っているものに上書きでき、adb で付けた権限も残る。
            // この Mac の ~/.android/debug.keystore に依存する点に注意 (別の環境でビルドすると鍵が変わる)。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 依存ライブラリなし。フレームワーク API だけで組む。
dependencies {
}
