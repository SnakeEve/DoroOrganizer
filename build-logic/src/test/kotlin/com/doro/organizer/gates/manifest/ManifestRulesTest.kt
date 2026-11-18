package com.doro.organizer.gates.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestRulesTest {
    private val allowlist = ManifestRules.permissionAllowlist(
        """
        # comment
        android.permission.USE_BIOMETRIC
        android.permission.HIDE_OVERLAY_WINDOWS
        $ALLOWLISTED_DYNAMIC_RECEIVER
        """.trimIndent(),
        RELEASE_ID,
    )

    private fun violations(manifest: String = VALID_MANIFEST) = ManifestRules.releaseViolations(parseXml(manifest), allowlist, RELEASE_ID)

    private fun assertSingleViolation(manifest: String, expectedFragment: String) {
        val found = violations(manifest)
        assertEquals("violations: $found", 1, found.size)
        assertTrue("'${found.single()}' should mention '$expectedFragment'", found.single().contains(expectedFragment))
    }

    @Test
    fun validReleaseManifest_hasNoViolations() {
        assertEquals(emptyList<String>(), violations())
    }

    @Test
    fun permissionAllowlist_substitutesTheSuppliedReleaseApplicationId() {
        assertTrue("com.doro.organizer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" in allowlist)
        assertEquals(3, allowlist.size)
        assertEquals(
            setOf("com.example.wrong.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            ManifestRules.permissionAllowlist(ALLOWLISTED_DYNAMIC_RECEIVER, "com.example.wrong"),
        )
    }

    @Test
    fun permissionOutsideAllowlist_fails() {
        assertSingleViolation(
            VALID_MANIFEST.replace(PERMISSIONS, "$PERMISSIONS<uses-permission android:name=\"android.permission.CAMERA\" />"),
            "android.permission.CAMERA",
        )
    }

    @Test
    fun networkPermissions_failEvenWhenAllowlisted() {
        val network = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE")
        val manifest = VALID_MANIFEST.replace(
            PERMISSIONS,
            PERMISSIONS + network.joinToString("") { "<uses-permission-sdk-23 android:name=\"$it\" />" },
        )
        val found = ManifestRules.releaseViolations(parseXml(manifest), allowlist + network, RELEASE_ID)
        assertEquals(network.map { "<uses-permission-sdk-23 $it>: network permissions are banned (DEC-68)" }, found)
    }

    @Test
    fun declaredPermissionOutsideAllowlist_fails() {
        assertSingleViolation(
            VALID_MANIFEST.replace(PERMISSIONS, "$PERMISSIONS<permission android:name=\"com.example.OTHER\" />"),
            "<permission com.example.OTHER>",
        )
    }

    @Test
    fun wrongPackageAndSdkLevels_fail() {
        val found = violations(
            VALID_MANIFEST.replace("package=\"com.doro.organizer\"", "package=\"com.doro.organizer.debug\"")
                .replace("minSdkVersion=\"28\"", "minSdkVersion=\"26\"")
                .replace("targetSdkVersion=\"35\"", "targetSdkVersion=\"37\""),
        )
        assertEquals(
            listOf(
                "release package is 'com.doro.organizer.debug', expected com.doro.organizer",
                "minSdkVersion is not 28",
                "targetSdkVersion is not 35",
            ),
            found,
        )
    }

    @Test
    fun applicationAttributes_eachFail() {
        val cases = mapOf(
            "android:allowBackup=\"false\"" to ("android:allowBackup=\"true\"" to "allowBackup"),
            "android:taskAffinity=\"\"" to ("android:taskAffinity=\"com.other\"" to "taskAffinity"),
            "android:enableOnBackInvokedCallback=\"true\"" to ("" to "enableOnBackInvokedCallback"),
            "android:fullBackupContent=\"@xml/backup_rules\"" to ("" to "fullBackupContent"),
            "android:dataExtractionRules=\"@xml/data_extraction_rules\"" to ("" to "dataExtractionRules"),
            "android:extractNativeLibs=\"false\"" to ("android:extractNativeLibs=\"true\"" to "extractNativeLibs"),
        )
        cases.forEach { (original, replacement) ->
            assertSingleViolation(VALID_MANIFEST.replace(original, replacement.first), replacement.second)
        }
    }

    @Test
    fun debuggableOrTestOnlyRelease_fails() {
        assertSingleViolation(VALID_MANIFEST.replace(APPLICATION_OPEN, "$APPLICATION_OPEN android:debuggable=\"true\""), "debuggable")
        assertSingleViolation(VALID_MANIFEST.replace(APPLICATION_OPEN, "$APPLICATION_OPEN android:testOnly=\"true\""), "testOnly")
    }

    @Test
    fun exportedProvider_fails() {
        assertSingleViolation(
            VALID_MANIFEST.replace(
                "android:authorities=\"com.doro.organizer.androidx-startup\" android:exported=\"false\"",
                "android:authorities=\"com.doro.organizer.androidx-startup\" android:exported=\"true\"",
            ),
            "<provider androidx.startup.InitializationProvider> is exported",
        )
    }

    @Test
    fun activityWithIntentFilterAndNoExportedAttribute_countsAsExported() {
        val found = violations(VALID_MANIFEST.replace(COMPONENTS_END, "$OTHER_ACTIVITY_WITH_FILTER$COMPONENTS_END"))
        assertEquals(
            listOf("<activity com.doro.organizer.Other> is exported (DEC-68)", "<activity com.doro.organizer.Other> has an intent filter"),
            found,
        )
    }

    @Test
    fun profileInstallReceiverWithoutDumpPermission_fails() {
        assertSingleViolation(
            VALID_MANIFEST.replace("android:permission=\"android.permission.DUMP\"", ""),
            "exported without android.permission.DUMP",
        )
    }

    @Test
    fun otherReceiverOrService_fails() {
        assertSingleViolation(
            VALID_MANIFEST.replace(COMPONENTS_END, "<receiver android:name=\"com.example.R\" android:exported=\"false\" />$COMPONENTS_END"),
            "no receiver other than ProfileInstallReceiver",
        )
        assertSingleViolation(
            VALID_MANIFEST.replace(COMPONENTS_END, "<service android:name=\"com.example.S\" android:exported=\"false\" />$COMPONENTS_END"),
            "services are not allowed",
        )
    }

    @Test
    fun mainActivityWithExtraIntentFilter_fails() {
        val extra = "<intent-filter><action android:name=\"android.intent.action.SEND\" />" +
            "<category android:name=\"android.intent.category.DEFAULT\" /></intent-filter>"
        assertSingleViolation(VALID_MANIFEST.replace(LAUNCHER_FILTER, LAUNCHER_FILTER + extra), "other than MAIN/LAUNCHER")
    }

    @Test
    fun launcherFilterWithData_fails() {
        assertSingleViolation(
            VALID_MANIFEST.replace(
                "<category android:name=\"android.intent.category.LAUNCHER\" />",
                "<category android:name=\"android.intent.category.LAUNCHER\" /><data android:scheme=\"content\" />",
            ),
            "other than MAIN/LAUNCHER",
        )
    }

    @Test
    fun fileProviderWithoutGrantUriPermissions_fails() {
        assertSingleViolation(VALID_MANIFEST.replace("android:grantUriPermissions=\"true\"", ""), "grantUriPermissions")
    }

    @Test
    fun fileProviderOtherThanBackupFileProvider_fails() {
        assertSingleViolation(
            VALID_MANIFEST.replace("com.doro.organizer.BackupFileProvider", "androidx.core.content.FileProvider"),
            "the only FileProvider allowed is com.doro.organizer.BackupFileProvider",
        )
    }

    @Test
    fun fileProviderAuthorityFollowsTheReleaseApplicationId() {
        assertSingleViolation(
            VALID_MANIFEST.replace("com.doro.organizer.fileprovider", "com.doro.organizer.share"),
            "authorities is not com.doro.organizer.fileprovider",
        )
        val otherId = ManifestRules.releaseViolations(parseXml(VALID_MANIFEST), allowlist, "com.example.wrong")
        assertTrue(otherId.toString(), otherId.any { it.contains("authorities is not com.example.wrong.fileprovider") })
    }

    @Test
    fun fileProviderPathsResourceOtherThanFilePaths_fails() {
        assertSingleViolation(VALID_MANIFEST.replace("@xml/file_paths", "@xml/other_paths"), "FILE_PROVIDER_PATHS is not @xml/file_paths")
    }

    @Test
    fun referencesFilePaths_onlyWithAFileProviderPathsMetaData() {
        assertTrue(ManifestRules.referencesFilePaths(parseXml(VALID_MANIFEST)))
        val withoutFileProvider = VALID_MANIFEST.replace("android.support.FILE_PROVIDER_PATHS", "com.example.OTHER")
        assertFalse(ManifestRules.referencesFilePaths(parseXml(withoutFileProvider)))
    }

    @Test
    fun directBootAwareScreenOrientationAndProcess_fail() {
        assertSingleViolation(
            VALID_MANIFEST.replace("android:directBootAware=\"false\"", "android:directBootAware=\"true\""),
            "directBootAware",
        )
        assertSingleViolation(
            VALID_MANIFEST.replace(MAIN_ACTIVITY_OPEN, "$MAIN_ACTIVITY_OPEN android:screenOrientation=\"portrait\""),
            "screenOrientation",
        )
        assertSingleViolation(
            VALID_MANIFEST.replace(MAIN_ACTIVITY_OPEN, "$MAIN_ACTIVITY_OPEN android:process=\":crashtest\""),
            "process ':crashtest'",
        )
    }

    @Test
    fun applicationIds_followTheBuildTypeSuffixRule() {
        val expected = mapOf(
            "release" to "com.doro.organizer",
            "debug" to "com.doro.organizer.debug",
            "qa" to "com.doro.organizer.qa",
            "benchmark" to "com.doro.organizer.benchmark",
        )
        assertEquals(emptyList<String>(), ManifestRules.applicationIdViolations(expected))
        assertEquals(
            listOf(
                "debug application id is com.doro.organizer, expected com.doro.organizer.debug",
                "release application id is com.doro.organizer.release, expected com.doro.organizer",
            ),
            ManifestRules.applicationIdViolations(
                expected + mapOf("debug" to "com.doro.organizer", "release" to "com.doro.organizer.release"),
            ),
        )
    }

    @Test
    fun missingOrUnexpectedBuildType_fails() {
        val ids = mapOf(
            "release" to "com.doro.organizer",
            "debug" to "com.doro.organizer.debug",
            "qa" to "com.doro.organizer.qa",
            "bench" to "com.doro.organizer.bench",
        )
        assertEquals(
            listOf("build type benchmark is missing (DEC-12, DEC-15)", "build type bench is not one of [release, debug, qa, benchmark] (DEC-12, DEC-15)"),
            ManifestRules.applicationIdViolations(ids),
        )
    }

    @Test
    fun packaging_failsOnCompressedNativeLibraries() {
        val found = ManifestRules.packagingViolations(
            mapOf("lib/arm64-v8a/libsqlcipher.so" to true, "lib/x86/libsqlcipher.so" to false),
        )
        assertEquals(listOf("lib/x86/libsqlcipher.so is compressed; native libraries must be stored"), found)
    }

    private companion object {
        const val RELEASE_ID = "com.doro.organizer"
        const val ALLOWLISTED_DYNAMIC_RECEIVER = "\${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        const val PERMISSIONS = "<uses-permission android:name=\"android.permission.HIDE_OVERLAY_WINDOWS\" />"
        const val APPLICATION_OPEN = "<application android:allowBackup=\"false\""
        const val MAIN_ACTIVITY_OPEN = "<activity android:name=\"com.doro.organizer.MainActivity\""
        const val LAUNCHER_FILTER = "<intent-filter><action android:name=\"android.intent.action.MAIN\" />" +
            "<category android:name=\"android.intent.category.LAUNCHER\" /></intent-filter>"
        const val COMPONENTS_END = "</application>"
        const val OTHER_ACTIVITY_WITH_FILTER = "<activity android:name=\"com.doro.organizer.Other\">" +
            "<intent-filter><action android:name=\"android.intent.action.VIEW\" /></intent-filter></activity>"

        // Shaped like the merged release manifest of AGP 9.5 plus the FileProvider of security.md § 9.1.
        val VALID_MANIFEST = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.doro.organizer">
              <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="35" />
              $PERMISSIONS
              <permission android:name="com.doro.organizer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature" />
              <uses-permission android:name="com.doro.organizer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
              $APPLICATION_OPEN android:dataExtractionRules="@xml/data_extraction_rules"
                  android:enableOnBackInvokedCallback="true" android:extractNativeLibs="false"
                  android:fullBackupContent="@xml/backup_rules" android:taskAffinity="">
                $MAIN_ACTIVITY_OPEN android:exported="true">
                  $LAUNCHER_FILTER
                </activity>
                <provider android:name="com.doro.organizer.BackupFileProvider" android:authorities="com.doro.organizer.fileprovider"
                    android:exported="false" android:grantUriPermissions="true">
                  <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
                </provider>
                <provider android:name="androidx.startup.InitializationProvider"
                    android:authorities="com.doro.organizer.androidx-startup" android:exported="false">
                  <meta-data android:name="androidx.lifecycle.ProcessLifecycleInitializer" android:value="androidx.startup" />
                </provider>
                <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" android:directBootAware="false"
                    android:enabled="true" android:exported="true" android:permission="android.permission.DUMP">
                  <intent-filter><action android:name="androidx.profileinstaller.action.INSTALL_PROFILE" /></intent-filter>
                </receiver>
              $COMPONENTS_END
            </manifest>
        """.trimIndent()
    }
}
