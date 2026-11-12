pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "afterdrop"
include(":testkit", ":formula", ":spec", ":engine", ":composer", ":ai", ":data", ":app")
