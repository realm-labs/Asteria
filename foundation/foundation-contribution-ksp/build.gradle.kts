plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    implementation(project(":foundation:foundation-ksp-support"))
    implementation(project(":foundation:foundation-contribution"))
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.ksp.symbol.processing.api)
}
