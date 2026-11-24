-keep class com.crunchyme.** { *; }
-keep @com.lagradost.cloudstream3.plugins.CloudstreamPlugin class * { *; }
-keepclassmembers class * {
    @com.lagradost.cloudstream3.plugins.CloudstreamPlugin <methods>;
}
-dontwarn com.lagradost.**
-dontwarn okhttp3.**
-dontwarn kotlinx.**
