package mihon.source.hitomi

/** UI token editing only. Hitomi uses whitespace between terms and underscores inside a tag. */
internal object HitomiSearchQuery {
    data class ActiveToken(
        val query: String,
        val start: Int,
        val text: String,
        val excluded: Boolean,
        val end: Int,
        val caret: Int,
    )

    data class Replacement(val query: String, val caret: Int)

    fun activeToken(
        query: String?,
        selectionStart: Int = query?.length ?: 0,
        selectionEnd: Int = selectionStart,
    ): ActiveToken? {
        if (query.isNullOrEmpty() || selectionStart != selectionEnd || selectionStart !in 1..query.length ||
            query[selectionStart - 1].isWhitespace()
        ) {
            return null
        }
        val start = query.substring(0, selectionStart).indexOfLast(Char::isWhitespace) + 1
        var end = selectionStart
        while (end < query.length && !query[end].isWhitespace()) end++
        val raw = query.substring(start, end)
        val excluded = raw.startsWith('-')
        val prefix = query.substring(start, selectionStart)
        val text = if (excluded) prefix.drop(1) else prefix
        // A gallery URL/id is also a valid search, but it is not an autocomplete tag prefix.
        if (text.length < 2 || text.startsWith('-') || text.all(Char::isDigit) ||
            "://" in raw || raw.startsWith('/')
        ) {
            return null
        }
        return ActiveToken(query, start, text, excluded, end, selectionStart)
    }

    /** Reject stale selections and preserve every preceding term/space, including negative filters. */
    fun replace(query: String, token: ActiveToken, value: String): String {
        return replaceAt(query, token, value)?.query ?: query
    }

    /** Replace the complete term at the caret, preserving all surrounding terms and separators. */
    fun replaceAt(query: String, token: ActiveToken, value: String): Replacement? {
        if (query != token.query || activeToken(query, token.caret) != token) return null
        val canonical = value.trim().replace(Regex("\\s+"), "_")
        if (canonical.isEmpty() || canonical.startsWith('-')) return null
        val inserted = (if (token.excluded) "-" else "") + canonical
        val suffix = query.substring(token.end)
        val separator = if (suffix.firstOrNull()?.isWhitespace() == true) "" else " "
        val replacement = query.substring(0, token.start) + inserted + separator + suffix
        return Replacement(replacement, token.start + inserted.length + 1)
    }
}
