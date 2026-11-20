package com.yatesv.contacthub.data

/** Finds tappable things in message text: links, emails, phone numbers and map coordinates. */
object Links {
    enum class Kind { URL, EMAIL, PHONE, GEO }

    data class Link(val start: Int, val end: Int, val kind: Kind, val text: String) {
        /** What to open: a full https:// URL, mailto:, tel: digits, or geo:. */
        val target: String
            get() = when (kind) {
                Kind.URL -> if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text else "https://$text"
                Kind.EMAIL -> "mailto:$text"
                Kind.PHONE -> "tel:" + text.filter { it.isDigit() || it == '+' }
                Kind.GEO -> "geo:" + text.replace(" ", "") + "?q=" + text.replace(" ", "")
            }
    }

    private const val TLDS = "com|net|org|io|co|us|gov|edu|app|dev|me|tv|gg|ly|be|to|info|biz|xyz|ai|shop|store|link|page|site|online|live|news|ca|uk|de|fm|so|sh|gl"
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val url = Regex(
        "(?i)(?<![@\\w.])(?:(?:https?://|www\\.)[^\\s<>\"]+|[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.(?:$TLDS)\\b(?:[/?#][^\\s<>\"]*)?)")
    private val phone = Regex("(?<![\\w/+.])(?:\\+?1[\\s.-]?)?(?:\\(\\d{3}\\)\\s?|\\d{3}[\\s.-]?)\\d{3}[\\s.-]?\\d{4}(?![\\w])|(?<![\\w/])\\+\\d{9,14}(?!\\w)")
    private val geo = Regex("(?<![\\d.])-?\\d{1,2}\\.\\d{3,}\\s*,\\s*-?\\d{1,3}\\.\\d{3,}(?![\\d.])")

    /** Drops punctuation that ends a sentence, like "see example.com." or "(example.com)". */
    private fun trimEnd(s: String): String {
        var t = s
        while (t.isNotEmpty()) {
            val c = t.last()
            t = when {
                c in ".,!?;:'\"" -> t.dropLast(1)
                c == ')' && t.count { it == '(' } < t.count { it == ')' } -> t.dropLast(1)
                c == ']' && t.count { it == '[' } < t.count { it == ']' } -> t.dropLast(1)
                else -> return t
            }
        }
        return t
    }

    fun find(text: String): List<Link> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Link>()
        fun free(s: Int, e: Int) = out.none { s < it.end && e > it.start }
        fun add(m: MatchResult, kind: Kind, trim: Boolean) {
            val t = if (trim) trimEnd(m.value) else m.value
            if (t.isEmpty()) return
            val s = m.range.first; val e = s + t.length
            if (free(s, e)) out += Link(s, e, kind, t)
        }
        email.findAll(text).forEach { add(it, Kind.EMAIL, true) }
        url.findAll(text).forEach { m ->
            // "1.5" or "3.50" aren't links
            if (m.value.none { it.isLetter() }) return@forEach
            add(m, Kind.URL, true)
        }
        geo.findAll(text).forEach { m ->
            val (a, b) = m.value.split(',').map { it.trim().toDouble() }
            if (a in -90.0..90.0 && b in -180.0..180.0) add(m, Kind.GEO, false)
        }
        phone.findAll(text).forEach { add(it, Kind.PHONE, false) }
        return out.sortedBy { it.start }
    }

    /** First web link, for the preview card. */
    fun firstUrl(text: String): String? = find(text).firstOrNull { it.kind == Kind.URL }?.target
}
