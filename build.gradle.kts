import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream.gradle:gradle:81b1d424d")
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

// Extension helper functions using the modern LibraryExtension API
fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = 
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) {
    extensions.getByName<LibraryExtension>("android").apply {
        project.extensions.findByType(JavaPluginExtension::class.java)?.apply {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }
        configuration()
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // Fallback cascade: GitHub Actions
        setRepo(System.getenv("GITHUB_REPOSITORY") 
            ?: "https://github.com/Reflex755/ReflexRepo")
        authors = listOf("Reflex1337")
        useJsDelivr = false // ADDED THIS TO BYPASS JSDELIVR CACHE
    }

    android {
        namespace = "com.reflex1337" 
        compileSdk = 35

        defaultConfig {
            minSdk = 21
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        lint {
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required for Cloudstream runtime compatibility
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xskip-metadata-version-check"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations

        // Cloudstream compilation stubs - THIS PROVIDES ALL NETWORK AND PARSING LIBRARIES.
        // Removed all 'implementation' dependencies to fix the "Error" installation bug.
        cloudstream("com.lagradost:cloudstream3:pre-release")
    }
}

// Clean task updated to use modern register API
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
