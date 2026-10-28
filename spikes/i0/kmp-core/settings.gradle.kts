// I0 Task 4: a throwaway KMP build over copies of :core:text, :core:research and :core:vectors. Never merged.
pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
rootProject.name = "kmp-core"
include(":vectors", ":text-research")
