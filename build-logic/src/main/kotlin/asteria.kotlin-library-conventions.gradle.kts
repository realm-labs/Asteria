import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
    id("com.vanniktech.maven.publish")
}

group = providers.gradleProperty("GROUP").orElse("io.github.realm-labs").get()
version = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

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

mavenPublishing {
    publishToMavenCentral()

    coordinates(group.toString(), project.name, version.toString())

    pom {
        name.set(providers.gradleProperty("ASTERIA_POM_NAME").orElse("Asteria").map { baseName ->
            "$baseName ${project.name}"
        })
        description.set(
            providers.gradleProperty("ASTERIA_POM_DESCRIPTION")
                .orElse("Modular game server framework for Kotlin.")
                .map { description -> "$description Module: ${project.path}." },
        )
        inceptionYear.set("2026")
        url.set(
            providers.gradleProperty("ASTERIA_POM_URL")
                .orElse("https://github.com/realm-labs/Asteria"),
        )
        licenses {
            license {
                name.set(
                    providers.gradleProperty("ASTERIA_POM_LICENSE_NAME")
                        .orElse("The Apache License, Version 2.0"),
                )
                url.set(
                    providers.gradleProperty("ASTERIA_POM_LICENSE_URL")
                        .orElse("https://www.apache.org/licenses/LICENSE-2.0.txt"),
                )
                distribution.set(providers.gradleProperty("ASTERIA_POM_LICENSE_DIST").orElse("repo"))
            }
        }
        developers {
            developer {
                id.set(providers.gradleProperty("ASTERIA_POM_DEVELOPER_ID").orElse("mikai"))
                name.set(providers.gradleProperty("ASTERIA_POM_DEVELOPER_NAME").orElse("Realm Labs"))
                url.set(
                    providers.gradleProperty("ASTERIA_POM_DEVELOPER_URL")
                        .orElse("https://github.com/realm-labs"),
                )
            }
        }
        scm {
            url.set(
                providers.gradleProperty("ASTERIA_POM_SCM_URL")
                    .orElse("https://github.com/realm-labs/Asteria"),
            )
            connection.set(
                providers.gradleProperty("ASTERIA_POM_SCM_CONNECTION")
                    .orElse("scm:git:git://github.com/realm-labs/Asteria.git"),
            )
            developerConnection.set(
                providers.gradleProperty("ASTERIA_POM_SCM_DEV_CONNECTION")
                    .orElse("scm:git:ssh://git@github.com/realm-labs/Asteria.git"),
            )
        }
    }
}
