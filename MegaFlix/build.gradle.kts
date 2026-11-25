version = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

plugins {
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language = "en"
    authors = listOf("MegaFlix")
    description = "One search across every installed CloudStream provider. Groups duplicate titles, labels source counts, and aggregates playable links without bundling other extensions."
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon", "OVA", "Live", "Documentary", "AsianDrama", "Others")
    requiresResources = false
    val slug = System.getenv("GITHUB_REPOSITORY") ?: "senpaiorbit/CrunchyMe"
    setRepo("https://github.com/$slug")
    iconUrl = "https://raw.githubusercontent.com/$slug/main/MegaFlix/icon.png"
}

android {
    namespace = "com.megaflix"
}

dependencies {
    val cloudstream by configurations
    val testImplementation by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    testImplementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
    testImplementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
    val cloudstreamJar = File(
        System.getProperty("user.home"),
        ".gradle/caches/cloudstream/cloudstream/cloudstream.jar",
    )
    if (cloudstreamJar.exists()) testImplementation(files(cloudstreamJar))
}
