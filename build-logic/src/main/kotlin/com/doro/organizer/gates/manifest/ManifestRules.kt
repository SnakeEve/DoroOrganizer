package com.doro.organizer.gates.manifest

import com.doro.organizer.gates.configEntries
import org.w3c.dom.Element

/** Rules of the `manifest` gate (testing.md § 9, security.md § 9) over merged manifests and the release APK. */
internal object ManifestRules {
    private const val RELEASE_APPLICATION_ID = "com.doro.organizer" // DEC-03
    private const val MIN_SDK = "28" // DEC-01
    private const val TARGET_SDK = "35" // DEC-01: AGP 9 silently defaults targetSdk to compileSdk
    private const val APPLICATION_ID_PLACEHOLDER = "\${applicationId}"
    private const val MAIN_ACTIVITY = "$RELEASE_APPLICATION_ID.MainActivity"
    private const val BACKUP_FILE_PROVIDER = "$RELEASE_APPLICATION_ID.BackupFileProvider" // security.md § 9.1
    private const val PROFILE_INSTALL_RECEIVER = "androidx.profileinstaller.ProfileInstallReceiver"
    private const val DUMP_PERMISSION = "android.permission.DUMP"
    private const val FILE_PROVIDER_PATHS = "android.support.FILE_PROVIDER_PATHS"
    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

    // DEC-68: these fail even if someone adds them to the allowlist.
    private val NETWORK_PERMISSIONS = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
    )

    // DEC-12, DEC-15: a missing or renamed build type would otherwise drop out of the id check unnoticed.
    private val BUILD_TYPES = setOf("release", "debug", "qa", "benchmark")
    private val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23", "permission")
    private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

    /** DEC-15: every build type but release installs under its own id, the release id plus `.<buildType>`. */
    private fun expectedApplicationId(buildType: String): String =
        if (buildType == "release") RELEASE_APPLICATION_ID else "$RELEASE_APPLICATION_ID.$buildType"

    /** [applicationIds] maps every build type of the app to its application id. */
    fun applicationIdViolations(applicationIds: Map<String, String>): List<String> = buildList {
        (BUILD_TYPES - applicationIds.keys).sorted().forEach { add("build type $it is missing (DEC-12, DEC-15)") }
        (applicationIds.keys - BUILD_TYPES).sorted().forEach { add("build type $it is not one of $BUILD_TYPES (DEC-12, DEC-15)") }
        applicationIds.toSortedMap().forEach { (buildType, id) ->
            val expected = expectedApplicationId(buildType)
            if (id != expected) add("$buildType application id is $id, expected $expected")
        }
    }

    /**
     * `config/permission-allowlist.txt` with `${applicationId}` replaced by [releaseApplicationId], the id of the
     * release build being checked (security.md § 9.3).
     */
    fun permissionAllowlist(text: String, releaseApplicationId: String): Set<String> =
        configEntries(text).map { it.replace(APPLICATION_ID_PLACEHOLDER, releaseApplicationId) }.toSet()

    /** Every rule on the merged release manifest of the build whose application id is [releaseApplicationId]. */
    fun releaseViolations(manifest: Element, allowedPermissions: Set<String>, releaseApplicationId: String): List<String> {
        val application = manifest.children("application").singleOrNull()
            ?: return listOf("the release manifest has no single <application>")
        val usesSdk = manifest.children("uses-sdk").singleOrNull()
        return buildList {
            val packageName = manifest.getAttribute("package")
            if (packageName != RELEASE_APPLICATION_ID) add("release package is '$packageName', expected $RELEASE_APPLICATION_ID")
            if (usesSdk?.android("minSdkVersion") != MIN_SDK) add("minSdkVersion is not $MIN_SDK")
            if (usesSdk?.android("targetSdkVersion") != TARGET_SDK) add("targetSdkVersion is not $TARGET_SDK")
            addAll(permissionViolations(manifest, allowedPermissions))
            addAll(applicationViolations(application))
            addAll(componentViolations(application))
            addAll(fileProviderViolations(application, releaseApplicationId))
            addAll(attributeViolations(manifest))
        }
    }

    /** Whether a provider of the merged release manifest names a `FILE_PROVIDER_PATHS` resource. */
    fun referencesFilePaths(manifest: Element): Boolean =
        manifest.children("application").flatMap { it.children("provider") }.any { it.filePathsMetaData().isNotEmpty() }

    /** [libEntries] maps every `lib/` entry of the release APK to whether it is stored uncompressed. */
    fun packagingViolations(libEntries: Map<String, Boolean>): List<String> =
        libEntries.filterValues { stored -> !stored }.keys.sorted().map { "$it is compressed; native libraries must be stored" }

    private fun permissionViolations(manifest: Element, allowed: Set<String>): List<String> =
        PERMISSION_TAGS.flatMap { tag -> manifest.children(tag).mapNotNull { it.android("name") }.map { tag to it } }
            .mapNotNull { (tag, permission) ->
                when {
                    permission in NETWORK_PERMISSIONS -> "<$tag $permission>: network permissions are banned (DEC-68)"
                    permission !in allowed -> "<$tag $permission> is not in config/permission-allowlist.txt"
                    else -> null
                }
            }

    private fun applicationViolations(application: Element): List<String> = buildList {
        if (application.android("debuggable") == "true") add("the release application is debuggable")
        if (application.android("testOnly") == "true") add("the release application is testOnly")
        if (application.android("allowBackup") != "false") add("allowBackup is not false (DEC-69)")
        if (application.android("fullBackupContent") == null) add("fullBackupContent is missing (DEC-69)")
        if (application.android("dataExtractionRules") == null) add("dataExtractionRules is missing (DEC-69)")
        if (application.android("enableOnBackInvokedCallback") != "true") add("enableOnBackInvokedCallback is not true")
        if (application.android("taskAffinity") != "") add("the application taskAffinity is not the empty string")
        if (application.android("extractNativeLibs") == "true") add("extractNativeLibs is true")
    }

    private fun componentViolations(application: Element): List<String> {
        val components = COMPONENT_TAGS.flatMap(application::children)
        return buildList {
            components.filter { it.tagName == "service" }.forEach { add("${it.describe()}: services are not allowed") }
            components.filter { it.tagName == "receiver" && !it.isProfileInstallReceiver() }
                .forEach { add("${it.describe()}: no receiver other than ProfileInstallReceiver is allowed") }
            components.filter { it.isExported() && !it.isMainActivity() }.forEach {
                when {
                    !it.isProfileInstallReceiver() -> add("${it.describe()} is exported (DEC-68)")
                    it.android("permission") != DUMP_PERMISSION -> add("${it.describe()} is exported without $DUMP_PERMISSION")
                }
            }
            // ProfileInstallReceiver keeps its own filters: it is DUMP-protected (security.md § 9.3).
            components.filter { it.children("intent-filter").isNotEmpty() && !it.isProfileInstallReceiver() }.forEach {
                when {
                    !it.isMainActivity() -> add("${it.describe()} has an intent filter")
                    !it.children("intent-filter").all(::isLauncherFilter) ->
                        add("${it.describe()} has an intent filter other than MAIN/LAUNCHER")
                }
            }
        }
    }

    // security.md § 9.1: the backup FileProvider is the only one, with a fixed class, authority and paths resource.
    private fun fileProviderViolations(application: Element, releaseApplicationId: String): List<String> = buildList {
        application.children("provider").filter { it.filePathsMetaData().isNotEmpty() }.forEach { provider ->
            val authority = "$releaseApplicationId.fileprovider"
            if (provider.android("name") != BACKUP_FILE_PROVIDER) {
                add("${provider.describe()}: the only FileProvider allowed is $BACKUP_FILE_PROVIDER")
            }
            if (provider.android("authorities") != authority) add("${provider.describe()}: authorities is not $authority")
            if (provider.android("grantUriPermissions") != "true") add("${provider.describe()}: grantUriPermissions is not true")
            if (provider.filePathsMetaData().any { it.android("resource") != "@xml/${FilePaths.RESOURCE_NAME}" }) {
                add("${provider.describe()}: FILE_PROVIDER_PATHS is not @xml/${FilePaths.RESOURCE_NAME}")
            }
        }
    }

    private fun attributeViolations(manifest: Element): List<String> = buildList {
        manifest.selfAndDescendants().forEach { element ->
            if (element.android("directBootAware") == "true") add("${element.describe()} is directBootAware")
            if (element.android("screenOrientation") != null) add("${element.describe()} sets screenOrientation")
            // The debug-only :crashtest service (testing.md § 5) must never reach a release build.
            element.android("process")?.let { add("${element.describe()} runs in process '$it'") }
        }
    }

    private fun isLauncherFilter(filter: Element): Boolean {
        val entries = filter.children().map { it.tagName to it.android("name") }
        return entries.size == 2 && entries.toSet() == setOf("action" to ACTION_MAIN, "category" to CATEGORY_LAUNCHER)
    }

    // Without android:exported, a component with an intent filter is exported; a provider never is (targetSdk ≥ 17).
    private fun Element.isExported(): Boolean = when (android("exported")) {
        "true" -> true
        null -> tagName != "provider" && children("intent-filter").isNotEmpty()
        else -> false
    }

    private fun Element.filePathsMetaData() = children("meta-data").filter { it.android("name") == FILE_PROVIDER_PATHS }

    private fun Element.isMainActivity() = tagName == "activity" && android("name") == MAIN_ACTIVITY

    private fun Element.isProfileInstallReceiver() = tagName == "receiver" && android("name") == PROFILE_INSTALL_RECEIVER
}
