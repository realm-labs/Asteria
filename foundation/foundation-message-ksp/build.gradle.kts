plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    implementation(project(":foundation:foundation-message"))
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.ksp.symbol.processing.api)
}
