package com.gios.brighthermes.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gios.brighthermes.deck.Tile
import org.json.JSONObject

/**
 * A deck tile June draws herself: her HTML, in a WebView, full width, `height` grid units tall.
 *
 * JavaScript is on, because "fully control" means a live thing and not a picture. Before the
 * page runs it gets `window.brighthermes = {server, token, device}` so it can call the gateway
 * back — read a tile, post to the journal, send June a message — and a `document` with the
 * deck's own defaults (black, white, the system font, no margin) when the HTML is a fragment
 * rather than a whole page. A whole page (`<html` present) is left exactly as written.
 *
 * Links stay inside: a tap on `<a href>` is swallowed rather than opening a browser, since the
 * widget is a surface on the deck, not a way off it. Anything the widget wants to *do* it does
 * with `fetch` against the gateway.
 *
 * The view is keyed on the HTML, so a new payload from June replaces the page rather than
 * reloading a stale one; the WebView itself is reused across recompositions that change
 * nothing.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WidgetView(tile: Tile, type: Type, server: String, token: String, device: String, modifier: Modifier = Modifier) {
    val html = tile.html ?: return
    Column(modifier.fillMaxWidth()) {
        if (tile.label.isNotBlank()) {
            Text(tile.label.uppercase(), style = type.label, color = Ink.Secondary)
            Spacer(Modifier.height(6.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(Grid * tile.height),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(Color.BLACK)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = false
                    settings.mediaPlaybackRequiresUserGesture = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    }
                    tag = ""
                }
            },
            update = { web ->
                val page = document(html, server, token, device)
                if (web.tag != page) {
                    web.tag = page
                    // The gateway as base URL, so a widget's relative `fetch("/tiles/weather")`
                    // lands on it, and so its origin is the gateway's for CORS purposes.
                    web.loadDataWithBaseURL(server, page, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/** The page the WebView loads: June's HTML with the runtime handle in front of it. */
internal fun document(html: String, server: String, token: String, device: String): String {
    val handle = JSONObject().put("server", server).put("token", token).put("device", device).toString()
    val prelude = """<script>window.brighthermes=$handle;
window.brighthermes.fetch=(p,o={})=>fetch(window.brighthermes.server+p,Object.assign({},o,{headers:Object.assign({"Authorization":"Bearer "+window.brighthermes.token,"X-Device":window.brighthermes.device},o.headers||{})}));</script>"""
    val trimmed = html.trimStart()
    return if (trimmed.startsWith("<!") || trimmed.startsWith("<html", ignoreCase = true)) {
        // A whole document: slot the handle in right after <head> if there is one, else at the top.
        val i = trimmed.indexOf("<head", ignoreCase = true)
        if (i >= 0) {
            val close = trimmed.indexOf('>', i)
            trimmed.substring(0, close + 1) + prelude + trimmed.substring(close + 1)
        } else {
            prelude + trimmed
        }
    } else {
        """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;padding:0;background:#000;color:#fff;font-family:-apple-system,Akkurat,system-ui,sans-serif;font-size:15px;line-height:1.4;-webkit-tap-highlight-color:transparent}
a{color:#fff}.dim{color:#bbb}.big{font-size:24px;letter-spacing:-.02em;line-height:1}.label{font-size:9px;letter-spacing:.1em;text-transform:uppercase;color:#bbb}
button{background:#000;color:#fff;border:1px solid #fff;border-radius:0;font:inherit;padding:6px 12px}</style>$prelude</head><body>$html</body></html>"""
    }
}
