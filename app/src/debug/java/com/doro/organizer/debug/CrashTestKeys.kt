package com.doro.organizer.debug

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.doro.organizer.core.coroutines.AppDispatchers
import com.doro.organizer.core.time.AppTime
import com.doro.organizer.security.AndroidBioKeystore
import com.doro.organizer.security.AndroidKekKeystore
import com.doro.organizer.security.BIO_ALIAS
import com.doro.organizer.security.DefaultKeyVault
import com.doro.organizer.settings.SettingsStore
import java.io.File

/**
 * The test configuration of the biometric seam (testing.md § 5, technique "Biometric slot"): the production alias,
 * key size, GCM mode and purposes, without the user-authentication parameters, so a `Cipher` works without a touch.
 * Debug-only: the `:crashtest` process cannot load test classes, and it arms and uses the slot with this spec.
 * `BiometricKeySpecTest` pins the production spec.
 */
fun testBioKeySpec(): KeyGenParameterSpec =
    KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build()

/** The production vault on the production key-state file, with the biometric seam in its test configuration. */
fun crashTestVault(context: Context, settings: SettingsStore, appTime: AppTime, dispatchers: AppDispatchers) =
    DefaultKeyVault(
        stateFile = File(context.noBackupFilesDir, DefaultKeyVault.STATE_FILE_NAME),
        kek = AndroidKekKeystore.forDevice(context),
        settings = settings,
        appTime = appTime,
        dispatchers = dispatchers,
        bio = AndroidBioKeystore(::testBioKeySpec),
    )
