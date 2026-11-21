plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.api)
    testImplementation(libs.junit)
    // SubtypeRuleR8Test runs the R8 of AGP's builder artifact, which bundles it, as the keep-rule generator does (DEC-103 (5)).
    testImplementation(libs.android.builder) { isTransitive = false }
}

gradlePlugin {
    plugins {
        register("verificationGates") {
            id = "doro.verification-gates"
            implementationClass = "com.doro.organizer.gates.VerificationGatesPlugin"
        }
        register("testApkKeepRules") {
            id = "doro.test-apk-keep-rules"
            implementationClass = "com.doro.organizer.testapk.TestApkKeepRulesPlugin"
        }
    }
}

tasks.test {
    // The tests read the real config/ lists, compare the banned list with docs/, run config/check-released-schemas.sh,
    // and read the app's own R8 configuration files that tell qa (obfuscated) from benchmark (-dontobfuscate).
    val configDir = layout.projectDirectory.dir("../config")
    val docsDir = layout.projectDirectory.dir("../docs")
    val appDir = layout.projectDirectory.dir("../app")
    inputs.dir(configDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(docsDir.file("security.md"), docsDir.file("testing.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(appDir.file("proguard-rules.pro"), appDir.file("proguard-benchmark.pro")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("doro.configDir", configDir.asFile.absolutePath)
    systemProperty("doro.docsDir", docsDir.asFile.absolutePath)
    systemProperty("doro.appDir", appDir.asFile.absolutePath)
}
