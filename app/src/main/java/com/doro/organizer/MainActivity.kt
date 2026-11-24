package com.doro.organizer

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.contentcapture.ContentCaptureManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.doro.organizer.session.GateState
import com.doro.organizer.ui.DoroRoot
import com.doro.organizer.ui.LocalUiGraph
import com.doro.organizer.ui.theme.DoroTheme
import kotlinx.coroutines.launch

/**
 * The single activity, a [FragmentActivity] because BiometricPrompt 1.1.0 needs one (DEC-04). The window starts
 * closed in every `onCreate` and is relaxed only by the window policy (DEC-67, architecture.md § MainActivity startup
 * order); the splash is held until that policy is applied and the gate has left `Starting`.
 */
class MainActivity : FragmentActivity() {
    private lateinit var container: AppContainer

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        DebugTools.configure(this) // a test's font scale in debug, no-op in release
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // before setContent (DEC-13)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        disableContentCapture()
        container = (application as DoroApplication).container
        var policyApplied = false // main thread only
        val policyWindow = ActivityWindow(this)
        lifecycleScope.launch {
            container.windowPolicy.collect { policy ->
                applyWindowPolicy(policy, policyWindow)
                policyApplied = true
            }
        }
        splash.setKeepOnScreenCondition { !policyApplied || container.sessionGate.state.value is GateState.Starting }
        container.externalActivities.bind(this) // every result launcher (DEC-90)
        container.appLockController.attach(this) // one BiometricPrompt per activity instance (DEC-04)
        setContent {
            CompositionLocalProvider(LocalUiGraph provides container) { // no keyboard-privacy local: always on (DEC-71)
                DoroTheme { DoroRoot() }
            }
        }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS // after setContent (DEC-71)
    }

    override fun onDestroy() {
        container.appLockController.detach(this)
        super.onDestroy()
    }

    /**
     * System content capture off on API 29+ (DEC-66, security.md § 8). Not persisted across reboots, so it runs on
     * every `onCreate`. The call is deprecated in API 37: Android 17's behavior changes (behavior-changes-17) say it
     * no longer disables content capture for apps that target 37 or higher, so it is kept while targetSdk stays below
     * 37. Its effect on an API 37 device for this app is inferred from that page, not observed (DEC-01, DEC-66).
     */
    private fun disableContentCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION") // API 37, see the KDoc (DEC-66)
            getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)
        }
    }
}
