plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val bindPluginPermission = providers.gradleProperty("asterBindPluginPermission")
    .orElse("com.weartak.plugin.permission.BIND_PLUGIN")

android {
    namespace = "com.weartak.plugin.aster.hosttest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weartak.plugin.aster.hosttest"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-test"
        manifestPlaceholders["bindPluginPermission"] = bindPluginPermission.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    debugImplementation(files("libs/weartak-plugin-sdk-debug.aar"))
}
