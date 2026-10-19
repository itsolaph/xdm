package com.olaph.xdm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedLimitInputTest {
    @Test fun kilobytesAndMegabytes() {
        assertEquals(256L * 1024, SpeedLimitInput.parse("256", megabytes = false))
        assertEquals(2L * 1024 * 1024, SpeedLimitInput.parse("2", megabytes = true))
    }

    @Test fun decimalsAndCommas() {
        assertEquals((1.5 * 1024 * 1024).toLong(), SpeedLimitInput.parse("1.5", megabytes = true))
        assertEquals((1.5 * 1024 * 1024).toLong(), SpeedLimitInput.parse(" 1,5 ", megabytes = true))
    }

    @Test fun nonsenseIsRejected() {
        for (bad in listOf("", "  ", "abc", "0", "-5", "NaN", "Infinity", "1e99", "99999999")) {
            assertNull("'$bad'", SpeedLimitInput.parse(bad, megabytes = true))
        }
    }

    @Test fun aTinyValueIsAtLeastOneByte() {
        assertEquals(1L, SpeedLimitInput.parse("0.0001", megabytes = false))
    }

    @Test fun displayPicksAUnit() {
        assertEquals("2" to true, SpeedLimitInput.display(2L * 1024 * 1024))
        assertEquals("1.5" to true, SpeedLimitInput.display((1.5 * 1024 * 1024).toLong()))
        assertEquals("256" to false, SpeedLimitInput.display(256L * 1024))
        assertEquals("1" to false, SpeedLimitInput.display(100))
    }

    @Test fun displayAndParseAgree() {
        for (bytes in listOf(64L * 1024, 512L * 1024, 1024L * 1024, 3L * 1024 * 1024)) {
            val (text, mb) = SpeedLimitInput.display(bytes)
            assertEquals(bytes, SpeedLimitInput.parse(text, mb))
        }
    }
}
