pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // GeckoView, Firefox's engine for apps.
        maven("https://maven.mozilla.org/maven2/")
    }
}
rootProject.name = "SpotifyWrapper"
include(":app")
