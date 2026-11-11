plugins {
    id("dev.kikugie.stonecutter")
    // Loaded once here so every version project shares them
    id("net.fabricmc.fabric-loom") version "1.18.1" apply false
    id("net.neoforged.moddev") version "2.0.147" apply false
    id("me.modmuss50.mod-publish-plugin") version "2.2.1" apply false
}

// Version shown in the IDE and in src/. Switch with the "Set active project" tasks (stonecutter group).
stonecutter active file(".sc_active_version")

stonecutter parameters {
    // Enables `//? if fabric {` and `//? if neoforge {` in the code
    constants.match(current.project.substringAfterLast('-'), "fabric", "neoforge")
}

// Safety checks, run before ANY upload task starts
gradle.taskGraph.whenReady {
    if (allTasks.none { it.name == "publishMods" }) return@whenReady
    if (System.getenv("DRY_RUN") == "true") return@whenReady
    val worker = findProperty("worker_url")?.toString() ?: ""
    if (!worker.startsWith("https://"))
        throw GradleException("Refusing to publish with worker_url='$worker'. Pass -Pworker_url=https://… (GitHub variable WORKER_URL).")
    if ((findProperty("modrinth_id")?.toString() ?: "").isBlank() || (findProperty("curseforge_id")?.toString() ?: "").isBlank())
        throw GradleException("Set modrinth_id and curseforge_id in gradle.properties before publishing.")
}
