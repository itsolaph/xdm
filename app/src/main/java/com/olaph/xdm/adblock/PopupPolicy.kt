package com.olaph.xdm.adblock

/** Settings value for "Block pop-ups". */
enum class PopupMode(val label: String) {
    OFF("Off"),
    ADS_ONLY("Ads only"),
    ALL("All"),
}

enum class PopupDecision {
    /** Open it in a new tab. */
    ALLOW,

    /** Discard without asking (an ad); counted in the shield. */
    BLOCK_SILENT,

    /** Discard, but offer "Pop-up blocked — Open" so the user can still open it. Counted too. */
    BLOCK_OFFER,
}

/**
 * Decides what happens to a window a page tries to open (`window.open`, `target=_blank`, pop-unders). Pure, so
 * every mode is covered by JVM tests; the browser supplies the facts:
 *  - [PopupRequest.userInitiated]: XDM's own long-press "Open in new tab" (never blocked);
 *  - [PopupRequest.hasGesture]: WebView says a tap started it;
 *  - [PopupRequest.isAd]: the target matches a `$popup` / `$document` / host-level rule;
 *  - [PopupRequest.openerIsAdFrame]: it was opened from a third-party frame that is itself blocked.
 */
object PopupPolicy {
    class PopupRequest(
        val targetUrl: String?,
        val userInitiated: Boolean = false,
        val hasGesture: Boolean = false,
        val isAd: Boolean = false,
        val openerIsAdFrame: Boolean = false,
    )

    fun decide(mode: PopupMode, request: PopupRequest): PopupDecision {
        if (request.userInitiated || mode == PopupMode.OFF) return PopupDecision.ALLOW
        if (request.isAd || request.openerIsAdFrame) return PopupDecision.BLOCK_SILENT
        val openable = request.targetUrl?.let { it.startsWith("http://", true) || it.startsWith("https://", true) } == true
        return when (mode) {
            // Everything a script opens is held back; the user can still open it from the snackbar.
            PopupMode.ALL -> if (openable) PopupDecision.BLOCK_OFFER else PopupDecision.BLOCK_SILENT
            // Classic pop-up blocker plus ads: a tap may open a window, a script on its own may not.
            PopupMode.ADS_ONLY -> when {
                request.hasGesture -> PopupDecision.ALLOW
                openable -> PopupDecision.BLOCK_OFFER
                else -> PopupDecision.BLOCK_SILENT
            }
            PopupMode.OFF -> PopupDecision.ALLOW
        }
    }
}
