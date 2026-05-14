plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    api(libs.ksp.symbol.processing.api)
    api(libs.kotlinpoet)
    api(libs.kotlinpoet.ksp)
}
