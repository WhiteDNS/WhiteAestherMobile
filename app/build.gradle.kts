import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec

val androidAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val appVersionCode = providers.gradleProperty("WHITEAESTHER_VERSION_CODE").orElse("1").map { it.toInt() }
val appVersionName = providers.gradleProperty("WHITEAESTHER_VERSION_NAME").orElse("0.1.0")
val abiSplitsEnabled = providers.gradleProperty("WHITEAESTHER_DISABLE_ABI_SPLITS")
    .map { !it.toBoolean() }
    .orElse(true)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.whitedns.whiteaesther"
    compileSdk = 36
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
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.11.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
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
