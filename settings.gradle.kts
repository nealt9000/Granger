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
        // Readium's PDFium adapter depends on PdfiumAndroid and AndroidPdfViewer, published only on JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.marain87") } }
    }
}

rootProject.name = "Granger"
include(":app", ":core:ai", ":core:zim", ":core:library", ":core:catalog", ":core:downloads", ":core:books", ":core:archive", ":core:research", ":core:taglib", ":core:media", ":core:player", ":core:speech", ":core:text", ":core:vectors", ":tools:chunker")
