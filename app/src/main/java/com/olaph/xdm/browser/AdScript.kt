package com.olaph.xdm.browser

import android.webkit.JavascriptInterface

/**
 * Second page-facing object (`XdmAds`), reachable from every frame like [MediaBridge]. Narrow on purpose: strings
 * in, strings out, all size-capped. It only hands out CSS built from the public filter lists and whether filtering
 * applies; it exposes nothing about the user or other pages.
 *
 *  - [active]: is cosmetic filtering on for the tab's current page (master switch, allowlist, `$elemhide`)?
 *  - [css]: site-specific selectors for a document on `host` plus the always-on generic ones;
 *  - [generic]: generic selectors for the classes / ids (`.cls #id …`) a document reported.
 */
class AdBridge(
    private val active: () -> Boolean,
    private val css: (host: String?) -> String,
    private val generic: (host: String?, keys: List<String>) -> String,
) {
    @JavascriptInterface
    fun active(): Boolean = active.invoke()

    @JavascriptInterface
    fun css(host: String?): String = runCatching { css.invoke(cleanHost(host)) }.getOrDefault("")

    @JavascriptInterface
    fun generic(host: String?, keys: String?): String {
        if (keys.isNullOrEmpty() || keys.length > MAX_KEYS_CHARS) return ""
        val list = keys.split(' ').asSequence()
            .filter { it.length in 2..MAX_KEY && (it[0] == '.' || it[0] == '#') }
            .take(MAX_KEYS)
            .toList()
        if (list.isEmpty()) return ""
        return runCatching { generic.invoke(cleanHost(host), list) }.getOrDefault("")
    }

    companion object {
        const val MAX_KEYS_CHARS = 64 * 1024
        const val MAX_KEYS = 1000
        const val MAX_KEY = 256

        /** A frame's `location.hostname`: host-name characters only, else null (the tab's page host is used). */
        fun cleanHost(host: String?): String? {
            val h = host?.trim()?.lowercase() ?: return null
            if (h.isEmpty() || h.length > 253) return null
            return h.takeIf { s -> s.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' || it == ':' || it == '[' || it == ']' } }
        }
    }
}

/**
 * Cosmetic filtering, injected at document start in every frame (fallback: page start / commit / finish):
 *  1. one `<style>` with the site-specific and always-on generic selectors (`display:none !important`);
 *  2. uBO-style generic filtering: the classes and ids present in the DOM (initially, then from a throttled
 *     MutationObserver) are sent to the bridge, which answers with the generic selectors keyed by them; those
 *     are appended to the same style element.
 * Idempotent: a second injection only triggers a rescan (and re-attaches the style if the page removed it).
 */
object AdScript {
    const val BRIDGE_NAME = "XdmAds"
    const val STYLE_ID = "__xdm_adcss"

    /** Keys sent per bridge call, and the most keys one document may report in total. */
    const val BATCH = 500
    const val MAX_SEEN = 20_000

    val SOURCE: String = """
(function () {
  var A = window.$BRIDGE_NAME;
  if (!A) return;
  if (window.__xdmAds) { try { window.__xdmAds(); } catch (e) {} return; }
  try { if (!A.active()) return; } catch (e) { return; }
  var host = '';
  try { host = String(location.hostname || ''); } catch (e) {}
  var style = null, pending = '';
  try { pending = String(A.css(host) || ''); } catch (e) {}
  function flush() {
    var root = document.head || document.documentElement;
    if (!root) return false;
    if (!style) {
      style = document.getElementById('$STYLE_ID');
      if (!style) { style = document.createElement('style'); style.id = '$STYLE_ID'; }
    }
    if (!style.isConnected) root.appendChild(style);
    if (pending) { style.appendChild(document.createTextNode(pending)); pending = ''; }
    return true;
  }
  var seen = Object.create(null), seenCount = 0, queue = [], added = [], rescan = true, timer = null;
  function key(k) {
    if (seen[k] || seenCount >= $MAX_SEEN || k.length > 256 || k.indexOf(' ') >= 0) return;
    seen[k] = 1; seenCount++; queue.push(k);
  }
  function collect(el) {
    if (!el || el.nodeType !== 1) return;
    var id = el.id;
    if (id && typeof id === 'string') key('#' + id);
    var cl = el.classList;
    if (cl) for (var i = 0; i < cl.length; i++) key('.' + cl[i]);
  }
  function scanTree(root) {
    collect(root);
    if (!root.querySelectorAll) return;
    var els = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < els.length; i++) collect(els[i]);
  }
  function run() {
    timer = null;
    try {
      if (rescan) { rescan = false; added = []; if (document.documentElement) scanTree(document.documentElement); }
      else { var list = added; added = []; for (var i = 0; i < list.length; i++) if (list[i].isConnected) scanTree(list[i]); }
      while (queue.length) {
        var css = A.generic(host, queue.splice(0, $BATCH).join(' '));
        if (css) pending += css;
      }
    } catch (e) {}
    flush();
  }
  function later(delay) { if (!timer) timer = setTimeout(run, delay); }
  try {
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'attributes') { collect(m.target); continue; }
        var nodes = m.addedNodes;
        for (var j = 0; j < nodes.length; j++) {
          if (nodes[j].nodeType !== 1) continue;
          if (added.length < 1000) added.push(nodes[j]); else rescan = true;
        }
      }
      if (added.length || queue.length || rescan || (style && !style.isConnected)) later(rescan ? 50 : 100);
    }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
  } catch (e) {}
  window.__xdmAds = function () { rescan = true; later(0); };
  flush();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { rescan = true; later(0); });
  }
  later(0);
})();
""".trimIndent()
}
