package mihon.translation

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.data.translation.PageIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Android Uri identity semantics only; no provider, SAF picker, permissions, storage or native inference. */
@RunWith(AndroidJUnit4::class)
class PageIdentityTest {
    @Test(timeout = 5_000)
    fun directAndDifferentTreeGrantsIdentifyTheSameOpaqueDocument() {
        val documentIds = listOf("page-001", "volume:book/chapter/001.png", "a/../b", ".", "literal%2Fpage")
        for (documentId in documentIds) {
            val expected = PageIdentity.Document(AUTHORITY, documentId)
            val aliases = listOf(
                DocumentsContract.buildDocumentUri(AUTHORITY, documentId),
                treeDocument("grant-a", documentId),
                treeDocument("grant-b:folder/nested", documentId),
            )
            assertEquals("The fixture must contain three different URI spellings", 3, aliases.toSet().size)
            for (alias in aliases) {
                assertEquals("Grant roots do not change this document's identity: $alias", expected, identify(alias))
            }
        }
    }

    @Test(timeout = 5_000)
    fun authorityCaseAndOpaqueDocumentIdDifferencesRemainDistinct() {
        val authorityVariants = listOf(AUTHORITY, "Other.$AUTHORITY", AUTHORITY.uppercase())
        val documentIds = listOf("page", "Page", "a/../page", "a/./page", "./page", "/page", "page/")
        val identities = authorityVariants.flatMap { authority ->
            documentIds.map { documentId ->
                val uri = DocumentsContract.buildDocumentUri(authority, documentId)
                identify(uri).also { identity ->
                    assertEquals(
                        "Provider IDs are opaque: $uri",
                        PageIdentity.Document(authority, documentId),
                        identity,
                    )
                }
            }
        }
        assertEquals("Do not normalize authority case, document case, slashes or dot segments", 21, identities.size)
        assertEquals("Different provider identities must not collapse", identities.size, identities.toSet().size)
    }

    @Test(timeout = 5_000)
    fun encodedSlashSpellingsMatchButLiteralPercentIsNotDecodedTwice() {
        val slashUpper = Uri.parse("content://$AUTHORITY/document/folder%2Fpage")
        val slashLower = Uri.parse("content://$AUTHORITY/tree/root/document/folder%2fpage")
        val literalPercent = DocumentsContract.buildDocumentUri(AUTHORITY, "folder%2Fpage")
        val literalPercentLower = DocumentsContract.buildDocumentUri(AUTHORITY, "folder%2fpage")

        assertEquals(PageIdentity.Document(AUTHORITY, "folder/page"), identify(slashUpper))
        assertEquals("Hex spelling does not change the decoded ID", identify(slashUpper), identify(slashLower))
        assertEquals(PageIdentity.Document(AUTHORITY, "folder%2Fpage"), identify(literalPercent))
        assertEquals(PageIdentity.Document(AUTHORITY, "folder%2fpage"), identify(literalPercentLower))
        assertFalse("A literal percent sequence is not a slash", identify(slashUpper) == identify(literalPercent))
        assertFalse("Literal percent sequences retain case", identify(literalPercent) == identify(literalPercentLower))
    }

    @Test(timeout = 5_000)
    fun malformedDocumentPathsPreserveTheirExactUriInsteadOfDroppingEmptySegments() {
        val paths = listOf(
            "/document",
            "/document/",
            "/document//page",
            "/document/page/",
            "//document/page",
            "/document/page/extra",
            "/document/page/children",
            "/tree/root",
            "/tree//document/page",
            "/tree/root//document/page",
            "/tree/root/document//page",
            "/tree/root/document/page/",
            "/tree/root/document/page/extra",
            "/document/bad%",
            "/document/bad%2",
            "/document/bad%GG",
            "/document/bad%FF",
            "/document/bad%C3%28",
            "/document/bad%C0%AF",
        )
        for (path in paths) assertExact("content://$AUTHORITY$path")
    }

    @Test(timeout = 5_000)
    fun queryAndFragmentPresenceIncludingEmptyValuesKeepsExactUriIdentity() {
        val bases = listOf(
            "content://$AUTHORITY/document/page",
            "content://$AUTHORITY/tree/root/document/page",
        )
        val suffixes = listOf("?", "?mode=preview", "#", "#section", "?#", "?mode=preview#section")
        for (base in bases) {
            for (suffix in suffixes) assertExact(base + suffix)
        }
    }

    @Test(timeout = 5_000)
    fun fileOtherSchemesAndNonDocumentContentUrisPreserveExactIdentity() {
        val values = listOf(
            "file:///private/page.png",
            "file:///private/../page.png",
            "https://$AUTHORITY/document/page",
            "CONTENT://$AUTHORITY/document/page",
            "content:///document/page",
            "content://$AUTHORITY/other/page",
            "content://$AUTHORITY/document",
            "content://$AUTHORITY/tree/root",
            "relative/document/page",
            "",
        )
        for (value in values) assertExact(value)
    }

    private fun treeDocument(treeId: String, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(
        DocumentsContract.buildTreeDocumentUri(AUTHORITY, treeId),
        documentId,
    )

    private fun identify(uri: Uri): PageIdentity = PageIdentity.fromUri(uri)

    private fun assertExact(value: String) {
        val uri = Uri.parse(value)
        assertEquals(
            "Unsupported URI must retain its exact spelling: $value",
            PageIdentity.ExactUri(value),
            identify(uri),
        )
    }

    private companion object {
        const val AUTHORITY = "test.yomitori.identity.documents"
    }
}
