package mihon.source.hitomi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HitomiSearchQueryTest {
    @Test
    fun `plain prefix selection inserts an actual canonical tag`() {
        val token = HitomiSearchQuery.activeToken("jap")!!
        token.text shouldBe "jap"
        HitomiSearchQuery.replace("jap", token, "language:japanese") shouldBe "language:japanese "
    }

    @Test
    fun `namespace and mixed case are sent unchanged to the provider`() {
        HitomiSearchQuery.activeToken("lang")!!.text shouldBe "lang"
        val token = HitomiSearchQuery.activeToken("LANGUAGE:Ja")!!
        token.text shouldBe "LANGUAGE:Ja"
        HitomiSearchQuery.replace(token.query, token, "language:japanese") shouldBe "language:japanese "
    }

    @Test
    fun `only last token changes preserving earlier terms exclusions and whitespace`() {
        val query = "my title  -language:english\tJap"
        val token = HitomiSearchQuery.activeToken(query)!!
        HitomiSearchQuery.replace(query, token, "language:japanese") shouldBe
            "my title  -language:english\tlanguage:japanese "
    }

    @Test
    fun `active negative filter stays negative`() {
        val query = "type:manga -kor"
        val token = HitomiSearchQuery.activeToken(query)!!
        token.text shouldBe "kor"
        token.excluded shouldBe true
        HitomiSearchQuery.replace(query, token, "language:korean") shouldBe "type:manga -language:korean "
    }

    @Test
    fun `multiword tag is one token and never consumes the preceding title`() {
        val query = "ordinary words tag:full_co"
        val token = HitomiSearchQuery.activeToken(query)!!
        HitomiSearchQuery.replace(query, token, "tag:full color") shouldBe "ordinary words tag:full_color "
        HitomiSearchQuery.replace(query, token, "tag:full_color") shouldBe "ordinary words tag:full_color "
    }

    @Test
    fun `no automatic replacement occurs without an explicit suggestion selection`() {
        val query = "an ordinary title"
        val token = HitomiSearchQuery.activeToken(query)!!
        token.text shouldBe "title"
        token.query shouldBe query
    }

    @Test
    fun `trailing whitespace empty input urls and ids have no active suggestion token`() {
        listOf(null, "", " ", "jap ", "jap\t", "-", "--jap", "j", "123456", "https://hitomi.la/g/123.html")
            .forEach { HitomiSearchQuery.activeToken(it) shouldBe null }
    }

    @Test
    fun `old response cannot replace a token after user edits or submits another query`() {
        val old = HitomiSearchQuery.activeToken("jap")!!
        HitomiSearchQuery.replace("kor", old, "language:japanese") shouldBe "kor"
        HitomiSearchQuery.replace("title jap", old, "language:japanese") shouldBe "title jap"
        HitomiSearchQuery.replace("jap ", old, "language:japanese") shouldBe "jap "
    }

    @Test
    fun `invalid empty or already negated values do not change the query`() {
        val token = HitomiSearchQuery.activeToken("-jap")!!
        listOf("", " ", "-language:japanese").forEach {
            HitomiSearchQuery.replace(token.query, token, it) shouldBe token.query
        }
    }

    @Test
    fun `editing an earlier token preserves every following filter and places caret after its separator`() {
        val query = "jap type:manga -tag:full_color"
        val token = HitomiSearchQuery.activeToken(query, 3, 3)!!
        token.text shouldBe "jap"
        token.start shouldBe 0
        token.end shouldBe 3
        val result = HitomiSearchQuery.replaceAt(query, token, "language:japanese")!!
        result.query shouldBe "language:japanese type:manga -tag:full_color"
        result.caret shouldBe 18
        result.query.substring(result.caret) shouldBe "type:manga -tag:full_color"
    }

    @Test
    fun `middle prefix replaces the entire current term rather than retaining its old tail`() {
        val query = "language:jaBROKEN type:manga"
        val token = HitomiSearchQuery.activeToken(query, 11)!!
        token.text shouldBe "language:ja"
        token.end shouldBe 17
        token.caret shouldBe 11
        HitomiSearchQuery.replaceAt(query, token, "language:japanese") shouldBe
            HitomiSearchQuery.Replacement("language:japanese type:manga", 18)
    }

    @Test
    fun `earlier excluded term remains negative when selected from its middle`() {
        val query = "type:manga -langgarbage artist:test"
        val token = HitomiSearchQuery.activeToken(query, "type:manga -lang".length)!!
        token.text shouldBe "lang"
        token.excluded shouldBe true
        HitomiSearchQuery.replaceAt(query, token, "language:korean") shouldBe
            HitomiSearchQuery.Replacement("type:manga -language:korean artist:test", 28)
    }

    @Test
    fun `multiple spaces and tabs survive replacing a term in the middle`() {
        val query = "title\t\tjap   \ttype:manga"
        val token = HitomiSearchQuery.activeToken(query, 10)!!
        val result = HitomiSearchQuery.replaceAt(query, token, "language:japanese")!!
        result.query shouldBe "title\t\tlanguage:japanese   \ttype:manga"
        result.caret shouldBe 25
        result.query.substring(result.caret) shouldBe "  \ttype:manga"

        val tabQuery = "title jap\t\tartist:test"
        val tabToken = HitomiSearchQuery.activeToken(tabQuery, 9)!!
        HitomiSearchQuery.replaceAt(tabQuery, tabToken, "language:japanese") shouldBe
            HitomiSearchQuery.Replacement("title language:japanese\t\tartist:test", 24)
    }

    @Test
    fun `final term replacement normalizes multiword value and returns the appended-space caret`() {
        val query = "type:manga tag:fuBROKEN"
        val token = HitomiSearchQuery.activeToken(query, "type:manga tag:fu".length)!!
        val result = HitomiSearchQuery.replaceAt(query, token, "  tag:full\tcolor  ")!!
        result.query shouldBe "type:manga tag:full_color "
        result.caret shouldBe 26
        result.caret shouldBe result.query.length
    }

    @Test
    fun `ranges invalid selection bounds and whitespace positions do not request suggestions`() {
        val query = "alpha  beta"
        listOf(0 to 0, -1 to -1, 12 to 12, 2 to 4, 4 to 2, 3 to 99, 6 to 6, 7 to 7)
            .forEach { (start, end) -> HitomiSearchQuery.activeToken(query, start, end) shouldBe null }
        HitomiSearchQuery.activeToken("alpha\tbeta", 6) shouldBe null
        HitomiSearchQuery.activeToken(null, 2, 2) shouldBe null
        HitomiSearchQuery.activeToken("", 0, 0) shouldBe null
    }

    @Test
    fun `earlier token response is stale when any other part of the query changed`() {
        val query = "jap type:manga"
        val token = HitomiSearchQuery.activeToken(query, 3)!!
        listOf("jap type:doujinshi", "jap  type:manga", "jap type:manga ", "kor type:manga")
            .forEach { changed ->
                HitomiSearchQuery.replaceAt(changed, token, "language:japanese") shouldBe null
                HitomiSearchQuery.replace(changed, token, "language:japanese") shouldBe changed
            }
        HitomiSearchQuery.replaceAt(query, token.copy(end = query.length), "language:japanese") shouldBe null
        HitomiSearchQuery.replaceAt(query, token.copy(caret = 2), "language:japanese") shouldBe null
    }

    @Test
    fun `urls paths and numeric ids remain excluded when editing before later terms`() {
        listOf("https://hitomi.la/g/123.html", "/reader/123.html", "123456", "-123456")
            .forEach { term ->
                val query = "$term type:manga"
                HitomiSearchQuery.activeToken(query, term.length) shouldBe null
                HitomiSearchQuery.activeToken(query, 3) shouldBe null
            }
    }
}
