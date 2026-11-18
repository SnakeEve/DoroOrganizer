plugins {
    alias(libs.plugins.android.application) // Kotlin is built into AGP 9: no org.jetbrains.kotlin.android (DEC-02)
    alias(libs.plugins.kotlin.compose)
    id("doro.verification-gates") // build-logic: local verification gates (testing.md § 9)
}

android {
    namespace = "com.doro.organizer"
    // DEC-01: API 37. The installed platform is the 37.2 minor release.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.doro.organizer" // DEC-03
        minSdk = 28
        targetSdk = 35 // DEC-01: explicit, AGP 9 otherwise defaults it to compileSdk
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // DEC-15: every build type that runs instrumented tests has its own id, so tests that clear app data and
        // Keystore entries never touch the owner's release app (com.doro.organizer).
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") // DEC-13
        }
        create("qa") { // DEC-12: release configuration with R8, debug-signed, for release smoke tests
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".qa"
        }
        create("benchmark") { // DEC-12: release-like build for the performance tests
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            proguardFiles("proguard-benchmark.pro")
        }
    }

    sourceSets {
        // initWith copies build-type properties, not source sets: qa and benchmark use the release implementations.
        // Only Kotlin files are shared, so src/release holds nothing but .kt files under src/release/java.
        getByName("qa").kotlin.directories.add("src/release/java")
        getByName("benchmark").kotlin.directories.add("src/release/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        error += "NewApi" // DEC-14
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment) // DEC-04: declared explicitly, biometric 1.1.0 alone pulls fragment 1.2.5
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.sqlcipher.android) // DEC-08: the only SQLite engine
    implementation(libs.androidx.sqlite) // SupportSQLiteDatabase, a supertype of the SQLCipher classes; SQLCipher declares it runtime-only

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// The gates are only as strong as their rules, so the aggregate also runs the build-logic unit tests that pin them.
tasks.named("verificationGates") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
