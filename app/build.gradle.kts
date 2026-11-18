import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.application) // Kotlin is built into AGP 9: no org.jetbrains.kotlin.android (DEC-02)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") // on the root buildscript classpath: applied by id, without a version
    alias(libs.plugins.room)
    id("doro.verification-gates") // build-logic: local verification gates (testing.md § 9)
}

// testing.md § 9: -PtestBuildType=qa|benchmark selects the build type the instrumented tests run against. With the
// Orchestrator's clearPackageData, only the suffixed ids may be targeted, never the owner's release app (DEC-15, V36).
val testType = providers.gradleProperty("testBuildType").getOrElse("debug").also {
    require(it in setOf("debug", "qa", "benchmark")) { "testBuildType must be debug, qa or benchmark (DEC-15)" }
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
        // DEC-01: explicit, AGP 9 otherwise defaults it to compileSdk. The override exists only for the optional
        // target36-canary job (testing.md § 9).
        targetSdk = providers.gradleProperty("targetSdkOverride").map(String::toInt).getOrElse(35)
        versionCode = 1
        versionName = "0.1.0"
        // qa runs black-box @ReleaseSmoke tests against R8-renamed classes, so it cannot load DoroTestRunner's app.
        testInstrumentationRunner = if (testType == "qa") {
            "androidx.test.runner.AndroidJUnitRunner"
        } else {
            "com.doro.organizer.testing.DoroTestRunner"
        }
        testInstrumentationRunnerArguments["clearPackageData"] = "true" // with the Orchestrator: pm clear after each test
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
        // Fakes without Android calls, shared by JVM and instrumented tests (DEC-12). AGP 9 built-in Kotlin compiles
        // .kt files only from kotlin directories, not from java.srcDir (architecture.md § AGP 9 build configuration).
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
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

    testBuildType = testType
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR" // hermetic instrumented tests (testing.md § 1)
        animationsDisabled = true
        unitTests.all {
            it.systemProperty("doro.srcRoot", file("src").absolutePath)
            // ArchitectureTest (I-41), MigrationCoverageTest and SchemaJsonTest (I-47, I-48) read these files, not
            // classes: declared as inputs, a change that alters no bytecode still reruns them.
            it.inputs.files(fileTree("src") { include("main/**", "debug/**", "release/**", "androidTest/**") })
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .withPropertyName("doroScannedSources")
            it.inputs.files(fileTree("schemas"))
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .withPropertyName("doroSchemas")
        }
        managedDevices { // optional emulator jobs only; no gate depends on them (DEC-15)
            allDevices {
                create<ManagedVirtualDevice>("api28") {
                    device = "Pixel 2"; apiLevel = 28; systemImageSource = "aosp"; require64Bit = true
                }
                create<ManagedVirtualDevice>("api35") { device = "Pixel 6"; apiLevel = 35; systemImageSource = "aosp-atd" }
                create<ManagedVirtualDevice>("api35ps16k") {
                    device = "Pixel 6"; apiLevel = 35; systemImageSource = "google"
                    pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_16KB_PAGES
                }
                create<ManagedVirtualDevice>("api36") { device = "Pixel 6"; apiLevel = 36; systemImageSource = "aosp-atd" }
            }
            groups { create("ci") { targetDevices.addAll(listOf(allDevices["api28"], allDevices["api35"])) } }
        }
    }

    lint {
        error += "NewApi" // DEC-14
        abortOnError = true
    }
}

// The exported schema JSON of every version is committed (DEC-07, DEC-14).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment) // DEC-04: declared explicitly, biometric 1.1.0 alone pulls fragment 1.2.5
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.sqlcipher.android) // DEC-08: the only SQLite engine
    implementation(libs.androidx.room.runtime) // DEC-07: on SQLCipher through SupportOpenHelperFactory
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite) // SupportSQLiteDatabase, a supertype of the SQLCipher classes; SQLCipher declares it runtime-only
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.process) // ProcessLifecycleOwner: AppTime refresh (architecture.md § Time)

    testImplementation(libs.junit)
    testImplementation(libs.org.json) // Android's org.json is a stub on the JVM
    testImplementation(libs.kotlinx.coroutines.test) // virtual time for DefaultAppTime (testing.md I-30)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test) // virtual time for the AppTime wiring
    androidTestUtil(libs.androidx.test.orchestrator)
}

// The gates are only as strong as their rules, so the aggregate also runs the build-logic unit tests that pin them.
tasks.named("verificationGates") {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
