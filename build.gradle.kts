// AGP 9 compiles Kotlin itself and bundles an older KGP; the classpath raises KGP and KSP to the pinned versions (DEC-02).
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
