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

rootProject.name = "Granger"
include(":app", ":core:ai", ":core:zim", ":core:library", ":core:catalog", ":core:downloads", ":core:books", ":core:archive", ":core:research", ":core:taglib", ":core:media", ":core:player", ":core:speech", ":core:text", ":core:vectors", ":tools:chunker")
