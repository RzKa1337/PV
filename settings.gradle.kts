pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (RS232-USB adapters) is published on JitPack only.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mik3y") }
        }
    }
}

rootProject.name = "SolarTrackerPro"
include(":core")
include(":app")
