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
        // NewPipeExtractor (and its nanojson fork) are only published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.TeamNewPipe.*") }
        }
    }
}
rootProject.name = "xdm"
include(":app")
