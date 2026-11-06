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
    }
}

rootProject.name = "navigator-app"
include(":app")
// Rider Intercom (phone-to-phone rider/pillion voice), merged from the standalone
// "BT intercom" project. See intercom/README.md.
include(":intercom")
