package io.github.th3rumbl3m4t0r.jmail

/** HTML mail → plain text, good enough to read: blocks become lines, links show their target. No WebView here. */
object Html {
    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "copy" to "©", "reg" to "®",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "euro" to "€",
    )

    fun unescape(s: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> entities[e.lowercase()] ?: m.value
        }
    }

    fun toText(html: String): String {
        var s = html
        s = Regex("(?is)<(script|style|head|title)[^>]*>.*?</\\1>").replace(s, "")
        s = Regex("(?is)<!--.*?-->").replace(s, "")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = Regex("(?i)</(p|div|h[1-6]|tr|table|ul|ol|blockquote|pre|section|article|header|footer)>").replace(s, "\n")
        s = Regex("(?i)<(p|div|h[1-6]|tr|table|ul|ol|blockquote|pre|section|article|header|footer)[^>]*>").replace(s, "\n")
        s = Regex("(?i)<li[^>]*>").replace(s, "\n• ")
        s = Regex("(?i)<hr[^>]*>").replace(s, "\n----\n")
        s = Regex("(?i)</t[dh]>").replace(s, "\t")
        s = Regex("(?is)<a\\s[^>]*href\\s*=\\s*[\"']?([^\"' >]+)[^>]*>(.*?)</a>").replace(s) { m ->
            val href = m.groupValues[1]
            val label = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            if (label.isEmpty() || label == href || href.startsWith("mailto:") && href.endsWith(label)) href
            else if (href.startsWith("#") || href.startsWith("javascript:")) label else "$label \u0001$href\u0002"
        }
        s = Regex("(?s)<[^>]+>").replace(s, "")
        s = s.replace('\u0001', '<').replace('\u0002', '>')
        s = unescape(s)
        s = s.replace("\r\n", "\n").replace('\u00a0', ' ')
        s = Regex("[ \\t]+\\n").replace(s, "\n")
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }
}
