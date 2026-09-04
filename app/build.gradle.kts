import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

val androidAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val appVersionCode = providers.gradleProperty("WHITEAESTHER_VERSION_CODE").orElse("1").map { it.toInt() }

/**
 * What the build calls itself when the release workflow has not said.
 *
 * Only that workflow passes WHITEAESTHER_VERSION_NAME, so every other build --
 * local, CI, and every preview APK handed to someone to test -- used to claim
 * 0.1.0, a version that has never been released. A tester reporting against it
 * was reporting against a number nobody could match to a build, and the same
 * string goes into the diagnostics report.
 *
 * `git describe` answers with the last tag plus the distance from it, so a
 * build off main reads 1.2.1-5-g53ea192 and says exactly what it is. Outside a
 * checkout there is no answer to give, and 0.0.0-unknown is at least not a
 * claim to be a release.
 */
val describedVersion = providers.of(GitDescribeSource::class) {}.orElse("0.0.0-unknown")
val appVersionName = providers.gradleProperty("WHITEAESTHER_VERSION_NAME").orElse(describedVersion)
val abiSplitsEnabled = providers.gradleProperty("WHITEAESTHER_DISABLE_ABI_SPLITS")
    .map { !it.toBoolean() }
    .orElse(true)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.whitedns.whiteaesther"
    // 37 because tor-android requires it, and only for compiling:
    // targetSdk stays at 36 deliberately. Raising that is a behaviour
    // change for every permission and background rule in the app, and it
    // is not something to carry in on the back of a dependency bump.
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.whitedns.whiteaesther"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += androidAbis
        }
    }

    splits {
        abi {
            isEnable = abiSplitsEnabled.get()
            reset()
            include(*androidAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
        }
        create("preview") {
            dimension = "channel"
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
    }

    val releaseStore = providers.gradleProperty("WHITEAESTHER_KEYSTORE_PATH").orNull
    val releaseStorePassword = providers.gradleProperty("WHITEAESTHER_KEYSTORE_PASSWORD").orNull
    val releaseAlias = providers.gradleProperty("WHITEAESTHER_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.gradleProperty("WHITEAESTHER_KEY_PASSWORD").orNull
    if (listOf(releaseStore, releaseStorePassword, releaseAlias, releaseKeyPassword).all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Compressed in the APK, and extracted at install.
        //
        // AGP's default since minSdk 23 is the opposite: store every .so
        // uncompressed and page-aligned so the loader can map it straight out
        // of the APK. That trade is right for an app people download from a
        // store over wifi, and wrong for this one. Three native engines --
        // mihomo, Psiphon and Aether -- come to about 82MB, and stored
        // uncompressed that is the download. They deflate better than 3:1.
        //
        // What it costs is disk on the device, because the extracted copies sit
        // beside the APK that still holds them, and a few seconds at install.
        // What it buys is the download, over a mobile connection, in a country
        // where that is metered and slow, for an app that is often fetched
        // again after every block. That is not a close call.
        jniLibs.useLegacyPackaging = true
        jniLibs.excludes += setOf(
            "**/libboringtun-*.so",
            "**/libquiche.so",
        )
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        getByName("debug").jniLibs.directories.add(
            layout.buildDirectory.dir("generated/rustJniLibs/debug").get().asFile.absolutePath,
        )
        getByName("release").jniLibs.directories.add(
            layout.buildDirectory.dir("generated/rustJniLibs/release").get().asFile.absolutePath,
        )
        // The exit chain's Go library, built separately by native/chain/build.ps1.
        // Not produced by this build: it needs the Go toolchain and takes
        // minutes. A missing directory is not an error -- the app loads the
        // chain at run time and reports it unavailable when it is absent, which
        // is what a build without it should do.
        getByName("main").jniLibs.srcDir(rootProject.file("native/chain/build"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Psiphon, as its own carrier. Pinned: this is a 44MB binary from outside
    // the usual supply chain, and "whatever is newest" is not a thing to be
    // relaxed about there. It runs in its own process -- see PsiphonService --
    // because it is Go, and the exit chain is already the one Go runtime this
    // process is allowed to have.
    implementation("ca.psiphon:psiphontunnel:2.0.41")

    // Tor, as a third carrier. Guardian Project's build, which is Orbot's,
    // rather than a cross-compile of our own: tor needs openssl, libevent and
    // zlib built for every ABI, and that is a job somebody already does
    // properly. It is C, and runs in ":tor" for isolation rather than because
    // it has to.
    //
    // Its pluggable transports are deliberately not here. IPtProxy is the
    // obvious dependency and it cannot be used: it is a gomobile library, so it
    // ships `libgojni.so` and the `go.*` support classes -- and so does
    // Psiphon. Two of them in one APK is a duplicate-class failure at build
    // time and, worse, one `libgojni.so` silently winning over the other at
    // packaging time. The transports come as their own executables instead, in
    // native/tor, which is also how tor expects to launch them.
    implementation("info.guardianproject:tor-android:0.4.9.11")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // android.jar ships org.json as stubs that throw, so anything that touches
    // JSON is untestable on the JVM without a real one. This is the same
    // implementation Android itself uses.
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.11.4")
}

fun registerCargoNdkTask(name: String, release: Boolean) = tasks.register<Exec>(name) {
    val outputVariant = if (release) "release" else "debug"
    val outputDir = layout.buildDirectory.dir("generated/rustJniLibs/$outputVariant")
    val androidHome = providers.environmentVariable("ANDROID_HOME")
    val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(androidHome.map { "$it/ndk/29.0.14206865" })
        .map { it.replace('\\', '/') }
    val cmakeBin = androidHome.map { "$it/cmake/3.22.1/bin".replace('\\', '/') }
    val clangBin = ndkHome.map {
        "$it/toolchains/llvm/prebuilt/windows-x86_64/bin"
    }
    val windowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    workingDir(rootProject.file("native/android-bridge"))
    environment("ANDROID_NDK_HOME", ndkHome.get())
    environment(
        "CMAKE",
        if (windowsHost) "C:/Program Files/CMake/bin/cmake.exe"
        else cmakeBin.map { "$it/cmake" }.get(),
    )
    environment("CMAKE_GENERATOR", "Ninja")
    environment("CMAKE_MAKE_PROGRAM", cmakeBin.map { "$it/${if (windowsHost) "ninja.exe" else "ninja"}" }.get())
    if (windowsHost) {
        // cargo-ndk normalizes exported NDK paths only for MSYS/Cygwin hosts.
        environment("MSYSTEM", "CARGO_NDK_PATH_NORMALIZATION")
        environment("CC_aarch64-linux-android", clangBin.map { "$it/aarch64-linux-android26-clang.cmd" }.get())
        environment("CXX_aarch64-linux-android", clangBin.map { "$it/aarch64-linux-android26-clang++.cmd" }.get())
        environment("CC_armv7-linux-androideabi", clangBin.map { "$it/armv7a-linux-androideabi26-clang.cmd" }.get())
        environment("CXX_armv7-linux-androideabi", clangBin.map { "$it/armv7a-linux-androideabi26-clang++.cmd" }.get())
        environment("CC_x86_64-linux-android", clangBin.map { "$it/x86_64-linux-android26-clang.cmd" }.get())
        environment("CXX_x86_64-linux-android", clangBin.map { "$it/x86_64-linux-android26-clang++.cmd" }.get())
    }
    commandLine(
        buildList {
            addAll(
                listOf(
                    "cargo",
                    "ndk",
                    "--platform",
                    "26",
                    *androidAbis.flatMap { listOf("-t", it) }.toTypedArray(),
                    "-o",
                    outputDir.get().asFile.absolutePath,
                    "build",
                    "--locked",
                ),
            )
            if (release) add("--release")
        },
    )
    inputs.files(
        rootProject.fileTree("native/android-bridge/src"),
        rootProject.file("native/android-bridge/Cargo.toml"),
        rootProject.file("native/android-bridge/Cargo.lock"),
        rootProject.file("native/rust-toolchain.toml"),
        rootProject.fileTree("native/aether") {
            exclude("**/target/**", "**/.git/**")
        },
        rootProject.fileTree("native/third-party/boring-sys") {
            exclude("**/target/**", "**/.git/**")
        },
    )
    inputs.property("androidAbis", androidAbis)
    outputs.dir(outputDir)
}

val cargoBuildAndroidDebug = registerCargoNdkTask("cargoBuildAndroidDebug", release = false)
val cargoBuildAndroidRelease = registerCargoNdkTask("cargoBuildAndroidRelease", release = true)

tasks.matching { it.name.matches(Regex("merge(?:Preview|Stable)DebugJniLibFolders")) }
    .configureEach { dependsOn(cargoBuildAndroidDebug) }
tasks.matching { it.name.matches(Regex("merge(?:Preview|Stable)ReleaseJniLibFolders")) }
    .configureEach { dependsOn(cargoBuildAndroidRelease) }


/**
 * Reads the version out of git, without breaking a build that has no git.
 *
 * A ValueSource rather than a plain exec so the configuration cache stays
 * usable: Gradle tracks this as an input instead of refusing to cache a build
 * that shelled out during configuration.
 */
abstract class GitDescribeSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String? {
        val stdout = ByteArrayOutputStream()
        val result = runCatching {
            execOperations.exec {
                // No --dirty: an uncommitted tree is worth knowing about, but the
                // version name is read by users in a footer, not by whoever
                // built it, and "1.2.1-6-gabc1234-dirty" reads as a fault.
                commandLine("git", "describe", "--tags", "--always")
                standardOutput = stdout
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        }.getOrNull() ?: return null
        if (result.exitValue != 0) return null
        // The tag is written v1.2.1; the version name is not.
        return stdout.toString(Charsets.UTF_8).trim().removePrefix("v").ifEmpty { null }
    }
}
