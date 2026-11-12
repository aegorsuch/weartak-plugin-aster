plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val nativeLibs = providers.gradleProperty("asterJniLibsDir")
val verifierVersion = providers.fileContents(layout.projectDirectory.file("../native/Cargo.lock"))
    .asText.map { lock ->
        Regex("""(?m)^name = "rustls-platform-verifier-android"\r?\nversion = "([^"]+)"$""")
            .find(lock)?.groupValues?.get(1)
            ?: error("rustls-platform-verifier-android not found in Cargo.lock")
    }

android {
    namespace = "com.weartak.plugin.aster.spike"
    compileSdk = 34

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../kotlin")
            if (nativeLibs.isPresent) jniLibs.srcDir(nativeLibs.get())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.rustls:rustls-platform-verifier:${verifierVersion.get()}")
}

tasks.named("preBuild") {
    doFirst {
        check(nativeLibs.isPresent) { "Set -PasterJniLibsDir to the ABI-organized JNI libraries directory" }
        check(fileTree(nativeLibs.get()).matching { include("**/libweartak_aster_spike.so") }.files.isNotEmpty()) {
            "No libweartak_aster_spike.so found under asterJniLibsDir"
        }
    }
}
