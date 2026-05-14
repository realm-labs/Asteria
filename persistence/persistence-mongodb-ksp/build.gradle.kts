plugins {
    id("asteria.kotlin-library-conventions")
}

dependencies {
    implementation(project(":foundation:foundation-ksp-support"))
    implementation(project(":persistence:persistence-core"))
    implementation(project(":persistence:persistence-mongodb-annotations"))
    implementation(project(":persistence:persistence-mongodb"))
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)
    implementation(libs.ksp.symbol.processing.api)
}
