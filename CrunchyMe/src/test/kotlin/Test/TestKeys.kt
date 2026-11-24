package test

import java.io.File

/** Live tests need a TMDB key: TMDB_API_KEY or tmdb.api.key in local.properties. */
object TestKeys {

    fun tmdb(): String? {
        System.getenv("TMDB_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(4) {
            val file = directory?.resolve("local.properties")
            if (file?.isFile == true) {
                file.readLines()
                    .firstOrNull { it.trim().startsWith("tmdb.api.key=") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
            directory = directory?.parentFile
        }
        return null
    }
}
