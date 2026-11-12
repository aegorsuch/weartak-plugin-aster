plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.weartak.plugin.aster.spike.runtime"
    compileSdk = 34
    defaultConfig {
        applicationId = providers.gradleProperty("asterEvaluationApplicationId")
            .getOrElse("com.weartak.aster.eval")
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-evaluation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":spike:android"))
}
