package com.doro.organizer.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Starts the app as [TestDoroApplication], so no container exists until a test installs one through [DoroAppRule]
 * (testing.md § 7, Wiring).
 */
class DoroTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestDoroApplication::class.java.name, context)
}
