rootProject.name = "CrunchyMePlugins"

// Every subdirectory that contains a build.gradle.kts is treated as a plugin module.
File(rootDir, ".").eachDir { dir ->
    if (File(dir, "build.gradle.kts").exists()) include(dir.name)
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
