package com.olaph.xdm.adblock

import com.olaph.xdm.adblock.PopupPolicy.PopupRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupPolicyTest {
    private val adPopup = PopupRequest("https://go.popads.example/smartpop/abc", hasGesture = true, isAd = true)
    private val tappedLink = PopupRequest("https://example.com/article", hasGesture = true)
    private val scriptWindow = PopupRequest("https://example.com/promo", hasGesture = false)
    private val fromAdFrame = PopupRequest("https://shop.example.net/", hasGesture = true, openerIsAdFrame = true)
    private val blankWindow = PopupRequest(null, hasGesture = false)
    private val longPress = PopupRequest("https://go.popads.example/smartpop/abc", userInitiated = true, isAd = true)

    private fun decide(mode: PopupMode, r: PopupRequest) = PopupPolicy.decide(mode, r)

    @Test fun adPopupsAreDiscardedSilently() {
        assertEquals(PopupDecision.BLOCK_SILENT, decide(PopupMode.ALL, adPopup))
        assertEquals(PopupDecision.BLOCK_SILENT, decide(PopupMode.ADS_ONLY, adPopup))
        assertEquals(PopupDecision.BLOCK_SILENT, decide(PopupMode.ALL, fromAdFrame))
        assertEquals(PopupDecision.BLOCK_SILENT, decide(PopupMode.ADS_ONLY, fromAdFrame))
    }

    @Test fun userInitiatedLongPressIsNeverBlocked() {
        for (mode in PopupMode.entries) assertEquals(mode.name, PopupDecision.ALLOW, decide(mode, longPress))
    }

    @Test fun allModeHoldsBackEveryWindowButOffersToOpenIt() {
        assertEquals(PopupDecision.BLOCK_OFFER, decide(PopupMode.ALL, tappedLink))
        assertEquals(PopupDecision.BLOCK_OFFER, decide(PopupMode.ALL, scriptWindow))
        assertEquals("nothing to open", PopupDecision.BLOCK_SILENT, decide(PopupMode.ALL, blankWindow))
        assertEquals(PopupDecision.BLOCK_SILENT, decide(PopupMode.ALL, PopupRequest("javascript:void(0)")))
    }

    @Test fun adsOnlyModeAllowsTappedWindows() {
        assertEquals(PopupDecision.ALLOW, decide(PopupMode.ADS_ONLY, tappedLink))
        assertEquals("no tap: classic pop-up blocker", PopupDecision.BLOCK_OFFER, decide(PopupMode.ADS_ONLY, scriptWindow))
        assertEquals(PopupDecision.BLOCK_SILENT, decide(PopupMode.ADS_ONLY, blankWindow))
        assertEquals("blank window opened by a tap (filled by script)", PopupDecision.ALLOW, decide(PopupMode.ADS_ONLY, PopupRequest(null, hasGesture = true)))
    }

    @Test fun offAllowsEverything() {
        for (r in listOf(adPopup, tappedLink, scriptWindow, fromAdFrame, blankWindow)) assertEquals(PopupDecision.ALLOW, decide(PopupMode.OFF, r))
    }

    @Test fun defaultModeIsAll() {
        assertEquals(PopupMode.ALL, com.olaph.xdm.data.AppSettings().popupMode)
        assertEquals(PopupMode.ALL, AdBlocker().popupMode)
    }
}
