import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
}

group = "io.github.mikai233"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.3")
}

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(21)
}

extensions.configure<DetektExtension> {
    toolVersion = "2.0.0-alpha.3"
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget.set("21")
    reports {
        checkstyle.required.set(true)
        html.required.set(true)
        markdown.required.set(true)
        sarif.required.set(true)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
