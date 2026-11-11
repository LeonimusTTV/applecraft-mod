// Build script of every :<mc>-neoforge project. Values come from gradle.properties and versions/<mc>-neoforge/gradle.properties.
plugins {
    id("net.neoforged.moddev")
    id("me.modmuss50.mod-publish-plugin")
}

fun prop(name: String) = property(name).toString()
val mc = stonecutter.current.version
val modId = prop("mod_id")

group = prop("group")
base.archivesName = "$modId-neoforge-$mc"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(prop("java_version"))
    withSourcesJar()
}

neoForge {
    enable {
        version = prop("neoforge_version")
        // -Pneoforge_skip_decompile=true: skips decompiling Minecraft (faster, less RAM; no Minecraft sources in the IDE)
        isDisableRecompilation = findProperty("neoforge_skip_decompile") == "true"
    }
    runs {
        register("client") {
            client()
            gameDirectory = file("runs/client")
            ideName = "NeoForge Client ($mc)"
        }
    }
    mods {
        register(modId) { sourceSet(sourceSets["main"]) }
    }
}

tasks.processResources {
    exclude("fabric.mod.json")
    val props = mapOf(
        "version" to project.version.toString(),
        "mod_id" to modId,
        "mod_name" to prop("mod_name"),
        "mod_author" to prop("mod_author"),
        "description" to prop("description"),
        "license" to prop("license"),
        "credits" to prop("credits"),
        "neoforge_loader_version_range" to prop("neoforge_loader_version_range"),
        "neoforge_version_range" to prop("neoforge_version_range"),
        "minecraft_range" to prop("minecraft_range"),
        "worker_url" to prop("worker_url"),
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta", "applecraft-build.json")) { expand(props) }
}

tasks.jar {
    from(rootProject.file("LICENSE")) { rename { "${it}_${prop("mod_name")}" } }
}

// Modrinth + CurseForge. Run by .github/workflows/publish.yml, or locally: DRY_RUN=true ./gradlew publishMods
publishMods {
    file = tasks.jar.flatMap { it.archiveFile }
    modLoaders.add("neoforge")
    displayName = "AppleCraft ${project.version} (NeoForge $mc)"
    version = "${project.version}+$mc-neoforge"
    changelog = providers.environmentVariable("CHANGELOG").orElse("See the GitHub release for details.")
    type = when (System.getenv("RELEASE_TYPE")) { "beta" -> BETA; "alpha" -> ALPHA; else -> STABLE }
    dryRun = System.getenv("DRY_RUN") == "true"

    val versions = prop("publish_versions").split(',').map { it.trim() }
    modrinth {
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        projectId = prop("modrinth_id")
        minecraftVersions.addAll(versions)
        environment = CLIENT_ONLY
        projectDescription = providers.fileContents(rootProject.layout.projectDirectory.file("MODRINTH.md")).asText
    }
    curseforge {
        accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
        projectId = prop("curseforge_id")
        minecraftVersions.addAll(versions)
        javaVersions.add(JavaVersion.toVersion(prop("java_version")))
        client = true
        server = false
    }
}
