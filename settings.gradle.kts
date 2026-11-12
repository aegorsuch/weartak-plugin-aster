pluginManagement {
    val user = System.getenv("ARTIFACTORY_USER")?.takeIf(String::isNotBlank)
    val password = System.getenv("ARTIFACTORY_PASSWORD")?.takeIf(String::isNotBlank)
    repositories {
        if (user != null && password != null) {
            maven {
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = user
                    this.password = password
                }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val takUser = System.getenv("ARTIFACTORY_USER")?.takeIf(String::isNotBlank)
val takPassword = System.getenv("ARTIFACTORY_PASSWORD")?.takeIf(String::isNotBlank)
val takUrl = "https://artifacts.tak.gov/artifactory/maven-release"
if (takUser != null && takPassword != null) {
    gradle.extra["takArtifactoryUrl"] = takUrl
    gradle.extra["takArtifactoryUser"] = takUser
    gradle.extra["takArtifactoryPassword"] = takPassword
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (takUser != null && takPassword != null) {
            maven {
                url = uri(takUrl)
                credentials {
                    username = takUser
                    password = takPassword
                }
            }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://github.com/rustls/rustls-platform-verifier/raw/maven-archive/android-release-support/maven/")
            content { includeGroup("org.rustls") }
        }
    }
}

rootProject.name = "WearTAK-Aster-Spike"
include(":app")
include(":integration-test-host")
include(":spike:android")
include(":spike:runtime")
