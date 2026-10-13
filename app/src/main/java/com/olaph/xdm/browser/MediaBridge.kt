package com.olaph.xdm.browser

import android.webkit.JavascriptInterface

/**
 * The only object exposed to page JavaScript (as `XdmBridge`). It is reachable from every frame, including
 * ads and third-party iframes, so it is deliberately tiny: three methods, strings in, nothing out. Input is
 * bounded and only http(s) URLs get through; what the page reports is merely a *candidate* that XDM
 * verifies itself before showing it.
 */
class MediaBridge(
    private val mediaSink: (url: String, source: String) -> Unit,
    private val playSink: (url: String) -> Unit,
    private val touchSink: (url: String) -> Unit,
) {
    /** A `<video>`/`<audio>`/`<source>`/meta URL found in the DOM. */
    @JavascriptInterface
    fun onMedia(url: String?, source: String?) {
        val u = clean(url) ?: return
        mediaSink(u, source.orEmpty().take(16))
    }

    /** A media element started playing; blank [url] means a `blob:` (MSE) source. */
    @JavascriptInterface
    fun onPlay(url: String?) {
        playSink(clean(url).orEmpty())
    }

    /** The user touched a media element ([url] is "blob" for MSE sources); used by the long-press menu. */
    @JavascriptInterface
    fun onTouchVideo(url: String?) {
        val raw = url.orEmpty()
        touchSink(if (raw == "blob") raw else clean(raw).orEmpty())
    }

    private fun clean(url: String?): String? {
        if (url == null || url.length > MAX_URL) return null
        val lower = url.take(8).lowercase()
        return url.takeIf { lower.startsWith("http://") || lower.startsWith("https://") }
    }

    private companion object {
        const val MAX_URL = 4096
    }
}

/**
 * Injected into pages (document start where supported, and again when a page finishes loading). Collects the
 * media URLs the DOM exposes, watches for new ones, and reports `play` events. `blob:` URLs are skipped: the
 * manifest behind an MSE player is picked up from the network instead.
 */
object MediaScript {
    const val BRIDGE_NAME = "XdmBridge"

    val SOURCE: String = """
(function () {
  var B = window.$BRIDGE_NAME;
  if (!B) return;
  if (window.__xdmScan) { window.__xdmScan(); return; }
  var sent = {};
  var count = 0;
  function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return null; } }
  function send(u, src) {
    if (!u || typeof u !== 'string') return;
    var a = abs(u);
    if (!a || a.indexOf('http') !== 0) return;
    if (sent[a] || count >= 200) return;
    sent[a] = 1; count++;
    try { B.onMedia(a, src); } catch (e) {}
  }
  function srcOf(el) { return el.currentSrc || el.src || ''; }
  function isMedia(el) { return el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO'); }
  function remember(e) {
    try {
      var d = (e.target && e.target.ownerDocument) || document;
      var p = (e.touches && e.touches.length) ? e.touches[0] : e;
      var list = d.elementsFromPoint ? d.elementsFromPoint(p.clientX, p.clientY) : [e.target];
      for (var i = 0; i < list.length; i++) {
        if (isMedia(list[i])) {
          var u = srcOf(list[i]);
          B.onTouchVideo(u.indexOf('http') === 0 ? u : 'blob');
          return;
        }
      }
    } catch (x) {}
  }
  function listen(doc) {
    if (doc.__xdmL) return;
    doc.__xdmL = 1;
    doc.addEventListener('play', function (e) {
      if (!isMedia(e.target)) return;
      var u = srcOf(e.target);
      try { B.onPlay(u.indexOf('http') === 0 ? u : ''); } catch (x) {}
      send(u, 'play');
    }, true);
    doc.addEventListener('touchstart', remember, { capture: true, passive: true });
    doc.addEventListener('contextmenu', remember, true);
  }
  function scanDoc(doc, depth) {
    try {
      listen(doc);
      var els = doc.querySelectorAll('video, audio, source');
      for (var i = 0; i < els.length; i++) { send(els[i].currentSrc, 'dom'); send(els[i].src, 'dom'); }
      var metas = doc.querySelectorAll('meta[property^="og:video"], meta[property^="og:audio"], meta[name="twitter:player:stream"]');
      for (var j = 0; j < metas.length; j++) send(metas[j].getAttribute('content'), 'meta');
      if (depth < 3) {
        var frames = doc.querySelectorAll('iframe');
        for (var k = 0; k < frames.length; k++) {
          try { if (frames[k].contentDocument) scanDoc(frames[k].contentDocument, depth + 1); } catch (x) {}
        }
      }
    } catch (x) {}
  }
  function scan() { scanDoc(document, 0); }
  window.__xdmScan = scan;
  var timer = null;
  function later() { if (timer) return; timer = setTimeout(function () { timer = null; scan(); }, 600); }
  try {
    new MutationObserver(later).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
  } catch (e) {}
  setInterval(scan, 4000);
  scan();
})();
""".trimIndent()
}
