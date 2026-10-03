package io.github.th3rumbl3m4t0r.jmail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTest {
    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun plainUtf8() {
        val p = Mime.parse(res("m1.eml"))
        assertEquals("text/plain", p.type)
        assertTrue(Mime.text(p)!!.contains("obyčejný text — čau"))
        assertTrue(Mime.attachments(p).isEmpty())
    }

    @Test
    fun htmlOnlyBecomesText() {
        val t = Mime.text(Mime.parse(res("m2.eml")))!!
        assertTrue(t, t.contains("Hello Alice,"))
        assertTrue(t, t.contains("• one"))
        assertTrue(t, t.contains("a link <http://x.example/>"))
        assertFalse(t, t.contains("color:red"))
        assertFalse(t, t.contains("<b>") || t.contains("<p>"))
    }

    @Test
    fun mixedWithAttachment() {
        val p = Mime.parse(res("m3.eml"))
        assertEquals("multipart/mixed", p.type)
        assertEquals("See the attached table — 2 rows.", Mime.text(p)!!.trim())
        val a = Mime.attachments(p).single()
        assertEquals("table.csv", a.name)
        assertEquals("text/csv", a.type)
        assertEquals("col1,col2\n1,2\n3,4\n", String(a.bytes!!))
    }

    @Test
    fun signedPartIsCutRaw() {
        val (signed, sig) = Mime.rawSignedPart(res("m5.eml"))!!
        assertArrayEquals(res("signed.part"), signed)
        assertTrue(String(sig).startsWith("-----BEGIN PGP SIGNATURE-----"))
        assertNull(Mime.rawSignedPart(res("m1.eml")))
    }

    @Test
    fun writtenMessageParsesBack() {
        val att = Attachment("příloha.txt", "text/plain", "x\u0000y".toByteArray())
        val body = Mime.bodyEntity("Ahoj — řádek 1\nřádek 2 s mezerou na konci \n=rovnítko", listOf(att))
        val msg = Mime.message(
            Mime.Headers(from = Mime.address("Alice Ø", "alice@example.com"), to = listOf("bob@example.com"), subject = "Zpráva č. 1", messageId = Mime.messageId("example.com"), inReplyTo = "<x@y>"),
            body,
        )
        val s = String(msg)
        assertTrue(s, s.contains("Subject: =?UTF-8?B?"))
        assertTrue(s, s.contains("From: =?UTF-8?B?"))
        assertTrue(s, s.contains("In-Reply-To: <x@y>"))
        assertTrue(s, s.contains("Content-Type: multipart/mixed; boundary="))
        val p = Mime.parse(msg)
        assertEquals("Ahoj — řádek 1\r\nřádek 2 s mezerou na konci \r\n=rovnítko", Mime.text(p)!!.trimEnd('\r', '\n'))
        val a = Mime.attachments(p).single()
        assertEquals("příloha.txt", a.name)
        assertArrayEquals(att.bytes, a.bytes)
        // plain text only: no multipart
        assertEquals("text/plain", Mime.parse(Mime.message(Mime.Headers("a@b", listOf("c@d"), subject = "s", messageId = "<1@2>"), Mime.bodyEntity("hi", emptyList()))).type)
    }

    @Test
    fun pgpMimeEntities() {
        val enc = Mime.parse(Mime.message(Mime.Headers("a@b", listOf("c@d"), subject = "s", messageId = "<1@2>"), Mime.encryptedEntity("-----BEGIN PGP MESSAGE-----\r\nabc\r\n-----END PGP MESSAGE-----")))
        assertEquals("multipart/encrypted", enc.type)
        assertEquals("application/pgp-encrypted", enc.protocol)
        assertEquals("application/pgp-encrypted", enc.children[0].type)
        assertTrue(enc.children[1].text!!.startsWith("-----BEGIN PGP MESSAGE-----"))
        val inner = Mime.textEntity("hello")
        val signed = Mime.message(Mime.Headers("a@b", listOf("c@d"), subject = "s", messageId = "<1@2>"), Mime.signedEntity(inner, "-----BEGIN PGP SIGNATURE-----\r\nxyz\r\n-----END PGP SIGNATURE-----"))
        val (part, sig) = Mime.rawSignedPart(signed)!!
        assertArrayEquals(inner.trimEnd(), part.trimEnd())
        assertTrue(String(sig).contains("xyz"))
        assertEquals("application/pgp-signature", Mime.parse(signed).protocol)
    }

    private fun ByteArray.trimEnd(): ByteArray {
        var n = size
        while (n > 0 && (this[n - 1] == '\r'.code.toByte() || this[n - 1] == '\n'.code.toByte())) n--
        return copyOf(n)
    }

    @Test
    fun quotedPrintable() {
        assertEquals("a=3Db", Mime.quotedPrintable("a=b"))
        assertEquals("=C4=8D", Mime.quotedPrintable("č"))
        assertEquals("x=20\r\ny", Mime.quotedPrintable("x \ny"))
        val long = "a".repeat(100)
        val qp = Mime.quotedPrintable(long)
        assertTrue(qp.lines().all { it.length <= 76 })
        assertEquals(long, qp.replace("=\r\n", ""))
    }

    @Test
    fun addressesAndWords() {
        assertEquals("bob@example.com", Mime.address("", "bob@example.com"))
        assertEquals("\"Bob \\\"B\\\" Builder\" <bob@example.com>", Mime.address("Bob \"B\" Builder", "bob@example.com"))
        assertEquals("plain", Mime.encodeWord("plain"))
        assertTrue(Mime.encodeWord("čau").startsWith("=?UTF-8?B?"))
    }
}
