plugins {
    id("asteria.kotlin-library-conventions")
    `java-gradle-plugin`
}

dependencies {
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.3.21")
    implementation(localGroovy())
    implementation(libs.ksp.symbol.processing.gradle.plugin)

    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("asteriaConfigCodegen") {
            id = "io.github.realm-labs.asteria.config-codegen"
            implementationClass = "io.github.realmlabs.asteria.config.gradle.AsteriaConfigCodegenPlugin"
        }
    }
}

tasks.jar {
    manifest {
        attributes["Implementation-Version"] = project.version.toString()
    }
}
