plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.3.21")
    implementation("dev.detekt:dev.detekt.gradle.plugin:2.0.0-alpha.3")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.36.0")
}
