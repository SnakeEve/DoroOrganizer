package com.doro.organizer.testing

import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

/**
 * The page-size smoke subset of testing.md § 2: SQLCipher classes that the optional `api35ps16k` run executes on
 * 16 KB memory pages. On the phone they run as ordinary instrumented tests. Every annotated class calls
 * [assertExpectedPageSize] in `@Before`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class PageSizeSmoke

/**
 * With the `expectedPageSize` instrumentation argument (the optional smoke run passes 16384), fails unless the device
 * uses that page size, so a wrong image fails loudly; without the argument it checks nothing (testing.md § 2).
 */
fun assertExpectedPageSize() {
    val expected = InstrumentationRegistry.getArguments().getString("expectedPageSize") ?: return
    assertEquals("memory page size", expected.toLong(), Os.sysconf(OsConstants._SC_PAGESIZE))
}
