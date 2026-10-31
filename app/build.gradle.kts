plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dshproxy.app"
    compileSdk = 35
    // AGP 8.7.3 默认找 build-tools 34.0.0，但我们只装了 35.0.0（对应 API 35）。
    // 不显式指定会报 "Failed to find Build Tools revision 34.0.0"。
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.dshproxy.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // 单测里用到 org.json，JVM 上需要显式依赖
    testImplementation("org.json:json:20240303")
}
