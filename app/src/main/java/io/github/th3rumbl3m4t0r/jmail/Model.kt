package io.github.th3rumbl3m4t0r.jmail

/** A folder. */
data class Mailbox(
    val id: String,
    val name: String,
    val role: String?,
    val parentId: String?,
    val sortOrder: Int,
    val total: Int,
    val unread: Int,
) {
    /** Folders in the order the list shows them: the standard ones first, then the rest by name. */
    val rank: Int get() = when (role) {
        "inbox" -> 0
        "drafts" -> 1
        "sent" -> 2
        "archive" -> 3
        "junk" -> 4
        "trash" -> 5
        else -> 9
    }
}

data class Address(val name: String?, val email: String) {
    /** "Bob" or the address. */
    val short get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: email

    /** `Bob <bob@x>` */
    val full get() = if (name.isNullOrBlank()) email else "$name <$email>"

    companion object {
        /** `Bob <bob@x>`, `bob@x`, `"B" <bob@x>` → an address, or null. */
        fun parse(s: String): Address? {
            val t = s.trim().trimEnd(',', ';').trim()
            if (t.isEmpty()) return null
            val m = Regex("^(?:\"?([^\"<]*?)\"?\\s*)?<([^>]+)>$").find(t)
            if (m != null) return Address(m.groupValues[1].trim().ifEmpty { null }, m.groupValues[2].trim())
            return if (t.contains('@') && !t.contains(' ')) Address(null, t) else null
        }

        /** A comma / semicolon / newline separated list. */
        fun parseList(s: String): List<Address> = Regex("[,;\\n]").split(s).mapNotNull { parse(it) }
    }
}

/** What the message list needs: one row per message, cached in the database. */
data class Email(
    val id: String,
    val blobId: String,
    val threadId: String,
    val mailboxIds: Set<String>,
    val keywords: Set<String>,
    val hasAttachment: Boolean,
    val from: List<Address>,
    val to: List<Address>,
    val subject: String,
    val receivedAt: Long,
    val sentAt: Long?,
    val size: Long,
    val preview: String,
    /** The top-level media type, so the list can mark PGP/MIME messages before they are opened. */
    val kind: String = "",
    /** The address the server delivered it to (Delivered-To): the alias of ours the sender used. */
    val deliveredTo: String? = null,
) {
    val encrypted get() = kind == "multipart/encrypted"
    val signed get() = kind == "multipart/signed"
    val seen get() = "\$seen" in keywords
    val flagged get() = "\$flagged" in keywords
    val draft get() = "\$draft" in keywords
    val answered get() = "\$answered" in keywords
}

/** A node of the server's bodyStructure. */
data class BodyPart(
    val partId: String?,
    val blobId: String?,
    val size: Long,
    val name: String?,
    val type: String,
    val charset: String?,
    val disposition: String?,
    val cid: String?,
    val subParts: List<BodyPart>,
) {
    fun leaves(): List<BodyPart> = if (subParts.isEmpty()) listOf(this) else subParts.flatMap { it.leaves() }
}

/** The whole message, as fetched when it is opened. */
data class EmailFull(
    val header: Email,
    val cc: List<Address>,
    val replyTo: List<Address>,
    val messageId: List<String>,
    val inReplyTo: List<String>,
    val references: List<String>,
    val body: BodyPart,
    val textBody: List<BodyPart>,
    val htmlBody: List<BodyPart>,
    val attachments: List<BodyPart>,
    val bodyValues: Map<String, String>,
)

data class Identity(val id: String, val name: String?, val email: String)

/** A Sieve script on the server; at most one is active. */
data class SieveScript(val id: String, val name: String, val blobId: String, val active: Boolean)

/** One key in our ring: ours (secret) or someone else's. */
data class Key(
    val fingerprint: String,
    val userIds: List<String>,
    val emails: List<String>,
    val keyIds: List<Long>,
    val created: Long,
    val expires: Long?,
    val secret: Boolean,
    val algorithm: String,
    /** The ring, binary; a secret one is stored encrypted and decrypted on load. */
    val encoded: ByteArray,
) {
    val label get() = userIds.firstOrNull() ?: Pgp.pretty(fingerprint)
    val expired get() = expires != null && expires < System.currentTimeMillis()
}

/** How a message looked once opened: what the reader shows besides the text. */
data class Security(
    val encrypted: Boolean,
    val signed: Boolean,
    /** The signature checked out against a key we know. */
    val valid: Boolean,
    val signer: String?,
    val note: String?,
)

/** A message ready to show: the text, the attachments with their bytes or blob, and the PGP outcome. */
class Opened(
    val full: EmailFull,
    val text: String,
    /** The HTML body, when the message has one (rendered unless the reader is in text mode). */
    val html: String?,
    val attachments: List<OpenedAttachment>,
    /** Parts with a Content-ID, for `cid:` images inside the HTML. */
    val inline: Map<String, OpenedAttachment>,
    val security: Security?,
)

class OpenedAttachment(val name: String, val type: String, val size: Long, val blobId: String?, val bytes: ByteArray?)
