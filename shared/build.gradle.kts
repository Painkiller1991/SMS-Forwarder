plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.nanohttpd:nanohttpd:2.3.1")
    api("org.json:json:20240303")
}
