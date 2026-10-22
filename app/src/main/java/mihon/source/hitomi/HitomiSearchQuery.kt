package mihon.source.hitomi

/** UI token editing only. Hitomi uses whitespace between terms and underscores inside a tag. */
internal object HitomiSearchQuery {
    data class ActiveToken(val query: String, val start: Int, val text: String, val excluded: Boolean)

    fun activeToken(query: String?): ActiveToken? {
        if (query.isNullOrEmpty() || query.last().isWhitespace()) return null
        val start = query.indexOfLast(Char::isWhitespace) + 1
        val raw = query.substring(start)
        val excluded = raw.startsWith('-')
        val text = if (excluded) raw.drop(1) else raw
        // A gallery URL/id is also a valid search, but it is not an autocomplete tag prefix.
        if (text.length < 2 || text.startsWith('-') || text.all(Char::isDigit) ||
            "://" in text || text.startsWith('/')
        ) {
            return null
        }
        return ActiveToken(query, start, text, excluded)
    }

    /** Reject stale selections and preserve every preceding term/space, including negative filters. */
    fun replace(query: String, token: ActiveToken, value: String): String {
        if (query != token.query || activeToken(query) != token) return query
        val canonical = value.trim().replace(Regex("\\s+"), "_")
        if (canonical.isEmpty() || canonical.startsWith('-')) return query
        return query.substring(0, token.start) + (if (token.excluded) "-" else "") + canonical + " "
    }
}
