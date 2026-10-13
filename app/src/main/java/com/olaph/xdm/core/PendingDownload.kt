package com.olaph.xdm.core

/**
 * A download the user is about to confirm in the "Add download" sheet. Created by the share flow
 * (URL only) or by the built-in browser (URL + the headers the page used + hints from the response).
 */
data class PendingDownload(
    val url: String,
    /** Cookie / User-Agent / Referer captured from the browser; sent with every request of the download. */
    val headers: Map<String, String> = emptyMap(),
    val fileName: String? = null,
    val mimeType: String? = null,
    /** From the browser's download callback; -1 when unknown. */
    val contentLength: Long = -1,
    /** The page that started the download, shown as "From: origin". */
    val pageUrl: String? = null,
    /** [com.olaph.xdm.data.DownloadKind]: HLS playlists are downloaded segment by segment. */
    val kind: String = com.olaph.xdm.data.DownloadKind.HTTP,
    /** True when [contentLength] is an estimate (HLS bandwidth × duration). */
    val lengthEstimated: Boolean = false,
)
