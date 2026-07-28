import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ── Rust build helpers ──────────────────────────────────────────────────────
// Map from Android ABI → Rust target triple
// Keep in sync with defaultConfig.ndk.abiFilters below
val rustTargetMap = mapOf(
    "arm64-v8a" to "aarch64-linux-android"
)

val ndkVersion  = "27.2.12479018"
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use { load(it) }
}
val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    releaseSigningPropertiesFile.takeIf(File::isFile)?.inputStream()?.use { load(it) }
}
val hasReleaseSigningConfig = releaseSigningPropertiesFile.isFile &&
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !releaseSigningProperties.getProperty(it).isNullOrBlank() }
val defaultSdkDir = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
        "${System.getProperty("user.home")}/Library/Android/sdk"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
        "${System.getProperty("user.home")}/AppData/Local/Android/Sdk"
    else -> "${System.getProperty("user.home")}/Android/Sdk"
}
val androidSdkDir = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: localProperties.getProperty("sdk.dir")
    ?: defaultSdkDir
val ndkHomeVal  = "$androidSdkDir/ndk/$ndkVersion"
val rustDirPath = rootProject.file("rust").absolutePath
val jniLibsDir     = layout.buildDirectory.dir("rustLibs")
// Resolve once at configuration time — safe because buildDirectory is stable and never a Transform
val jniLibsDirFile = jniLibsDir.get().asFile

/**
 * Configuration-cache-compatible Rust build task.
 * All inputs are declared as typed Gradle properties so they can be serialised
 * by the configuration cache without capturing script-object references.
 */
abstract class BuildRustTask : DefaultTask() {
    @get:Input    abstract val rustDir:      Property<String>
    @get:Input    abstract val ndkHome:      Property<String>
    @get:Input    abstract val releaseMode:  Property<Boolean>
    @get:Input    abstract val rustTargets:  MapProperty<String, String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun build() {
        val isWin    = System.getProperty("os.name").lowercase().contains("win")
        val cargoExe = if (isWin) "cargo.exe" else "cargo"
        val profile  = if (releaseMode.get()) "release" else "debug"
        val outRoot  = outputDir.get().asFile

        rustTargets.get().forEach { (abi, target) ->
            val outDir  = File(outRoot, abi).also { it.mkdirs() }
            val logFile = File(temporaryDir, "$abi-cargo.log")
            val args    = buildList {
                add(cargoExe); add("ndk"); add("-t"); add(abi); add("build")
                if (releaseMode.get()) add("--release")
            }
            val process = ProcessBuilder(args)
                .directory(File(rustDir.get()))
                .also { pb ->
                    pb.environment()["ANDROID_NDK_HOME"] = ndkHome.get()
                    pb.redirectErrorStream(true)
                    pb.redirectOutput(logFile)
                }
                .start()

            val exitCode = process.waitFor()
            val output   = if (logFile.exists()) logFile.readText() else ""
            if (output.isNotBlank()) logger.lifecycle(output)
            if (exitCode != 0) throw GradleException("cargo ndk build failed for $abi (exit $exitCode)")

            val soSrc = File(rustDir.get(), "target/$target/$profile/libliveupdate_core.so")
            if (soSrc.exists()) {
                soSrc.copyTo(File(outDir, "libliveupdate_core.so"), overwrite = true)
            } else {
                throw GradleException("Rust build produced no .so at: $soSrc")
            }
        }
    }
}

fun registerBuildRustTask(taskName: String, release: Boolean) =
    tasks.register<BuildRustTask>(taskName) {
        group       = "build"
        description = "Compile Rust library (${if (release) "release" else "debug"}) for all Android ABIs via cargo-ndk"
        rustDir.set(rustDirPath)
        ndkHome.set(ndkHomeVal)
        releaseMode.set(release)
        rustTargets.set(rustTargetMap)
        outputDir.set(jniLibsDir)
        inputs.files(
            fileTree(rustDirPath) {
                include("Cargo.toml", "Cargo.lock", "src/**")
            }
        )
        outputs.dir(jniLibsDirFile)
    }

val buildRustDebug   = registerBuildRustTask("buildRustDebug",   release = false)
val buildRustRelease = registerBuildRustTask("buildRustRelease", release = true)

android {
    namespace = "com.jizizr.signaldock"
    ndkVersion = "27.2.12479018"
    // miuix 0.9.x AAR 元数据要求消费方 compileSdk ≥ 37；targetSdk/minSdk 维持 36 不变
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jizizr.signaldock"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only build ABIs we have Rust targets for
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Register Rust output directory — use a resolved string path (not a Provider) to satisfy
    // AGP's restriction against adding Provider instances to the SourceSet API.
    sourceSets["main"].jniLibs.directories.add(jniLibsDirFile.path)

    val localReleaseSigning = if (hasReleaseSigningConfig) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
            storePassword = releaseSigningProperties.getProperty("storePassword")
            keyAlias = releaseSigningProperties.getProperty("keyAlias")
            keyPassword = releaseSigningProperties.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    } else null

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = localReleaseSigning
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "DebugProbesKt.bin"
            )
        }
    }
}

// Make the Rust build run before the JNI libs are merged
afterEvaluate {
    tasks.named("mergeDebugJniLibFolders")   { dependsOn(buildRustDebug) }
    tasks.named("mergeReleaseJniLibFolders") { dependsOn(buildRustRelease) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    // Miuix — HyperOS-style Compose UI (theme, components, preferences, icons)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    // LSPosed HiddenApiBypass — bypasses Android hidden API restrictions (incl. MIUI api=blocked)
    implementation(libs.hidden.api.bypass)
    // Shizuku — run privileged shell to auto-grant accessibility
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
