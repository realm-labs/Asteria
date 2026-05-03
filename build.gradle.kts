plugins {
    kotlin("jvm") version "2.3.21" apply false
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
}

group = providers.gradleProperty("GROUP").orElse("io.github.realm-labs.asteria").get()
version = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()
