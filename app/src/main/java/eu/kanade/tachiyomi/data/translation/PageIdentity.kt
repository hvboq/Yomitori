package eu.kanade.tachiyomi.data.translation

import android.net.Uri

/** Process-local identity only; it neither changes a URI nor obtains additional access to a document. */
internal sealed interface PageIdentity {
    data class Document(val authority: String, val documentId: String) : PageIdentity

    data class ExactUri(val value: String) : PageIdentity

    companion object {
        private val malformedEscape = Regex("%(?![0-9a-fA-F]{2})")

        fun fromUri(uri: Uri): PageIdentity {
            val exact = ExactUri(uri.toString())
            if (uri.scheme != "content" || !uri.isHierarchical) return exact
            if (uri.encodedQuery != null || uri.encodedFragment != null) return exact

            val authority = uri.authority?.takeIf { it.isNotEmpty() } ?: return exact
            val encodedAuthority = uri.encodedAuthority ?: return exact
            val encodedPath = uri.encodedPath ?: return exact
            if (malformedEscape.containsMatchIn(encodedAuthority) || malformedEscape.containsMatchIn(encodedPath)) {
                return exact
            }
            val segments = uri.pathSegments
            // Uri.pathSegments discards empty segments. Do not normalize extra or trailing slashes.
            if (!encodedPath.startsWith('/') || encodedPath.count { it == '/' } != segments.size) return exact
            // Uri replaces invalid UTF-8 escapes. Keep such URIs distinct rather than merging replacements.
            if ('\uFFFD' in authority || segments.any { '\uFFFD' in it }) return exact

            val documentId = when {
                segments.size == 2 && segments[0] == "document" -> segments[1]
                segments.size == 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
                else -> return exact
            }
            // Document IDs are opaque. pathSegments has already decoded each segment once.
            return Document(authority, documentId)
        }
    }
}
