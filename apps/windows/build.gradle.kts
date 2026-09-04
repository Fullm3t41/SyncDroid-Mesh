import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "com.syncdows"
version = "1.2.9"

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        resources.srcDir("../../design/icon")
    }
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
        mainClass = "com.syncdows.app.MainKt"

        nativeDistributions {
            modules("java.sql", "java.naming", "java.logging", "java.xml", "java.net.http", "jdk.crypto.ec")
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "SyncDows"
            // jpackage requires a non-zero first component even while the product is pre-1.0.
            packageVersion = "1.2.9"
            description = "SyncDows Background Sync"
            vendor = "Fullm3t41"

            windows {
                iconFile.set(project.file("src/main/resources/icons/syncdows.ico"))
                menuGroup = "SyncDows"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "7f646530-5b2f-4a97-ae0e-d99ce65b6f0e"
            }
        }
    }
}
