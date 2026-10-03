package io.github.th3rumbl3m4t0r.jmail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PgpTest {
    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
    private val aliceSec by lazy { Pgp.readSecretRings(res("alice.sec.asc")) }
    private val alicePub by lazy { Pgp.readPublicRings(res("alice.pub.asc")) }
    private val bobSec by lazy { Pgp.readSecretRings(res("bob.sec.asc")) }
    private val bobPub by lazy { Pgp.readPublicRings(res("bob.pub.asc")) }
    private val pass = "alicekey".toCharArray()

    @Test
    fun keyInfo() {
        val a = Pgp.info(aliceSec.single())
        assertEquals(listOf("Alice <alice@example.com>"), a.userIds)
        assertEquals(listOf("alice@example.com"), a.emails)
        assertTrue(a.secret)
        assertEquals(3, a.keyIds.size)
        assertEquals(40, a.fingerprint.length)
        assertEquals("C149 DCDC C9F8 03F4 299B 28EB 7C58 A39F A0FD 7A20", Pgp.pretty(a.fingerprint))
        assertEquals(null, a.expires)
        val b = Pgp.info(bobPub.single())
        assertEquals("rsa3072", b.algorithm)
        assertFalse(b.secret)
        // a secret key pasted as a public one still yields its public half
        assertEquals(a.fingerprint, Pgp.info(Pgp.readPublicRings(res("alice.sec.asc")).single()).fingerprint)
    }

    @Test
    fun passphrase() {
        assertTrue(Pgp.unlocks(aliceSec.single(), pass))
        assertFalse(Pgp.unlocks(aliceSec.single(), "nope".toCharArray()))
        assertTrue(Pgp.unlocks(bobSec.single(), null))
    }

    @Test
    fun decryptGpgMessageAndVerifyBobsSignature() {
        val root = Mime.parse(res("m4.eml"))
        assertEquals("multipart/encrypted", root.type)
        assertEquals("application/pgp-encrypted", root.protocol)
        val payload = root.children[1].bytes!!
        val d = Pgp.decrypt(payload, aliceSec, pass, bobPub)
        assertTrue(d.encrypted)
        assertArrayEquals(res("inner.mime"), d.data)
        assertEquals(1, d.sigs.size)
        assertTrue(d.sigs[0].valid)
        assertTrue(d.sigs[0].known)
        assertEquals(listOf("Bob <bob@example.com>"), d.sigs[0].userIds)
        // the decrypted entity parses like any MIME
        val inner = Mime.parse(d.data)
        assertEquals("See the attached table — 2 rows.", Mime.text(inner)!!.trim())
        assertEquals("table.csv", Mime.attachments(inner).single().name)
    }

    @Test
    fun unknownSignerAndWrongPassphrase() {
        val payload = Mime.parse(res("m4.eml")).children[1].bytes!!
        val d = Pgp.decrypt(payload, aliceSec, pass, emptyList())
        assertFalse(d.sigs[0].known)
        assertFalse(d.sigs[0].valid)
        try {
            Pgp.decrypt(payload, aliceSec, "nope".toCharArray(), bobPub)
            fail("wrong passphrase accepted")
        } catch (e: PgpError) {
            assertEquals("wrong passphrase", e.message)
        }
        try {
            Pgp.decrypt(payload, bobSec, null, bobPub)
            fail("decrypted with the wrong key")
        } catch (e: PgpError) {
            assertTrue(e.message!!.contains("not encrypted to"))
        }
    }

    @Test
    fun detachedSignature() {
        val raw = res("m5.eml")
        val (signed, sig) = Mime.rawSignedPart(raw)!!
        assertArrayEquals(res("signed.part"), signed)
        val r = Pgp.verifyDetached(signed, sig, bobPub)!!
        assertTrue(r.valid)
        assertTrue(r.known)
        val tampered = signed.copyOf().also { it[it.size - 3] = 'X'.code.toByte() }
        assertFalse(Pgp.verifyDetached(tampered, sig, bobPub)!!.valid)
    }

    @Test
    fun encryptAndSignThenDecryptWithTheOtherKey() {
        val plain = Mime.bodyEntity("Ahoj Bobe — tajné.", listOf(Attachment("x.txt", "text/plain", "data".toByteArray())))
        val armored = Pgp.encrypt(plain, bobPub + alicePub, aliceSec.single(), pass)
        assertTrue(armored.startsWith("-----BEGIN PGP MESSAGE-----"))
        File(System.getProperty("java.io.tmpdir"), "bc-enc.asc").writeText(armored)
        // bob reads it
        val d = Pgp.decrypt(armored.toByteArray(), bobSec, null, alicePub)
        assertArrayEquals(plain, d.data)
        assertTrue(d.sigs.single().valid)
        assertEquals("Alice <alice@example.com>", d.sigs.single().userIds.single())
        // alice reads her own copy
        val d2 = Pgp.decrypt(armored.toByteArray(), aliceSec, pass, alicePub)
        assertArrayEquals(plain, d2.data)
        // detached signing
        val sig = Pgp.signDetached(plain, bobSec.single(), null)
        assertTrue(Pgp.verifyDetached(plain, sig.toByteArray(), bobPub)!!.valid)
        File(System.getProperty("java.io.tmpdir"), "bc-sig.asc").writeText(sig)
        File(System.getProperty("java.io.tmpdir"), "bc-signed.bin").writeBytes(plain)
    }

    @Test
    fun encryptionAndSigningSubkeysArePicked() {
        val enc = Pgp.encryptionKey(alicePub.single())
        assertFalse(enc.isMasterKey)
        assertTrue(enc.isEncryptionKey)
        val sign = Pgp.signingKey(aliceSec.single())
        assertFalse(sign.isMasterKey)
        assertNotNull(Pgp.encryptionKey(bobPub.single()))
    }
}
