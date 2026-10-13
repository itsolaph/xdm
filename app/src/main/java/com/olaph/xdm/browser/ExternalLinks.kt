package com.olaph.xdm.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URISyntaxException

/** Opens non-web links (`intent://`, `market://`, `tel:`, `mailto:`...) in other apps, defensively. */
object ExternalLinks {
    /** Schemes the WebView renders itself. */
    private val INTERNAL = setOf("http", "https", "about", "data", "blob", "javascript")

    /** Local content must never be reachable from a web page. */
    private val BLOCKED = setOf("file", "content")

    sealed interface Outcome {
        /** Let the WebView load the URL. */
        data object LoadInPage : Outcome

        /** The link was consumed (an app was launched, or it was blocked). */
        data object Consumed : Outcome

        /** Consumed, and the page asked for [url] as a fallback (`browser_fallback_url`). */
        data class Fallback(val url: String) : Outcome
    }

    fun handle(context: Context, url: String, hasGesture: Boolean): Outcome {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme in INTERNAL) return Outcome.LoadInPage
        if (scheme in BLOCKED || scheme.isEmpty()) return Outcome.Consumed
        // Pages can't pop open apps on their own: this needs a tap (stops redirect-based app launching).
        if (!hasGesture) return Outcome.Consumed

        val intent = try {
            if (scheme == "intent") Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (_: URISyntaxException) {
            return Outcome.Consumed
        }
        // Strip anything that would let a page target a specific component or hand out URI grants.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        return try {
            context.startActivity(intent)
            Outcome.Consumed
        } catch (_: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            when {
                fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://")) -> Outcome.Fallback(fallback)
                intent.`package` != null -> openStore(context, intent.`package`!!)
                else -> Outcome.Consumed
            }
        } catch (_: SecurityException) {
            Outcome.Consumed
        }
    }

    private fun openStore(context: Context, pkg: String): Outcome {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Uri.encode(pkg)))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(store)
        } catch (_: ActivityNotFoundException) {
        }
        return Outcome.Consumed
    }
}
