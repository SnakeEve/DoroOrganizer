plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.api)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("verificationGates") {
            id = "doro.verification-gates"
            implementationClass = "com.doro.organizer.gates.VerificationGatesPlugin"
        }
    }
}

tasks.test {
    // The tests read the real config/ lists, compare the banned list with docs/, and run config/check-released-schemas.sh.
    val configDir = layout.projectDirectory.dir("../config")
    val docsDir = layout.projectDirectory.dir("../docs")
    inputs.dir(configDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(docsDir.file("security.md"), docsDir.file("testing.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("doro.configDir", configDir.asFile.absolutePath)
    systemProperty("doro.docsDir", docsDir.asFile.absolutePath)
}
