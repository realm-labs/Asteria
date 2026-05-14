plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    implementation(project(":foundation:foundation-ksp-support"))
    implementation(project(":config:config-annotations"))
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.ksp.symbol.processing.api)
}
