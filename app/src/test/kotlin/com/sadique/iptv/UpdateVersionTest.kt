package com.sadique.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateVersionTest {
    @Test fun readsBuildNumberFromReleaseTag() {
        assertEquals(45L, UpdateChecker.parseVersionCode("v2.0.45"))
    }

    @Test fun unusableTagsHaveNoBuildNumber() {
        for (tag in listOf("", "latest", "v2.0.", "v2.0.beta", "v2.0.99999999999999999999")) {
            assertNull(tag, UpdateChecker.parseVersionCode(tag))
        }
    }
}
