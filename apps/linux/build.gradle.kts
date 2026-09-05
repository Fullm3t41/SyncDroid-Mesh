import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "com.syncdeck"
version = "1.2.13"

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
        mainClass = "com.syncdeck.app.MainKt"
        jvmArgs += listOf("-Xms16m", "-Xmx512m", "-XX:ReservedCodeCacheSize=96m")

        nativeDistributions {
            modules("java.sql", "java.naming", "java.logging", "java.xml", "java.net.http", "jdk.crypto.ec")
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "SyncDeck"
            // jpackage requires the first bundle-version component to be non-zero.
            packageVersion = "1.2.13"
            description = "Linux and SteamOS local-first peer-to-peer folder synchronization for SyncDroid-Mesh"
            vendor = "Fullm3t41"

            linux {
                iconFile.set(project.file("src/main/resources/icons/syncdeck.png"))
                shortcut = true
                menuGroup = "Network"
            }
        }
    }
}

// Lets the headless smoke harness launch the exact runtime without loading Compose.
tasks.register("writeRuntimeClasspath") {
    val runtime = sourceSets.main.get().runtimeClasspath
    dependsOn(tasks.named("classes"))
    val outputFile = layout.buildDirectory.file("runtime-classpath.txt").get().asFile
    doLast { outputFile.writeText(runtime.asPath) }
}
