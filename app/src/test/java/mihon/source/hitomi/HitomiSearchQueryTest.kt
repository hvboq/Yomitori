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
}
