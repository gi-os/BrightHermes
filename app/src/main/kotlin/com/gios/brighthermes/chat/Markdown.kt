package com.gios.brighthermes.chat

/**
 * LightChat's Markdown parser, carried over whole (same author, same panel), plus one thing:
 * a fenced block whose info string is `html` is not code, it is a card — a live page June
 * drew, rendered in a WebView with the deck's own defaults. ```html 6` sets its height
 * in grid units (15dp each); the default is 8.
 *
 * A small, dependency-free Markdown subset parser for agent replies. Covers what an
 * LLM actually emits: headings, paragraphs, bold/italic, inline + fenced code, links,
 * images (inline and block), ordered/unordered lists, blockquotes and horizontal rules.
 *
 * Deliberately not a full CommonMark implementation — it exists to render agent text on a
 * black-and-white phone, not to round-trip documents. Underscore emphasis is not supported
 * on purpose: `_italic_`/`__bold__` would mangle snake_case identifiers that models emit
 * constantly, so emphasis is `*italic*` / `**bold**` only.
 */

/** Inline spans within a block. */
sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Code(val text: String) : Inline
    data class Link(val children: List<Inline>, val url: String) : Inline
    /** An image inline in prose; rendered as its alt text (block images render fully). */
    data class Image(val alt: String, val url: String) : Inline
}

/** A structural unit of a document. */
sealed interface Block {
    data class Paragraph(val children: List<Inline>) : Block
    data class Heading(val level: Int, val children: List<Inline>) : Block
    data class Code(val text: String, val lang: String = "") : Block

    /** A live HTML card — a ```html fence. [height] in grid units. */
    data class Html(val html: String, val height: Int) : Block
    data class Quote(val children: List<Block>) : Block
    data class ListBlock(val ordered: Boolean, val items: List<List<Inline>>) : Block
    data class Image(val alt: String, val url: String) : Block
    data object Rule : Block
}

object Markdown {

    fun parse(src: String): List<Block> {
        val lines = src.lines()
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            when {
                t.isEmpty() -> i++
                isFence(t) -> {
                    val info = t.removePrefix("```").trim()
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !isFence(lines[i].trim())) {
                        buf.appendLine(lines[i]); i++
                    }
                    if (i < lines.size) i++ // closing fence
                    val body = buf.toString().trimEnd()
                    val words = info.split(Regex("\s+")).filter { it.isNotBlank() }
                    if (words.firstOrNull().equals("html", ignoreCase = true)) {
                        val h = words.getOrNull(1)?.toIntOrNull()?.coerceIn(2, 24) ?: 8
                        out.add(Block.Html(body, h))
                    } else {
                        out.add(Block.Code(body, words.firstOrNull().orEmpty()))
                    }
                }
                isQuote(t) -> {
                    val buf = StringBuilder()
                    while (i < lines.size && isQuote(lines[i].trim())) {
                        buf.appendLine(lines[i].trim().removePrefix(">").removePrefix(" "))
                        i++
                    }
                    out.add(Block.Quote(parse(buf.toString())))
                }
                isHeading(t) -> {
                    val level = t.takeWhile { it == '#' }.length
                    out.add(Block.Heading(level, parseInlines(t.drop(level).trim())))
                    i++
                }
                isRule(t) -> { out.add(Block.Rule); i++ }
                isUnordered(t) -> {
                    val items = ArrayList<List<Inline>>()
                    while (i < lines.size && isUnordered(lines[i].trim())) {
                        items.add(parseInlines(lines[i].trim().substring(2).trim()))
                        i++
                    }
                    out.add(Block.ListBlock(ordered = false, items = items))
                }
                isOrdered(t) -> {
                    val re = Regex("^\\d+\\.\\s+")
                    val items = ArrayList<List<Inline>>()
                    while (i < lines.size && isOrdered(lines[i].trim())) {
                        items.add(parseInlines(lines[i].trim().replace(re, "")))
                        i++
                    }
                    out.add(Block.ListBlock(ordered = true, items = items))
                }
                isStandaloneImage(t) -> {
                    val img = imageAt(t, 0)
                    out.add(if (img == null) Block.Paragraph(parseInlines(t)) else Block.Image(img.first, img.second))
                    i++
                }
                else -> {
                    val buf = StringBuilder()
                    while (i < lines.size && !isBlockStart(lines[i].trim())) {
                        buf.append(lines[i].trim()).append(' ')
                        i++
                    }
                    out.add(Block.Paragraph(parseInlines(buf.toString().trim())))
                }
            }
        }
        return out
    }

    fun parseInlines(s: String): List<Inline> {
        val out = ArrayList<Inline>()
        parseInlinesInto(s, out)
        return out
    }

    private fun parseInlinesInto(s: String, out: MutableList<Inline>) {
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) { out.add(Inline.Text(sb.toString())); sb.clear() }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) {
                        flush(); out.add(Inline.Code(s.substring(i + 1, end))); i = end + 1
                    } else { sb.append(c); i++ }
                }
                c == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    val img = imageAt(s, i)
                    if (img != null) {
                        flush(); out.add(Inline.Image(img.first, img.second)); i = img.third
                    } else { sb.append(c); i++ }
                }
                c == '[' -> {
                    val link = linkAt(s, i)
                    if (link != null) {
                        flush(); out.add(Inline.Link(parseInlines(link.first), link.second)); i = link.third
                    } else { sb.append(c); i++ }
                }
                c == '*' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val end = s.indexOf("**", i + 2)
                    if (end > i + 1) {
                        flush(); out.add(Inline.Bold(parseInlines(s.substring(i + 2, end)))); i = end + 2
                    } else { sb.append(c); i++ }
                }
                c == '*' -> {
                    val end = s.indexOf('*', i + 1)
                    if (end > i) {
                        flush(); out.add(Inline.Italic(parseInlines(s.substring(i + 1, end)))); i = end + 1
                    } else { sb.append(c); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        flush()
    }

    /** `![alt](url)` at [start], or null. Returns (alt, url, index-after). */
    private fun imageAt(s: String, start: Int): Triple<String, String, Int>? {
        val close = s.indexOf("](", start + 2)
        if (close <= start) return null
        val urlEnd = s.indexOf(')', close + 2)
        if (urlEnd <= close) return null
        return Triple(s.substring(start + 2, close), s.substring(close + 2, urlEnd), urlEnd + 1)
    }

    /** `[text](url)` at [start], or null. Returns (text, url, index-after). */
    private fun linkAt(s: String, start: Int): Triple<String, String, Int>? {
        val close = s.indexOf("](", start + 1)
        if (close <= start) return null
        val urlEnd = s.indexOf(')', close + 2)
        if (urlEnd <= close) return null
        return Triple(s.substring(start + 1, close), s.substring(close + 2, urlEnd), urlEnd + 1)
    }

    private fun isFence(t: String) = t.startsWith("```")
    private fun isQuote(t: String) = t.startsWith(">")
    private fun isHeading(t: String) = Regex("^#{1,6} ").containsMatchIn(t)
    private fun isRule(t: String) = Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t)
    private fun isUnordered(t: String) = t.startsWith("- ") || t.startsWith("* ")
    private fun isOrdered(t: String) = Regex("^\\d+\\.\\s").containsMatchIn(t)
    private fun isStandaloneImage(t: String) = Regex("^!\\[[^\\]]*]\\([^)]*\\)$").matches(t)

    private fun isBlockStart(t: String) =
        t.isEmpty() || isFence(t) || isQuote(t) || isHeading(t) || isRule(t) ||
            isUnordered(t) || isOrdered(t) || isStandaloneImage(t)
}
