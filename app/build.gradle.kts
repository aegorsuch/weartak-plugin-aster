plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val bindPluginPermission = providers.gradleProperty("asterBindPluginPermission")
    .orElse("com.weartak.plugin.permission.BIND_PLUGIN")

android {
    namespace = "com.weartak.plugin.aster"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weartak.plugin.aster"
        minSdk = 30
        targetSdk = 34
        versionCode = providers.environmentVariable("CI_PIPELINE_IID").orElse("1").get().toInt()
        versionName = "0.1.0-evaluation"
        manifestPlaceholders["bindPluginPermission"] = bindPluginPermission.get()
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    val releaseKeystore = providers.environmentVariable("WEARTAK_RELEASE_KEYSTORE_FILE").orNull
    if (releaseKeystore != null) {
        fun signingValue(name: String): String =
            providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
                ?: error("Missing release signing variable: $name")
        val releaseSigning = signingConfigs.create("weartakRelease") {
            storeFile = rootProject.file(releaseKeystore).also {
                check(it.isFile) { "WearTAK release keystore missing." }
            }
            storePassword = signingValue("RELEASE_STORE_PASSWORD")
            keyAlias = signingValue("RELEASE_KEY_ALIAS")
            keyPassword = signingValue("RELEASE_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = releaseSigning
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            if (gradle.extra.has("takArtifactoryUrl")) {
                it.systemProperty("robolectric.dependency.repo.url", gradle.extra["takArtifactoryUrl"]!!)
                it.systemProperty("robolectric.dependency.repo.username", gradle.extra["takArtifactoryUser"]!!)
                it.systemProperty("robolectric.dependency.repo.password", gradle.extra["takArtifactoryPassword"]!!)
            }
        }
    }

    val legalAssets by tasks.registering(Sync::class) {
        from(rootProject.projectDir) {
            include("LICENSE", "NOTICE", "third_party/**/LICENSE*", "third_party/**/*NOTICES*")
            include("third_party/**/*PROVENANCE.md")
        }
        val nativeLibs = providers.gradleProperty("asterJniLibsDir")
        if (nativeLibs.isPresent) {
            from(file(nativeLibs.get()).resolveSibling("native-notices")) {
                into("native-dependencies")
            }
        }
        into(layout.buildDirectory.dir("generated/legalAssets/legal"))
    }
    android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/legalAssets"))
    tasks.named("preBuild") { dependsOn(legalAssets) }
}

dependencies {
    debugImplementation(files("libs/weartak-plugin-sdk-debug.aar"))
    releaseImplementation(files("libs/weartak-plugin-sdk-release.aar"))
    implementation(project(":spike:android"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
