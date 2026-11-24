import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("com.github.recloudstream.gradle:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.android(configuration: LibraryExtension.() -> Unit) =
    extensions.getByName<LibraryExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")

    android {
        namespace = "com.example"
        compileSdk = 36
        defaultConfig {
            minSdk = 21
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        testOptions {
            unitTests.all { it.useJUnitPlatform() }
            unitTests.isReturnDefaultValues = true
        }
        buildTypes {
            release {
                isMinifyEnabled = false
                isShrinkResources = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            debug {
                isMinifyEnabled = false
                isShrinkResources = false
            }
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                listOf(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            )
        }
    }

    dependencies {
        val compileOnly by configurations
        val testImplementation by configurations

        testImplementation(kotlin("test"))
        testImplementation(kotlin("test-junit5"))
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

        // Provided by the CloudStream app at runtime, so they must not be bundled.
        compileOnly(kotlin("stdlib"))
        compileOnly("com.github.Blatzar:NiceHttp:0.4.18")
        compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

        // Unit tests run on the JVM, outside the app.
        testImplementation("com.github.Blatzar:NiceHttp:0.4.18")
        testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        testImplementation("org.json:json:20240303")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
