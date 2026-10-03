package io.github.th3rumbl3m4t0r.jmail

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * HTML mail in a WebView with the brakes on: no JavaScript, no file or content access,
 * and no network at all unless [online] is true. Images referenced by `cid:` come from the
 * message's own parts. Links open in the browser. The view grows to the height of its content.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlView(html: String, inline: Map<String, OpenedAttachment>, online: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var heightPx by remember(html) { mutableIntStateOf(0) }
    val density = ctx.resources.displayMetrics.density
    val page = remember(html) { wrap(html) }
    // read from the interceptor's worker thread: WebView methods themselves may only be called on the main thread
    val allowNet = remember { java.util.concurrent.atomic.AtomicBoolean(online) }
    allowNet.set(online)

    AndroidView(
        modifier = modifier.fillMaxWidth().height(if (heightPx > 0) (heightPx / density).dp else 320.dp),
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        if (u.scheme == "http" || u.scheme == "https" || u.scheme == "mailto") {
                            runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u: Uri = request.url
                        if (u.scheme == "cid") {
                            val id = u.schemeSpecificPart.trim('<', '>')
                            val part = inline[id] ?: inline.entries.firstOrNull { it.key.trim('<', '>') == id }?.value
                            if (part != null) {
                                val bytes = part.bytes ?: runCatching { Sync.client().download(part.blobId!!, part.name, part.type) }.getOrNull()
                                if (bytes != null) return WebResourceResponse(part.type, null, ByteArrayInputStream(bytes))
                            }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        // everything else is the network: only when the reader said so
                        if (allowNet.get() || u.scheme == "data" || u.scheme == "about") return null
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // the content height settles a little after the page is in
                        var tries = 0
                        val r = object : Runnable {
                            override fun run() {
                                val h = (view.contentHeight * view.scale).toInt()
                                if (h > 0) heightPx = h
                                if (++tries < 8) view.postDelayed(this, 120)
                            }
                        }
                        view.post(r)
                    }
                }
            }
        },
        update = { w ->
            val changed = w.tag != page || (w.settings.blockNetworkLoads == online)
            w.settings.blockNetworkLoads = !online
            w.settings.blockNetworkImage = !online
            if (changed) {
                w.tag = page
                w.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
    )
}

/** The message's HTML on a light sheet: a default look for mail that brings none, the viewport, images that fit. */
private fun wrap(html: String): String {
    val style = """<meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        html,body{margin:0;padding:8px;background:#e9edf1;color:#1f262e;font-family:sans-serif;font-size:15px;line-height:1.4;word-wrap:break-word;overflow-wrap:anywhere}
        img{max-width:100% !important;height:auto}
        pre{white-space:pre-wrap}
        table{max-width:100%}
        a{color:#2a5d9f}
        blockquote{border-left:3px solid #a6afbb;margin:0;padding-left:8px;color:#5a636f}
        </style>"""
    val hasHead = Regex("(?i)<head[^>]*>").find(html)
    return when {
        hasHead != null -> html.replaceRange(hasHead.range.last + 1, hasHead.range.last + 1, style)
        Regex("(?i)<html[^>]*>").containsMatchIn(html) -> Regex("(?i)<html[^>]*>").replace(html, "$0<head>$style</head>")
        else -> "<html><head>$style</head><body>$html</body></html>"
    }
}
