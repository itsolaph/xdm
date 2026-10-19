package com.olaph.xdm.engine

/**
 * Is a new link for a half-finished download still the same file? Decides whether the bytes already on disk can be
 * kept. Plain function so the rules are tested on the JVM; the manager only feeds it the old record and the new probe.
 */
object LinkMatch {
    sealed interface Verdict {
        /** Same file as far as can be told: swap the link and carry on. */
        data object Same : Verdict

        /** A different size: this is another file, the link is not accepted. */
        data class Different(val oldSize: Long, val newSize: Long) : Verdict

        /** The link may work, but the bytes on disk cannot be trusted: only a restart from zero is safe. */
        data class RestartOnly(val reason: Reason) : Verdict
    }

    enum class Reason { ETAG_CHANGED, SIZE_UNKNOWN }

    fun decide(oldSize: Long, oldEtag: String?, newSize: Long, newEtag: String?): Verdict {
        if (oldSize > 0) {
            // Without a size to compare there is no proof the new link serves the same bytes.
            if (newSize <= 0) return Verdict.RestartOnly(Reason.SIZE_UNKNOWN)
            if (newSize != oldSize) return Verdict.Different(oldSize, newSize)
        }
        if (oldEtag != null && newEtag != null && oldEtag != newEtag) return Verdict.RestartOnly(Reason.ETAG_CHANGED)
        return Verdict.Same
    }
}
