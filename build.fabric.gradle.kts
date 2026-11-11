// Build script of every :<mc>-fabric project. Values come from gradle.properties and versions/<mc>-fabric/gradle.properties.
plugins {
    id("net.fabricmc.fabric-loom")
    id("me.modmuss50.mod-publish-plugin")
}

fun prop(name: String) = property(name).toString()
val mc = stonecutter.current.version
val modId = prop("mod_id")

group = prop("group")
base.archivesName = "$modId-fabric-$mc"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(prop("java_version"))
    withSourcesJar()
}

dependencies {
    minecraft("com.mojang:minecraft:$mc")
    implementation("net.fabricmc:fabric-loader:${prop("fabric_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_version")}")
}

loom {
    runs {
        named("client") {
            client()
            displayName = "Fabric Client ($mc)"
            generateRunConfig = true
            runDirectory = layout.projectDirectory.dir("runs/client")
        }
        named("server") { generateRunConfig = false }
    }
}

tasks.processResources {
    exclude("META-INF/neoforge.mods.toml")
    val props = mapOf(
        "version" to project.version.toString(),
        "mod_id" to modId,
        "mod_name" to prop("mod_name"),
        "mod_author" to prop("mod_author"),
        "description" to prop("description"),
        "license" to prop("license"),
        "java_version" to prop("java_version"),
        "fabric_loader_version" to prop("fabric_loader_version"),
        "minecraft_range" to prop("minecraft_range"),
        "worker_url" to prop("worker_url"),
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "pack.mcmeta", "applecraft-build.json")) { expand(props) }
}

tasks.jar {
    from(rootProject.file("LICENSE")) { rename { "${it}_${prop("mod_name")}" } }
}

// Modrinth + CurseForge. Run by .github/workflows/publish.yml, or locally: DRY_RUN=true ./gradlew publishMods
publishMods {
    file = tasks.jar.flatMap { it.archiveFile }
    modLoaders.add("fabric")
    displayName = "AppleCraft ${project.version} (Fabric $mc)"
    version = "${project.version}+$mc-fabric"
    changelog = providers.environmentVariable("CHANGELOG").orElse("See the GitHub release for details.")
    type = when (System.getenv("RELEASE_TYPE")) { "beta" -> BETA; "alpha" -> ALPHA; else -> STABLE }
    dryRun = System.getenv("DRY_RUN") == "true"

    val versions = prop("publish_versions").split(',').map { it.trim() }
    modrinth {
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        projectId = prop("modrinth_id")
        minecraftVersions.addAll(versions)
        environment = CLIENT_ONLY
        requires("fabric-api")
        // Keeps the Modrinth page in sync with MODRINTH.md on every release
        projectDescription = providers.fileContents(rootProject.layout.projectDirectory.file("MODRINTH.md")).asText
    }
    curseforge {
        accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
        projectId = prop("curseforge_id")
        minecraftVersions.addAll(versions)
        javaVersions.add(JavaVersion.toVersion(prop("java_version")))
        client = true    // client-side mod: not needed on servers
        server = false
        requires("fabric-api")
    }
}
