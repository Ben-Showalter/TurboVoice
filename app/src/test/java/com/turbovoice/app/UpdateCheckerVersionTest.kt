package com.turbovoice.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerVersionTest {

    @Test
    fun higherVersionsAreNewer() {
        assertTrue(UpdateChecker.isNewer("1.1", "1.0"))
        assertTrue(UpdateChecker.isNewer("1.10", "1.9"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.99.9"))
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun sameOrLowerVersionsAreNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.0", "1.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0"))
        assertFalse(UpdateChecker.isNewer("0.9", "1.0"))
        assertFalse(UpdateChecker.isNewer("1.9", "1.10"))
    }

    @Test
    fun suffixesAreIgnored() {
        assertFalse(UpdateChecker.isNewer("1.0-beta", "1.0"))
        assertTrue(UpdateChecker.isNewer("1.1-rc1", "1.0"))
    }
}
