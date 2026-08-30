import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "com.synctosh"
version = "1.2.8"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":mesh-protocol"))
    implementation(project(":sync-core"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.jmdns:jmdns:3.6.2")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.synctosh.app.MainKt"
        jvmArgs += listOf("-Xms16m", "-Xmx512m", "-XX:ReservedCodeCacheSize=96m")

        nativeDistributions {
            modules("java.sql", "java.naming", "java.logging", "java.xml", "java.net.http", "jdk.crypto.ec")
            targetFormats(TargetFormat.Dmg)
            packageName = "SyncTosh"
            // jpackage requires the first bundle-version component to be non-zero.
            packageVersion = "1.2.8"
            description = "Apple Silicon local-first peer-to-peer folder synchronization for SyncDroid-Mesh"
            vendor = "Fullm3t41"

            macOS {
                bundleID = "com.synctosh.app"
                dockName = "SyncTosh"
                minimumSystemVersion = "13.0"
                iconFile.set(project.file("src/main/resources/icons/synctosh.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>SyncTosh discovers trusted devices and synchronizes files directly over your local Wi-Fi network.</string>
                        <key>NSLocationWhenInUseUsageDescription</key>
                        <string>SyncTosh uses the current Wi-Fi network name so you can approve it for background synchronization.</string>
                        <key>NSBonjourServices</key>
                        <array>
                            <string>_syncdroid._tcp</string>
                            <string>_syncdroid-pair._tcp</string>
                        </array>
                    """.trimIndent()
                }
            }
        }
    }
}
