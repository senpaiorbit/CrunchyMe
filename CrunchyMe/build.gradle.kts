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
    val slug = System.getenv("GITHUB_REPOSITORY") ?: "YOUR_GITHUB_USER/CrunchyMe"
    setRepo("https://github.com/$slug")
    iconUrl = "https://raw.githubusercontent.com/$slug/main/CrunchyMe/icon.png"
}

android {
    namespace = "com.crunchyme"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

}
