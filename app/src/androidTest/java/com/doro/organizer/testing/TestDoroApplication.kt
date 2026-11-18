package com.doro.organizer.testing

import com.doro.organizer.DoroApplication

/** Starts nothing: each test that needs the running app builds its container with [DoroAppRule]. */
class TestDoroApplication : DoroApplication() {
    override fun startApp() = Unit
}
