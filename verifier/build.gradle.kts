plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("app.saveit.verifier.MainKt")
}

tasks.named<JavaExec>("run") {
    // Run from the repository root so relative paths (test matrix, output dir) behave as documented.
    workingDir = rootProject.projectDir.let { if (it.name == "jvm-build") it.parentFile.parentFile else it }
    standardInput = System.`in`
}
