package io.github.th3rumbl3m4t0r.jmail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlAndModelTest {
    @Test
    fun htmlToText() {
        val t = Html.toText("<html><head><style>p{color:red}</style></head><body><p>Hi <b>you</b>,</p><p>line&nbsp;1<br>line 2</p><ul><li>a</li><li>b &amp; c</li></ul><a href=\"http://x/\">x</a> <a href=\"http://y/\">http://y/</a></body></html>")
        assertEquals("Hi you,\n\nline 1\nline 2\n\n• a\n• b & c\nx <http://x/> http://y/", t)
        assertEquals("€ < > \"", Html.unescape("&euro; &lt; &gt; &#34;"))
    }

    @Test
    fun aliasDisplayName() {
        // the identity's "name" is the primary address (Stalwart's default): the alias stands alone
        assertEquals("alias1@example.net", Senders.displayName("me@example.net", "me@example.net", "alias1@example.net"))
        assertEquals("alias1@example.net <alias1@example.net>", Address(Senders.displayName("me@example.net", "me@example.net", "alias1@example.net"), "alias1@example.net").full)
        // a real name is kept on every alias
        assertEquals("Alice", Senders.displayName("Alice", "alice@example.com", "shop@example.org"))
        assertEquals("x@y.z", Senders.displayName(null, "x@y.z", "x@y.z"))
        assertEquals("x@y.z", Senders.displayName("", "p@y.z", "x@y.z"))
    }

    @Test
    fun addresses() {
        assertEquals(Address("Bob", "bob@example.com"), Address.parse("Bob <bob@example.com>"))
        assertEquals(Address("Bob B", "bob@example.com"), Address.parse("\"Bob B\" <bob@example.com>"))
        assertEquals(Address(null, "bob@example.com"), Address.parse(" bob@example.com ,"))
        assertNull(Address.parse("not an address"))
        assertEquals(2, Address.parseList("a@b, \"C\" <c@d>;").size)
        assertEquals("Bob <bob@example.com>", Address("Bob", "bob@example.com").full)
    }

    @Test
    fun emailFromJson() {
        val o = Json.parseToJsonElement(
            """{"id":"e1","blobId":"b1","threadId":"t1","mailboxIds":{"a":true,"b":false},"keywords":{"${'$'}seen":true,"${'$'}flagged":true},
               "hasAttachment":true,"from":[{"name":"Bob","email":"bob@example.com"}],"to":[{"name":null,"email":"alice@example.com"}],
               "subject":"Hi","receivedAt":"2026-10-01T17:17:32Z","sentAt":null,"size":317,"preview":"Ahoj"}""",
        ).jsonObject
        val e = Jmap.email(o)
        assertEquals(setOf("a"), e.mailboxIds)
        assertTrue(e.seen)
        assertTrue(e.flagged)
        assertFalse(e.draft)
        assertEquals("Bob", e.from.single().short)
        assertEquals("alice@example.com", e.to.single().short)
        assertEquals(1790875052000L, e.receivedAt)
        assertNull(e.sentAt)
        assertEquals("/jmap/download/{accountId}/{blobId}/{name}?accept={type}", Jmap.pathOf("http://localhost:8090/jmap/download/{accountId}/{blobId}/{name}?accept={type}"))
        assertEquals("https://mail.example.net/", Jmap.baseOf("mail.example.net").toString())
    }
}
