// Standalone JVM-only build of :core and :verifier.
// Use it to run the extractor unit tests and the real-URL verification harness on a machine
// that has no Android SDK:   ./gradlew -p tools/jvm-build test   (or :verifier:run)
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "SaveIt-jvm"

include(":core", ":verifier")
project(":core").projectDir = file("../../core")
project(":verifier").projectDir = file("../../verifier")
