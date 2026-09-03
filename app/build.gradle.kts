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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
