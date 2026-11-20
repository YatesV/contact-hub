package com.yatesv.contacthub.data

/**
 * Replies: the quoted message travels as a first line starting with "> ", so it works over plain
 * SMS too (the other person sees what you replied to, and Orbit shows it as a quote block).
 */
object Quote {
    /** Short one-line version of the message you're replying to. */
    fun snippet(original: String, max: Int = 70): String {
        val oneLine = split(original).second.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= max) oneLine else oneLine.take(max - 1).trimEnd() + "…"
    }

    fun make(original: String, reply: String, attachmentLabel: String = "📷 Photo"): String =
        "> " + snippet(original).ifBlank { attachmentLabel } + "\n" + reply.trim()

    /** (quote or null, the rest of the message). */
    fun split(body: String): Pair<String?, String> {
        if (!body.startsWith("> ")) return null to body
        val nl = body.indexOf('\n')
        if (nl < 0) return null to body
        val q = body.substring(2, nl).trim()
        val rest = body.substring(nl + 1)
        return if (q.isEmpty() || rest.isBlank()) null to body else q to rest
    }
}

/** A contact card file (.vcf) other phones can import. */
object VCard {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")

    fun build(name: String, phones: List<Pair<String, String>>, emails: List<String> = emptyList()): String = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        append("FN:").append(esc(name)).append("\r\n")
        val parts = name.trim().split(Regex("\\s+"))
        append("N:").append(esc(parts.drop(1).joinToString(" "))).append(";").append(esc(parts.firstOrNull().orEmpty())).append(";;;\r\n")
        phones.forEach { (type, num) -> append("TEL;TYPE=").append(type.uppercase().ifBlank { "CELL" }).append(":").append(num).append("\r\n") }
        emails.forEach { append("EMAIL:").append(esc(it)).append("\r\n") }
        append("END:VCARD\r\n")
    }
}
