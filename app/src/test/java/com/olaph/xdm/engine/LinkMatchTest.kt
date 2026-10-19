package com.olaph.xdm.engine

import com.olaph.xdm.engine.LinkMatch.Reason
import com.olaph.xdm.engine.LinkMatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkMatchTest {
    @Test fun sameSizeIsTheSameFile() {
        assertEquals(Verdict.Same, LinkMatch.decide(1000, null, 1000, null))
    }

    @Test fun sameSizeAndSameEtagIsTheSameFile() {
        assertEquals(Verdict.Same, LinkMatch.decide(1000, "\"abc\"", 1000, "\"abc\""))
    }

    @Test fun aDifferentSizeIsAnotherFile() {
        assertEquals(Verdict.Different(1000, 1001), LinkMatch.decide(1000, null, 1001, null))
        // a different size wins over a matching validator
        assertEquals(Verdict.Different(1000, 10), LinkMatch.decide(1000, "\"a\"", 10, "\"a\""))
    }

    @Test fun anUnknownNewSizeCannotBeVerified() {
        assertEquals(Verdict.RestartOnly(Reason.SIZE_UNKNOWN), LinkMatch.decide(1000, "\"a\"", -1, "\"a\""))
        assertEquals(Verdict.RestartOnly(Reason.SIZE_UNKNOWN), LinkMatch.decide(1000, null, 0, null))
    }

    @Test fun anUnknownOldSizeLeavesNothingToCompare() {
        assertEquals(Verdict.Same, LinkMatch.decide(-1, null, 5000, null))
        assertEquals(Verdict.Same, LinkMatch.decide(-1, null, -1, null))
    }

    @Test fun aChangedEtagMeansRestartingFromZero() {
        assertEquals(Verdict.RestartOnly(Reason.ETAG_CHANGED), LinkMatch.decide(1000, "\"old\"", 1000, "\"new\""))
        assertEquals(Verdict.RestartOnly(Reason.ETAG_CHANGED), LinkMatch.decide(-1, "\"old\"", 1000, "\"new\""))
    }

    @Test fun anEtagThatIsMissingOnOneSideIsNotAChange() {
        assertEquals(Verdict.Same, LinkMatch.decide(1000, "\"old\"", 1000, null))
        assertEquals(Verdict.Same, LinkMatch.decide(1000, null, 1000, "\"new\""))
    }
}
