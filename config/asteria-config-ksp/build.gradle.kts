plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    implementation(project(":asteria-config-annotations"))
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.ksp.symbol.processing.api)
}
