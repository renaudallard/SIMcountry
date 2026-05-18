/*
 * Copyright (c) 2026 Renaud Allard <renaud@allard.it>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGES.
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "it.allard.simcountry"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "it.allard.simcountry"
        minSdk = 33
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val path = providers.gradleProperty("SIMCOUNTRY_KEYSTORE_PATH").orNull
            val keystoreFile = path?.let { file(it) }
            if (keystoreFile != null && keystoreFile.isFile) {
                storeFile = keystoreFile
                storePassword = providers.gradleProperty("SIMCOUNTRY_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("SIMCOUNTRY_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("SIMCOUNTRY_KEY_PASSWORD").orNull
                enableV2Signing = true
                enableV3Signing = true
            } else if (path != null) {
                logger.warn("SIMCOUNTRY_KEYSTORE_PATH=$path does not exist; release will be unsigned.")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/versions/**/OSGI-INF/MANIFEST.MF",
            )
        }
        // The native daemon (libsimcountryd.so) is an ELF executable, not a
        // shared library. It must be extracted to nativeLibraryDir as a real
        // file on disk so adb shell can execve it; legacy packaging stores
        // it uncompressed in the APK and triggers the install-time extract.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "SIMcountry-${variant.versionName}.apk"
        }
    }
}

// Builds the Rust daemon (daemon-native/) for arm64-v8a and stages it under
// jniLibs/ so AGP packages it as lib/arm64-v8a/libsimcountryd.so. The .so
// suffix is required: Android only extracts files matching that pattern from
// the APK into the on-device nativeLibraryDir, which is where adb shell will
// need to execve from.
val buildDaemonNative by tasks.registering(Exec::class) {
    val nativeDir = rootProject.projectDir.resolve("daemon-native")
    val targetTriple = "aarch64-linux-android"
    val apiLevel = android.defaultConfig.minSdk ?: 33
    val cargoOut = nativeDir.resolve("target/$targetTriple/release/simcountryd")
    val stagedSo = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libsimcountryd.so").asFile

    workingDir = nativeDir
    inputs.dir(nativeDir.resolve("src"))
    inputs.file(nativeDir.resolve("Cargo.toml"))
    inputs.property("ndkVersion", android.ndkVersion)
    inputs.property("apiLevel", apiLevel)
    outputs.file(stagedSo)

    val hostTag = when {
        org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux-x86_64"
        org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin-x86_64"
        org.gradle.internal.os.OperatingSystem.current().isWindows -> "windows-x86_64"
        else -> error("unsupported host OS for NDK toolchain lookup")
    }
    val ndkRoot = android.sdkDirectory.resolve("ndk/${android.ndkVersion}")
    val toolchainBin = ndkRoot.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
    val linker = toolchainBin.resolve("$targetTriple$apiLevel-clang")
    val envSuffix = targetTriple.replace('-', '_')

    commandLine("cargo", "build", "--release", "--target", targetTriple)
    environment("CARGO_TARGET_${envSuffix.uppercase()}_LINKER", linker.absolutePath)
    environment("CC_$envSuffix", linker.absolutePath)
    environment("AR_$envSuffix", toolchainBin.resolve("llvm-ar").absolutePath)

    doFirst {
        check(ndkRoot.isDirectory) {
            "NDK ${android.ndkVersion} not found at $ndkRoot. Install via `sdkmanager 'ndk;${android.ndkVersion}'`."
        }
        check(linker.isFile) { "expected NDK linker at $linker but it does not exist" }
    }

    doLast {
        check(cargoOut.isFile) { "cargo produced no binary at $cargoOut" }
        stagedSo.parentFile.mkdirs()
        cargoOut.copyTo(stagedSo, overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        afterEvaluate {
            val mergeTask = tasks.findByName("merge${variant.name.replaceFirstChar { it.uppercase() }}JniLibFolders")
            mergeTask?.dependsOn(buildDaemonNative)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildDaemonNative)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.conscrypt.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
