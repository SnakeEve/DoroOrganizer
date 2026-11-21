package com.doro.organizer

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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // before setContent (DEC-13)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Not persisted across reboots, so on every onCreate (security.md § 8).
            getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)
        }
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
}
