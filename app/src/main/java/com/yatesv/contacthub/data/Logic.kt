package com.yatesv.contacthub.data

/**
 * Pure helpers (no Android calls) so they can be unit tested.
 */
object Phone {
    /** "+1 (337) 555-0101" -> "3375550101". Short codes / names ("AMAZON") stay as lowercase letters+digits. */
    fun norm(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.any { it.isLetter() } && !s.startsWith("tel:")) return s.lowercase().filter { it.isLetterOrDigit() }
        val digits = s.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }

    /** Stable key for a conversation: its numbers, normalized and sorted. */
    fun convKey(addresses: Collection<String>): String =
        addresses.map { norm(it) }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")

    fun isGroupKey(key: String) = key.contains(',') || key.startsWith("g:")

    fun pretty(raw: String): String {
        val d = norm(raw)
        return if (d.length == 10 && d.all { it.isDigit() }) "(${d.substring(0, 3)}) ${d.substring(3, 6)}-${d.substring(6)}" else raw
    }
}

data class ParsedReaction(val emoji: String, val target: String, val removed: Boolean)

object Reactions {
    val picker = listOf("❤️", "😂", "👍", "👎", "😮", "😢", "😡", "‼️", "❓")

    private val verbs = linkedMapOf(
        "Laughed at" to "😂",
        "Liked" to "👍",
        "Loved" to "❤️",
        "Disliked" to "👎",
        "Emphasized" to "‼️",
        "Questioned" to "❓",
    )
    private val removedNouns = mapOf(
        "a like" to "👍", "a heart" to "❤️", "a dislike" to "👎", "a laugh" to "😂",
        "an exclamation" to "‼️", "a question mark" to "❓",
    )
    private const val Q = "[\"“”']"

    /**
     * Reads the text that iPhones and some Android apps send to SMS contacts when they react,
     * e.g. `Liked “hey”` or `Reacted 😂 to “hey”`. Returns null for normal messages.
     */
    fun parse(text: String): ParsedReaction? {
        val t = text.trim()
        for ((verb, emoji) in verbs) {
            Regex("^$verb $Q(.+)$Q$", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
                return ParsedReaction(emoji, it.groupValues[1], false)
            }
        }
        Regex("^Removed (.+?) from $Q(.+)$Q$", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
            val noun = it.groupValues[1]
            val emoji = removedNouns[noun] ?: noun.removePrefix("a ").removePrefix("an ")
            return ParsedReaction(emoji, it.groupValues[2], true)
        }
        Regex("^Reacted (\\S+) to $Q(.+)$Q$", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
            return ParsedReaction(it.groupValues[1], it.groupValues[2], false)
        }
        Regex("^(\\S{1,8}) to $Q(.+)$Q$", RegexOption.DOT_MATCHES_ALL).find(t)?.let {
            val e = it.groupValues[1]
            if (e.none { c -> c.isLetterOrDigit() }) return ParsedReaction(e, it.groupValues[2], false)
        }
        return null
    }

    /** Does [body] look like the message a reaction quoted? Quotes are often cut short with "…". */
    fun matches(target: String, body: String): Boolean {
        val tg = target.trim()
        val b = body.trim()
        if (tg == b) return true
        val cut = tg.removeSuffix("…").removeSuffix("...").trim()
        return cut.length >= 8 && b.startsWith(cut)
    }
}

object Otp {
    private val keyword = Regex("(?i)(code|verif|otp|passcode|pin|login|sign.?in|2fa|security)")
    private val number = Regex("(?<!\\d)(?<!\\d-)(\\d{4,8})(?!\\d)(?!-\\d)")
    private val split = Regex("(?<!\\d)(\\d{3})[- ](\\d{3})(?!\\d)")

    fun find(text: String): String? {
        if (!keyword.containsMatchIn(text)) return null
        split.find(text)?.let { return it.groupValues[1] + it.groupValues[2] }
        return number.findAll(text).map { it.groupValues[1] }.firstOrNull { !(it.length == 4 && it.startsWith("20")) }
            ?: number.find(text)?.groupValues?.get(1)
    }
}

enum class Category(val label: String) { ALL("All"), PERSONAL("Personal"), GROUPS("Groups"), CODES("Codes"), DELIVERIES("Deliveries"), SPAM("Spam") }

object Categorize {
    private val delivery = Regex("(?i)(out for delivery|delivered|tracking|shipped|your package|your order|ups|fedex|usps|dhl|amazon|doordash|uber eats|instacart)")
    private val spammy = Regex("(?i)(congratulations|you('ve)? won|claim (your|now)|free gift|act now|limited time|click (here|below)|reply stop|txt stop|unsubscribe)")
    private val link = Regex("(?i)(https?://|www\\.|bit\\.ly)")

    fun of(key: String, snippet: String, isContact: Boolean, blocked: Boolean = false): Category {
        if (blocked) return Category.SPAM
        if (Phone.isGroupKey(key)) return Category.GROUPS
        if (Otp.find(snippet) != null) return Category.CODES
        if (!isContact && delivery.containsMatchIn(snippet)) return Category.DELIVERIES
        if (!isContact && spammy.containsMatchIn(snippet) && link.containsMatchIn(snippet)) return Category.SPAM
        return Category.PERSONAL
    }
}

enum class Social(val label: String, val pkg: String) {
    INSTAGRAM("Instagram", "com.instagram.android"),
    SNAPCHAT("Snapchat", "com.snapchat.android"),
    TIKTOK("TikTok", "com.zhiliaoapp.musically"),
}

object Socials {
    /** Which app a shared link belongs to. */
    fun detect(text: String): Social? {
        val t = text.lowercase()
        return when {
            "instagram.com" in t || "instagr.am" in t -> Social.INSTAGRAM
            "snapchat.com" in t -> Social.SNAPCHAT
            "tiktok.com" in t -> Social.TIKTOK
            else -> null
        }
    }

    private val url = Regex("https?://\\S+")

    /**
     * Turns what the user typed or shared ("@name", "name", or a profile link) into what we store:
     * the username, or the whole link when it's a short link we can't read the username from.
     */
    fun clean(social: Social, input: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return ""
        val link = url.find(raw)?.value?.trimEnd('.', ',', ')')
        if (link == null) return raw.removePrefix("@").substringBefore(' ').trim()
        val path = link.substringAfter("://").substringAfter('/').substringBefore('?').trimEnd('/')
        val parts = path.split('/').filter { it.isNotEmpty() }
        val user = when (social) {
            Social.INSTAGRAM -> parts.firstOrNull()?.takeIf { it !in setOf("p", "reel", "reels", "stories", "explore") }
            Social.SNAPCHAT -> when {
                parts.firstOrNull() == "add" -> parts.getOrNull(1)
                parts.firstOrNull()?.startsWith("@") == true -> parts.first().removePrefix("@")
                else -> null
            }
            Social.TIKTOK -> parts.firstOrNull { it.startsWith("@") }?.removePrefix("@")
        }
        return user ?: link
    }

    /** Link that opens their profile in the app (or the browser if the app isn't installed). */
    fun profileUrl(social: Social, stored: String): String {
        if (stored.startsWith("http")) return stored
        return when (social) {
            Social.INSTAGRAM -> "https://instagram.com/_u/$stored"
            Social.SNAPCHAT -> "https://www.snapchat.com/add/$stored"
            Social.TIKTOK -> "https://www.tiktok.com/@$stored"
        }
    }
}

object Stats {
    /** Days in a row (ending today or yesterday) that have at least one message. */
    fun streak(epochDays: Set<Long>, today: Long): Int {
        var day = if (today in epochDays) today else today - 1
        var n = 0
        while (day in epochDays) { n++; day-- }
        return n
    }
}

object Geo {
    /** Distance in miles between two lat/lng points. */
    fun miles(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} hr ago"
            else -> "${s / 86400} d ago"
        }
    }
}
