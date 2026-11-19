package com.doro.organizer.core.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A queued request that a newer one replaces ends without a launch and is reported (security.md § 7.6). */
class ExternalRequestQueueTest {
    private val notLaunched = mutableListOf<ExternalRequest>()
    private val callbacks = object : ExternalCallbacks {
        override fun beforeLaunch(kind: ExternalKind) = throw AssertionError("nothing is launched")

        override fun afterResult(kind: ExternalKind) = throw AssertionError("nothing is launched")

        override fun notLaunched(request: ExternalRequest) {
            notLaunched += request
        }

        override fun onCredentialResult(resultCode: Int) = throw AssertionError("no result without a launch")
    }

    @Test
    fun aReplacedRequest_isReportedNotLaunched() {
        val external = DefaultExternalActivities { callbacks }
        val first = ExternalRequest(ExternalKind.CredentialConfirm)
        val second = ExternalRequest(ExternalKind.Enroll)
        external.request(first)
        assertEquals(emptyList<ExternalRequest>(), notLaunched)
        external.request(second)
        assertSame(first, notLaunched.single())
    }
}
