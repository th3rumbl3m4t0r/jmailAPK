package io.github.th3rumbl3m4t0r.jmail

import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing

/** The PGP keys we hold: ours (secret, kept encrypted) and the public keys of the people we write to. */
object Keys {
    /** A passphrase typed for this session only (when the user doesn't want it kept). */
    @Volatile
    var sessionPassphrase: CharArray? = null

    fun all(): List<Key> = Db.keys()
    fun ours(): List<Key> = all().filter { it.secret }
    fun theirs(): List<Key> = all().filter { !it.secret }

    fun secretRings(): List<PGPSecretKeyRing> = ours().map { Pgp.secretRing(it.encoded) }

    /** Every public key we know, ours included (to verify our own signatures and encrypt to ourselves). */
    fun publicRings(): List<PGPPublicKeyRing> = all().map { publicRing(it) }

    fun publicRing(k: Key): PGPPublicKeyRing =
        if (k.secret) PGPPublicKeyRing(Pgp.secretRing(k.encoded).publicKeys.asSequence().toList()) else Pgp.publicRing(k.encoded)

    /** The best public key for an address: not expired, newest first. */
    fun forEmail(email: String): Key? {
        val e = email.lowercase().trim()
        return all().filter { e in it.emails && !it.expired }.maxByOrNull { it.created }
    }

    fun passphrase(): CharArray? = Store.state.value.passphrase?.toCharArray() ?: sessionPassphrase

    /** True if one of our keys is locked and we don't have its passphrase. */
    fun passphraseMissing(): Boolean {
        val rings = secretRings()
        if (rings.isEmpty()) return false
        val pp = passphrase()
        return rings.none { Pgp.unlocks(it, pp) }
    }

    /** True if [pp] opens one of our keys; keeps it (in prefs or for the session). */
    fun tryPassphrase(pp: String): Boolean {
        val chars = pp.toCharArray()
        if (secretRings().none { Pgp.unlocks(it, chars) }) return false
        if (Store.state.value.rememberPassphrase) Store.update { it.copy(passphrase = pp) } else sessionPassphrase = chars
        return true
    }

    private fun key(info: Pgp.KeyInfo, encoded: ByteArray) = Key(
        fingerprint = info.fingerprint, userIds = info.userIds, emails = info.emails, keyIds = info.keyIds,
        created = info.created, expires = info.expires, secret = info.secret, algorithm = info.algorithm, encoded = encoded,
    )

    /**
     * Keys out of pasted or picked text (armored or binary; secret rings become ours, public ones
     * theirs; a public ring of a key we hold the secret for is ignored). Returns what was added.
     */
    fun import(bytes: ByteArray): List<Key> {
        val added = ArrayList<Key>()
        val secrets = runCatching { Pgp.readSecretRings(bytes) }.getOrDefault(emptyList())
        for (r in secrets) {
            val k = key(Pgp.info(r), r.encoded)
            Db.saveKey(k)
            added += k
        }
        val secretFprs = ours().map { it.fingerprint }.toSet()
        if (secrets.isEmpty()) {
            for (r in Pgp.readPublicRings(bytes)) {
                val info = Pgp.info(r)
                if (info.fingerprint in secretFprs) continue
                val k = key(info, r.encoded)
                Db.saveKey(k)
                added += k
            }
        }
        if (added.isEmpty()) throw PgpError("nothing new in that key")
        return added
    }

    fun remove(fingerprint: String) = Db.removeKey(fingerprint)

    /** The armored public half of a key, to hand to someone. */
    fun export(k: Key): String = Pgp.armor(publicRing(k))
}
