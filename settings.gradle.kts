pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Builds the same source for several Minecraft versions: https://stonecutter.kikugie.dev
    id("dev.kikugie.stonecutter") version "0.10"
}

rootProject.name = "applecraft-mod"

stonecutter {
    create(rootProject) {
        // One project per Minecraft version and loader, e.g. :26.3-fabric, each with versions/<name>/gradle.properties
        fun mc(version: String, vararg loaders: String) =
            loaders.forEach { version("$version-$it", version).buildscript = "build.$it.gradle.kts" }

        mc("26.1.2", "fabric", "neoforge")
        mc("26.2", "fabric", "neoforge")
        mc("26.3", "fabric", "neoforge")

        // Version the code in src/ is written for (what gets committed)
        vcsVersion = "26.3-fabric"
    }
}
