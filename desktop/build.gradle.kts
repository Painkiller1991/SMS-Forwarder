import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.smsforwarder.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "SMS-Forwarder-PC"
            packageVersion = "1.0.1"
            description = "LAN SMS receiver for Windows (pairs with Android sender)"
            copyright = "SMS Forwarder"
            vendor = "SMS Forwarder"
            windows {
                menuGroup = "SMS Forwarder"
                dirChooser = true
                // Portable folder via createDistributable; Exe is the packaged installer.
            }
        }
    }
}
