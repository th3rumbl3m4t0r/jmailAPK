package io.github.th3rumbl3m4t0r.jmail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Our key is missing or locked: the reader shows what to do instead of a body. */
class NeedKey(message: String, val passphrase: Boolean) : IOException(message)

/**
 * Opens a message: fetches it, decrypts PGP/MIME (or inline PGP) with our key, checks
 * signatures against the keys we know, and turns HTML into text. Results stay in memory
 * only (a dozen of them), never on disk.
 */
object Reader {
    private val cache = object : LinkedHashMap<String, Opened>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Opened>?) = size > 12
    }

    fun forget(id: String? = null) = synchronized(cache) { if (id == null) cache.clear() else cache.remove(id) }

    suspend fun open(id: String): Opened = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[id] }?.let { return@withContext it }
        val j = Sync.client()
        val full = j.email(id) ?: throw IOException("the message is gone")
        val o = build(j, full)
        synchronized(cache) { cache[id] = o }
        o
    }

    private fun build(j: Jmap, full: EmailFull): Opened {
        val body = full.body
        when {
            body.type == "multipart/encrypted" && body.subParts.size >= 2 -> {
                val part = body.subParts.firstOrNull { it.type == "application/octet-stream" } ?: body.subParts[1]
                val payload = j.download(part.blobId ?: throw IOException("no encrypted part"), part.name ?: "encrypted.asc", part.type)
                return decrypted(full, payload)
            }
            body.type == "multipart/signed" -> {
                val raw = j.download(full.header.blobId, "message.eml", "message/rfc822")
                val sig = Mime.rawSignedPart(raw)?.let { (data, s) -> verifyLenient(data, s) }
                return plain(full, security(full, encrypted = false, listOfNotNull(sig)))
            }
        }
        val text = plainText(full)
        if (text.trimStart().startsWith("-----BEGIN PGP MESSAGE-----")) {
            // inline PGP: the text is the ciphertext, the plaintext is just text
            val d = decryptBytes(text.toByteArray())
            return Opened(full, String(d.data, Charsets.UTF_8), null, emptyList(), emptyMap(), security(full, encrypted = d.encrypted, d.sigs))
        }
        return plain(full, null)
    }

    private fun decryptBytes(payload: ByteArray): Pgp.Decrypted {
        val secrets = Keys.secretRings()
        if (secrets.isEmpty()) throw NeedKey("This message is encrypted. Import your private key under settings → keys to read it.", passphrase = false)
        val pp = Keys.passphrase()
        if (secrets.none { Pgp.unlocks(it, pp) }) throw NeedKey("Your key is locked.", passphrase = true)
        return Pgp.decrypt(payload, secrets, pp, Keys.publicRings())
    }

    private fun decrypted(full: EmailFull, payload: ByteArray): Opened {
        val d = decryptBytes(payload)
        var sigs = d.sigs
        val inner = Mime.parse(d.data)
        if (inner.type == "multipart/signed") {
            Mime.rawSignedPart(d.data)?.let { (data, s) -> verifyLenient(data, s)?.let { sigs = sigs + it } }
        }
        val text = (Mime.text(inner) ?: "").replace("\r\n", "\n").replace('\r', '\n')
        val atts = Mime.attachments(inner).map { OpenedAttachment(it.name ?: "attachment", it.type, (it.bytes?.size ?: 0).toLong(), null, it.bytes) }
        val inline = inner.leaves().filter { it.cid != null && it.bytes != null }.associate { it.cid!! to OpenedAttachment(it.name ?: it.cid!!, it.type, it.bytes!!.size.toLong(), null, it.bytes) }
        return Opened(full, text, Mime.html(inner), atts, inline, security(full, encrypted = true, sigs))
    }

    /**
     * RFC 3156 signs the part without the CRLF before the boundary; some senders include it.
     * A failed check is retried that way before it is called bad.
     */
    private fun verifyLenient(data: ByteArray, sig: ByteArray): Pgp.Sig? {
        val publics = Keys.publicRings()
        val r = Pgp.verifyDetached(data, sig, publics) ?: return null
        if (r.valid || !r.known) return r
        return Pgp.verifyDetached(data + "\r\n".toByteArray(), sig, publics)?.takeIf { it.valid } ?: r
    }

    private fun plainText(full: EmailFull): String {
        val plain = full.textBody.filter { it.type == "text/plain" }.mapNotNull { p -> p.partId?.let { full.bodyValues[it] } }
        if (plain.isNotEmpty()) return plain.joinToString("\n\n")
        val html = full.htmlBody.mapNotNull { p -> p.partId?.let { full.bodyValues[it] } }
        if (html.isNotEmpty()) return html.joinToString("\n\n") { Html.toText(it) }
        val any = full.textBody.mapNotNull { p -> p.partId?.let { full.bodyValues[it] } }
        return any.joinToString("\n\n") { if (it.trimStart().startsWith("<")) Html.toText(it) else it }
    }

    private fun plain(full: EmailFull, security: Security?): Opened {
        val atts = full.attachments.filter { it.type != "application/pgp-signature" && it.type != "application/pgp-keys" || it.disposition.equals("attachment", true) }
            .map { OpenedAttachment(it.name ?: "attachment", it.type, it.size, it.blobId, null) }
        // htmlBody lists text/plain parts too when the message has no HTML alternative
        val html = full.htmlBody.filter { it.type == "text/html" }.mapNotNull { p -> p.partId?.let { full.bodyValues[it] } }.takeIf { it.isNotEmpty() }?.joinToString("<br>")
        val inline = full.body.leaves().filter { it.cid != null && it.blobId != null }.associate { it.cid!!.trim('<', '>') to OpenedAttachment(it.name ?: it.cid!!, it.type, it.size, it.blobId, null) }
        return Opened(full, plainText(full).replace("\r\n", "\n").replace('\r', '\n'), html, atts, inline, security)
    }

    /** The one line under the subject: what the crypto says. */
    private fun security(full: EmailFull, encrypted: Boolean, sigs: List<Pgp.Sig>): Security? {
        if (!encrypted && sigs.isEmpty()) return null
        val sig = sigs.firstOrNull()
        val sender = full.header.from.firstOrNull()?.email?.lowercase()
        val signerEmails = sig?.userIds?.mapNotNull { Regex("<([^>]+)>").find(it)?.groupValues?.get(1)?.lowercase() } ?: emptyList()
        val note = when {
            sig == null -> null
            !sig.known -> "signed with a key we don't have (${"%016X".format(sig.keyId).takeLast(16)})"
            !sig.valid -> "BAD signature: the message was changed after signing"
            sender != null && signerEmails.isNotEmpty() && sender !in signerEmails -> "signed by ${signerEmails.first()}, not by the sender"
            else -> null
        }
        return Security(
            encrypted = encrypted,
            signed = sig != null,
            valid = sig?.valid == true && sig.known,
            signer = sig?.userIds?.firstOrNull(),
            note = note,
        )
    }

    /** An attachment's bytes: decrypted ones are in memory, the others come from the server. */
    suspend fun bytes(a: OpenedAttachment): ByteArray = withContext(Dispatchers.IO) {
        a.bytes ?: Sync.client().download(a.blobId ?: throw IOException("no data"), a.name, a.type)
    }
}
