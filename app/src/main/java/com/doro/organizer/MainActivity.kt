package com.doro.organizer

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.doro.organizer.ui.theme.DoroTheme

/** The single activity. A [FragmentActivity] because BiometricPrompt 1.1.0 needs one (DEC-04). */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // before setContent (DEC-13)
        setContent {
            DoroTheme {
                Surface(modifier = Modifier.fillMaxSize()) {}
            }
        }
    }
}
