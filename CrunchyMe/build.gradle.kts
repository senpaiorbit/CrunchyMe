version = 1

plugins {
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language = "en"
    authors = listOf("CrunchyMe")
    description = "TMDB-first catalog with a pluggable multi-source stream farm: public-domain archive, Stremio/Nuvio addons and user-defined providers."
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    val slug = System.getenv("GITHUB_REPOSITORY") ?: "senpaiorbit/CrunchyMe"
    setRepo("https://github.com/$slug")
    iconUrl = "https://raw.githubusercontent.com/$slug/main/CrunchyMe/icon.png"
}

android {
    namespace = "com.crunchyme"
}

dependencies {
    val cloudstream by configurations
    val implementation by configurations
    val testImplementation by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

    implementation("org.jsoup:jsoup:1.17.2")
    testImplementation("org.jsoup:jsoup:1.17.2")
}
