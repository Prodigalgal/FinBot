pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "finbot-backend"

include(
    "finbot-domain",
    "finbot-application",
    "finbot-infrastructure",
    "finbot-bootstrap",
    "finbot-migration",
)
