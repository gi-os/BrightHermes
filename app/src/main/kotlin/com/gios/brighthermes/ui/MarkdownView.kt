package com.gios.brighthermes.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.brighthermes.chat.Block
import com.gios.brighthermes.chat.Inline
import com.gios.brighthermes.deck.Tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * LightChat's renderer with the deck's rules applied: no radii, no tinted fills, one grey for
 * code and rules. Two things it has that LightChat's does not — a **card** (a `html` fence,
 * rendered live in a WebView with the deck defaults, see [WidgetView]) and **images** that load
 * through the gateway with the token when they live there, and from a `data:` URI when June
 * inlines one. Images are drawn grey: the panel is grey, and a colour photograph the width of the
 * screen beside white text on black reads as a foreign object.
 */
@Composable
fun MarkdownView(
    blocks: List<Block>,
    type: Type,
    host: WidgetHost,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = type.body,
    contentColor: Color = Ink.Content,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> MarkdownText(block.children, textStyle, contentColor)
                is Block.Heading -> MarkdownText(
                    block.children,
                    when (block.level) {
                        1 -> type.title
                        2 -> type.bodyYou.copy(fontSize = 19.sp, lineHeight = 25.sp)
                        else -> type.bodyYou
                    },
                    contentColor,
                )
                is Block.Code -> Box(Modifier.fillMaxWidth().background(Ink.Rule).padding(9.dp)) {
                    Text(block.text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp), color = contentColor)
                }
                is Block.Html -> WidgetView(
                    Tile(id = "card", label = "", value = "", sub = "", html = block.html, height = block.height),
                    type, host.server, host.token, host.device,
                )
                is Block.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(Ink.Secondary))
                    Column(Modifier.padding(start = 9.dp)) {
                        MarkdownView(block.children, type, host, textStyle = textStyle, contentColor = Ink.Secondary)
                    }
                }
                is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            val marker = if (block.ordered) "${index + 1}." else "–"
                            Text(marker, style = textStyle, color = Ink.Secondary, modifier = Modifier.width(if (block.ordered) 24.dp else 16.dp))
                            MarkdownText(item, textStyle, contentColor)
                        }
                    }
                }
                is Block.Image -> MarkdownImage(block.url, block.alt, host, type)
                Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Rule))
            }
        }
    }
}

/** Where the images and cards may call home. */
data class WidgetHost(val server: String, val token: String, val device: String)

@Composable
private fun MarkdownText(inlines: List<Inline>, style: TextStyle, contentColor: Color) {
    Text(text = annotated(inlines, contentColor), style = style, color = contentColor)
}

fun annotated(inlines: List<Inline>, contentColor: Color): AnnotatedString = buildAnnotatedString { emit(inlines, contentColor) }

private fun AnnotatedString.Builder.emit(list: List<Inline>, contentColor: Color) {
    for (inl in list) {
        when (inl) {
            is Inline.Text -> append(inl.text)
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { emit(inl.children, contentColor) }
            is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(inl.children, contentColor) }
            is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(inl.text) }
            is Inline.Link -> withLink(
                LinkAnnotation.Url(inl.url, TextLinkStyles(SpanStyle(color = contentColor, textDecoration = TextDecoration.Underline))),
            ) { emit(inl.children, contentColor) }
            is Inline.Image -> append(inl.alt.ifBlank { "image" })
        }
    }
}

@Composable
private fun MarkdownImage(url: String, alt: String, host: WidgetHost, type: Type) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url) { value = Images.load(url, host) }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = alt,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Text(alt.ifBlank { "image" }, style = type.small, color = Ink.Secondary)
    }
}

/**
 * Loading a picture June sent. `data:` URIs decode in place; anything on the gateway's own host
 * gets the token (that is how `POST /images` uploads come back); anything else is fetched as is.
 * Bounded to the panel: decoded at most 1080px wide, and turned grey so it matches the screen.
 */
object Images {
    private val client = OkHttpClient()

    suspend fun load(url: String, host: WidgetHost): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes: ByteArray = if (url.startsWith("data:")) {
                val comma = url.indexOf(',')
                if (comma < 0) return@runCatching null
                Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
            } else {
                val full = if (url.startsWith("/")) host.server.trimEnd('/') + url else url
                val b = Request.Builder().url(full)
                if (full.startsWith(host.server.trimEnd('/'))) {
                    b.header("Authorization", "Bearer " + host.token).header("X-Device", host.device)
                }
                client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } ?: return@runCatching null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1080) sample *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching null
            grey(src)
        }.getOrNull()
    }

    private fun grey(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        if (src != out) src.recycle()
        return out
    }
}
