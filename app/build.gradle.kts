import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.turbomacro.app"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.turbomacro.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 28
        versionName = "1.8.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".diagnostic"
            versionNameSuffix = "-diagnostic"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // libtouchmux.so is execve'd via Shizuku; keep uncompressed & extractable
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("touchmuxJniLibs"))
        }
    }
}

fun stageTouchmuxFromCmake(buildType: String) {
    val abi = "arm64-v8a"
    val outJni = layout.buildDirectory.dir("touchmuxJniLibs/$abi").get().asFile
    outJni.mkdirs()

    val searchRoots = listOf(
        layout.buildDirectory.dir("intermediates/cxx").get().asFile,
        project.file(".cxx"),
        layout.buildDirectory.get().asFile
    ).filter { it.exists() }

    fun findHelper(): File? {
        for (root in searchRoots) {
            val match = root.walkTopDown()
                .maxDepth(20)
                .firstOrNull { f ->
                    f.isFile &&
                        f.name == "libtouchmux.so" &&
                        f.length() > 8_000 &&
                        (f.path.contains(buildType, ignoreCase = true) ||
                            f.path.contains(buildType.lowercase()) ||
                            f.path.contains("debug", ignoreCase = true) ||
                            f.path.contains("release", ignoreCase = true))
                }
            if (match != null) return match
        }
        for (root in searchRoots) {
            val match = root.walkTopDown()
                .maxDepth(20)
                .firstOrNull { it.isFile && it.name == "libtouchmux.so" && it.length() > 8_000 }
            if (match != null) return match
        }
        return null
    }

    val found = findHelper()
    if (found != null) {
        found.copyTo(File(outJni, "libtouchmux.so"), overwrite = true)
        logger.lifecycle("Staged TouchMux helper: ${found.absolutePath}")
    } else {
        logger.warn("libtouchmux.so not found; searched: ${searchRoots.map { it.absolutePath }}")
    }
}

afterEvaluate {
    listOf("Debug", "Release").forEach { kind ->
        val extNative = tasks.findByName("externalNativeBuild$kind")
            ?: tasks.matching { it.name.contains("externalNativeBuild") && it.name.contains(kind) }.firstOrNull()
        if (extNative == null) {
            logger.warn("No externalNativeBuild$kind task; TouchMux stage hook skipped")
            return@forEach
        }
        val stage = tasks.register("stageTouchmux$kind") {
            dependsOn(extNative)
            doLast { stageTouchmuxFromCmake(kind) }
        }
        listOf(
            "merge${kind}JniLibFolders",
            "merge${kind}NativeLibs",
            "package$kind"
        ).forEach { name ->
            tasks.findByName(name)?.dependsOn(stage)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
