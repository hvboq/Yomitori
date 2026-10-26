package mihon.source.hitomi

/** Match the editor's Char.isWhitespace token boundaries without changing caller-specific control checks. */
internal object HitomiSearchText {
    fun normalizeWhitespace(value: String): String = buildString(value.length) {
        value.forEach { append(if (it.isWhitespace()) ' ' else it) }
    }

    fun terms(value: String): List<String> = normalizeWhitespace(value).split(' ').filter(String::isNotEmpty)

    fun canonicalTag(value: String): String = terms(value).joinToString("_")
}
