package com.olaph.xdm.browser

/**
 * Run in refresh mode so a page that waits for a tap on play still requests its video. Only `<video>` elements are
 * touched (muted, same-origin frames included); nothing on the page is clicked.
 */
object RefreshScript {
    val PLAY: String = """
(function () {
  function go(doc, depth) {
    try {
      doc.querySelectorAll('video').forEach(function (v) { v.muted = true; v.play().catch(function () {}); });
      if (depth < 3) doc.querySelectorAll('iframe').forEach(function (f) {
        try { if (f.contentDocument) go(f.contentDocument, depth + 1); } catch (e) {}
      });
    } catch (e) {}
  }
  go(document, 0);
})();
""".trimIndent()

    /** Delays after a page finished loading at which playback is tried again, for players that appear late. */
    val RETRY_DELAYS_MS = longArrayOf(0, 2_000, 5_000, 10_000)
}
