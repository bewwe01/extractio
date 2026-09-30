plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Network tests hit real platforms; they only run when explicitly requested.
    systemProperty("saveit.liveTests", System.getProperty("saveit.liveTests") ?: "false")
}
