pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "Oasis"

// The same split as OasisKit on iOS: core (API, session, models), chat crypto, design (theme, components, routes),
// realtime (Reverb), one feature module per area, and the app that maps routes to screens.
include(":app")
include(":core")
include(":core-testing")
include(":design")
include(":chat-crypto")
include(":realtime")
include(":feature:network")
include(":feature:chat")
include(":feature:account")
include(":feature:live")
include(":feature:membership")
include(":feature:staff")
