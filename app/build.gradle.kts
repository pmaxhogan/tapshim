plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
}

// Version scheme: VERSION file holds "major.minor"; patch is the number of
// commits on the current branch, so every push to main is a new, monotonic
// release. CI passes TAPSHIM_PATCH explicitly; local builds derive it from git.
val baseVersion = rootProject.file("VERSION").readText().trim()
val patch: Int = (System.getenv("TAPSHIM_PATCH")
    ?: providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "0" }).toInt()
val (major, minor) = baseVersion.split(".").map { it.toInt() }
val computedVersionName = "$major.$minor.$patch"
val computedVersionCode = major * 1_000_000 + minor * 10_000 + patch

android {
    namespace = "dev.maxhogan.tapshim"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "dev.maxhogan.tapshim"
        minSdk = 31
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = computedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("TAPSHIM_KEYSTORE_PATH")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("TAPSHIM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TAPSHIM_KEY_ALIAS")
                keyPassword = System.getenv("TAPSHIM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("TAPSHIM_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Coverage gate. The UI, the Bluetooth service, and the debug-only receiver need
// a device, so they are excluded; everything else must stay well covered.
kover {
    reports {
        filters {
            excludes {
                androidGeneratedClasses()
                packages("dev.maxhogan.tapshim.ui", "dev.maxhogan.tapshim.debug")
                classes(
                    "dev.maxhogan.tapshim.TapShimApp",
                    "dev.maxhogan.tapshim.bluetooth.TapSocketService*",
                    "*ComposableSingletons*",
                )
            }
        }
        variant("debug") {
            verify {
                rule("overall line coverage") {
                    minBound(85)
                }
                rule("every package at least 75% lines") {
                    groupBy = kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.PACKAGE
                    minBound(75)
                }
            }
        }
    }
}
