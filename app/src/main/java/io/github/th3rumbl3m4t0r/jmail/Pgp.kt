package io.github.th3rumbl3m4t0r.jmail

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPMarker
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignature
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRingCollection
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.Date

class PgpError(message: String) : Exception(message)

/**
 * OpenPGP with Bouncy Castle's lightweight API (no JCA provider, so it works next to the
 * copy of BC that Android ships). Keys come and go as rings; the app keeps them encoded.
 */
object Pgp {
    private val calc = BcKeyFingerprintCalculator()

    /** What the key list shows. */
    data class KeyInfo(
        val fingerprint: String,
        val keyId: Long,
        val userIds: List<String>,
        val created: Long,
        val expires: Long?,
        val secret: Boolean,
        val algorithm: String,
        /** Every key id in the ring (subkeys too): what a message is encrypted to or signed with. */
        val keyIds: List<Long>,
    ) {
        val emails: List<String> get() = userIds.mapNotNull { uid -> Regex("<([^>]+)>").find(uid)?.groupValues?.get(1)?.lowercase() ?: uid.takeIf { "@" in it }?.lowercase() }
        val expired: Boolean get() = expires != null && expires < System.currentTimeMillis()
    }

    /** One signature on a message, checked against the keys we know. */
    data class Sig(val keyId: Long, val valid: Boolean, val known: Boolean, val fingerprint: String?, val userIds: List<String>)

    class Decrypted(val data: ByteArray, val encrypted: Boolean, val sigs: List<Sig>)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /** "ABCD 1234 …" in groups of four, the way fingerprints are printed. */
    fun pretty(fingerprint: String) = fingerprint.chunked(4).joinToString(" ")

    private fun decoder(bytes: ByteArray): InputStream = PGPUtil.getDecoderStream(ByteArrayInputStream(bytes))

    /** Every public key ring in armored or binary text; a secret key ring yields its public half. */
    fun readPublicRings(bytes: ByteArray): List<PGPPublicKeyRing> {
        val out = ArrayList<PGPPublicKeyRing>()
        val f = BcPGPObjectFactory(decoder(bytes))
        var o = f.nextObject()
        while (o != null) {
            when (o) {
                is PGPPublicKeyRing -> out += o
                is PGPSecretKeyRing -> out += PGPPublicKeyRing(o.publicKeys.asSequence().toList())
            }
            o = f.nextObject()
        }
        if (out.isEmpty()) throw PgpError("no key in this text")
        return out
    }

    fun readSecretRings(bytes: ByteArray): List<PGPSecretKeyRing> {
        val out = ArrayList<PGPSecretKeyRing>()
        val f = BcPGPObjectFactory(decoder(bytes))
        var o = f.nextObject()
        while (o != null) {
            if (o is PGPSecretKeyRing) out += o
            o = f.nextObject()
        }
        if (out.isEmpty()) throw PgpError("no secret key in this text")
        return out
    }

    fun publicRing(encoded: ByteArray): PGPPublicKeyRing = BcPGPPublicKeyRingCollection(decoder(encoded)).keyRings.next()
    fun secretRing(encoded: ByteArray): PGPSecretKeyRing = BcPGPSecretKeyRingCollection(decoder(encoded)).keyRings.next()

    private fun algorithmName(key: PGPPublicKey): String = when (key.algorithm) {
        1, 2, 3 -> "rsa${key.bitStrength}"
        16, 20 -> "elgamal"
        17 -> "dsa"
        18 -> "ecdh"
        19 -> "ecdsa"
        22 -> "eddsa"
        25 -> "x25519"
        27 -> "ed25519"
        else -> "alg${key.algorithm}"
    }

    private fun expiresOf(key: PGPPublicKey): Long? = key.validSeconds.takeIf { it > 0 }?.let { key.creationTime.time + it * 1000 }

    fun info(ring: PGPPublicKeyRing): KeyInfo = info(ring.publicKey, ring.publicKeys.asSequence().toList(), secret = false)
    fun info(ring: PGPSecretKeyRing): KeyInfo = info(ring.publicKey, ring.publicKeys.asSequence().toList(), secret = true)

    private fun info(primary: PGPPublicKey, all: List<PGPPublicKey>, secret: Boolean) = KeyInfo(
        fingerprint = hex(primary.fingerprint),
        keyId = primary.keyID,
        userIds = primary.userIDs.asSequence().toList(),
        created = primary.creationTime.time,
        expires = expiresOf(primary),
        secret = secret,
        algorithm = algorithmName(primary),
        keyIds = all.map { it.keyID },
    )

    /** Armored export of a public ring. */
    fun armor(ring: PGPPublicKeyRing): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { it.write(ring.encoded) }
        return out.toString("UTF-8")
    }

    // ---- which key does what ----

    private fun flagsOf(key: PGPPublicKey): Int? {
        val sigs = if (key.isMasterKey) key.userIDs.asSequence().flatMap { key.getSignaturesForID(it)?.asSequence() ?: emptySequence() } else key.signatures.asSequence()
        return sigs.mapNotNull { it.hashedSubPackets?.keyFlags?.takeIf { f -> f != 0 } }.lastOrNull()
    }

    private fun usable(key: PGPPublicKey) = !key.hasRevocation() && (expiresOf(key)?.let { it > System.currentTimeMillis() } ?: true)

    /** The key to encrypt to: a subkey flagged for encryption, else whatever can encrypt. */
    fun encryptionKey(ring: PGPPublicKeyRing): PGPPublicKey {
        val keys = ring.publicKeys.asSequence().filter { it.isEncryptionKey && usable(it) }.toList()
        return keys.lastOrNull { (flagsOf(it) ?: 0) and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0 }
            ?: keys.lastOrNull { !it.isMasterKey } ?: keys.lastOrNull() ?: throw PgpError("key cannot encrypt")
    }

    /** The key to sign with: a subkey flagged for signing, else the primary. */
    fun signingKey(ring: PGPSecretKeyRing): PGPSecretKey {
        val keys = ring.secretKeys.asSequence().filter { it.isSigningKey && usable(it.publicKey) }.toList()
        return keys.lastOrNull { (flagsOf(it.publicKey) ?: 0) and KeyFlags.SIGN_DATA != 0 }
            ?: keys.lastOrNull { !it.isMasterKey } ?: keys.lastOrNull() ?: throw PgpError("key cannot sign")
    }

    private fun decryptor(passphrase: CharArray?) = BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(passphrase ?: CharArray(0))

    private fun privateKey(sk: PGPSecretKey, passphrase: CharArray?): PGPPrivateKey = try {
        sk.extractPrivateKey(decryptor(passphrase))
    } catch (e: PGPException) {
        throw PgpError("wrong passphrase")
    }

    /** True if the passphrase opens the ring (the signing key, as that is what we'll use). */
    fun unlocks(ring: PGPSecretKeyRing, passphrase: CharArray?): Boolean = try {
        signingKey(ring).extractPrivateKey(decryptor(passphrase))
        true
    } catch (e: PGPException) {
        false
    }

    private fun findPublic(rings: List<PGPPublicKeyRing>, keyId: Long): Pair<PGPPublicKeyRing, PGPPublicKey>? {
        for (r in rings) r.getPublicKey(keyId)?.let { return r to it }
        return null
    }

    // ---- reading ----

    /**
     * A PGP message (armored or binary) → its plaintext. [secrets] are ours (any of them may
     * be the one the message was encrypted to), [publics] the keys we know, for the signatures.
     * A signed-only message works too ([Decrypted.encrypted] = false).
     */
    fun decrypt(message: ByteArray, secrets: List<PGPSecretKeyRing>, passphrase: CharArray?, publics: List<PGPPublicKeyRing>): Decrypted {
        var factory: PGPObjectFactory = BcPGPObjectFactory(decoder(message))
        var o = factory.nextObject()
        if (o is PGPMarker) o = factory.nextObject()
        var encrypted = false
        var integrity: PGPPublicKeyEncryptedData? = null
        if (o is PGPEncryptedDataList) {
            encrypted = true
            var clear: InputStream? = null
            var sawOurs = false
            for (ed in o) {
                if (ed !is PGPPublicKeyEncryptedData) continue
                val sk = secrets.firstNotNullOfOrNull { it.getSecretKey(ed.keyID) } ?: continue
                sawOurs = true
                val priv = privateKey(sk, passphrase)
                clear = ed.getDataStream(BcPublicKeyDataDecryptorFactory(priv))
                integrity = ed
                break
            }
            if (clear == null) throw PgpError(if (sawOurs) "could not decrypt" else "not encrypted to any key of ours")
            factory = BcPGPObjectFactory(clear)
            o = factory.nextObject()
        }
        val out = ByteArrayOutputStream()
        val sigs = ArrayList<Sig>()
        readBody(o, factory, publics, out, sigs)
        integrity?.let { if (it.isIntegrityProtected && !it.verify()) throw PgpError("message was tampered with (integrity check failed)") }
        return Decrypted(out.toByteArray(), encrypted, sigs)
    }

    private fun readBody(first: Any?, factory: PGPObjectFactory, publics: List<PGPPublicKeyRing>, out: ByteArrayOutputStream, sigs: MutableList<Sig>) {
        var o = first
        var ops: PGPOnePassSignature? = null
        var opsRing: PGPPublicKeyRing? = null
        var opsKey: PGPPublicKey? = null
        while (o != null) {
            when (o) {
                is PGPCompressedData -> {
                    val f = BcPGPObjectFactory(o.dataStream)
                    readBody(f.nextObject(), f, publics, out, sigs)
                }
                is PGPOnePassSignatureList -> if (!o.isEmpty) {
                    ops = o[0]
                    findPublic(publics, ops.keyID)?.let { (r, k) ->
                        opsRing = r
                        opsKey = k
                        ops.init(BcPGPContentVerifierBuilderProvider(), k)
                    }
                }
                is PGPLiteralData -> {
                    val buf = ByteArray(1 shl 16)
                    val s = o.inputStream
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (opsKey != null) ops!!.update(buf, 0, n)
                    }
                }
                is PGPSignatureList -> if (!o.isEmpty) {
                    val sig = o[0]
                    val k = opsKey
                    if (ops != null && k != null && sig.keyID == ops.keyID) {
                        val ring = opsRing!!
                        sigs += Sig(sig.keyID, ops.verify(sig), true, hex(ring.publicKey.fingerprint), ring.publicKey.userIDs.asSequence().toList())
                    } else sigs += Sig(sig.keyID, false, false, null, emptyList())
                }
            }
            o = factory.nextObject()
        }
    }

    /** A detached signature over [data] (PGP/MIME multipart/signed). */
    fun verifyDetached(data: ByteArray, signature: ByteArray, publics: List<PGPPublicKeyRing>): Sig? {
        var f: PGPObjectFactory = BcPGPObjectFactory(decoder(signature))
        var o = f.nextObject()
        if (o is PGPCompressedData) {
            f = BcPGPObjectFactory(o.dataStream)
            o = f.nextObject()
        }
        val list = o as? PGPSignatureList ?: return null
        if (list.isEmpty) return null
        val sig = list[0]
        val (ring, key) = findPublic(publics, sig.keyID) ?: return Sig(sig.keyID, false, false, null, emptyList())
        sig.init(BcPGPContentVerifierBuilderProvider(), key)
        sig.update(data)
        return Sig(sig.keyID, sig.verify(), true, hex(ring.publicKey.fingerprint), ring.publicKey.userIDs.asSequence().toList())
    }

    // ---- writing ----

    /** [plain] encrypted to every ring in [to] (AES-256, integrity protected), signed by [signer] if given; armored. */
    fun encrypt(plain: ByteArray, to: List<PGPPublicKeyRing>, signer: PGPSecretKeyRing?, passphrase: CharArray?, filename: String = ""): String {
        if (to.isEmpty()) throw PgpError("nobody to encrypt to")
        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(out)
        val encGen = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true).setSecureRandom(SecureRandom()),
        )
        for (r in to) encGen.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(encryptionKey(r)))
        val encOut = encGen.open(armored, ByteArray(1 shl 16))
        val compGen = PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP)
        val compOut = compGen.open(encOut)
        val sigGen = signer?.let { signatureGenerator(it, passphrase) }
        sigGen?.generateOnePassVersion(false)?.encode(compOut)
        val litGen = PGPLiteralDataGenerator()
        val litOut = litGen.open(compOut, PGPLiteralData.BINARY, filename, Date(), ByteArray(1 shl 16))
        litOut.write(plain)
        sigGen?.update(plain)
        litOut.close()
        litGen.close()
        sigGen?.generate()?.encode(compOut)
        compOut.close()
        compGen.close()
        encOut.close()
        encGen.close()
        armored.close()
        return out.toString("UTF-8")
    }

    /** An armored detached signature over [data] (for multipart/signed). */
    fun signDetached(data: ByteArray, signer: PGPSecretKeyRing, passphrase: CharArray?): String {
        val gen = signatureGenerator(signer, passphrase)
        gen.update(data)
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { gen.generate().encode(it) }
        return out.toString("UTF-8")
    }

    private fun signatureGenerator(signer: PGPSecretKeyRing, passphrase: CharArray?): PGPSignatureGenerator {
        val sk = signingKey(signer)
        val priv = privateKey(sk, passphrase)
        val gen = PGPSignatureGenerator(BcPGPContentSignerBuilder(sk.publicKey.algorithm, HashAlgorithmTags.SHA256), sk.publicKey)
        gen.init(PGPSignature.BINARY_DOCUMENT, priv)
        signer.publicKey.userIDs.asSequence().firstOrNull()?.let { uid ->
            val sub = PGPSignatureSubpacketGenerator()
            sub.addSignerUserID(false, uid)
            gen.setHashedSubpackets(sub.generate())
        }
        return gen
    }
}
