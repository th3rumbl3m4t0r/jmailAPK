package io.github.th3rumbl3m4t0r.jmail

import org.apache.james.mime4j.dom.BinaryBody
import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.dom.field.ContentTypeField
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** One node of a parsed MIME tree; leaves carry their decoded bytes. */
class Part(
    val type: String,
    val charset: String?,
    val name: String?,
    val disposition: String?,
    val protocol: String?,
    val bytes: ByteArray?,
    val children: List<Part>,
    /** Content-ID without the angle brackets, for `cid:` references. */
    val cid: String? = null,
) {
    val isText get() = type.startsWith("text/") && bytes != null
    val text: String? get() = bytes?.let { String(it, charsetOrDefault()) }
    private fun charsetOrDefault(): Charset = runCatching { Charset.forName(charset ?: "utf-8") }.getOrDefault(Charsets.UTF_8)
    val isAttachment get() = children.isEmpty() && (disposition.equals("attachment", true) || (!type.startsWith("text/") && name != null))
    fun leaves(): List<Part> = if (children.isEmpty()) listOf(this) else children.flatMap { it.leaves() }
}

class Attachment(val name: String, val type: String, val bytes: ByteArray)

/**
 * MIME in both directions: mime4j parses what we download or decrypt; the writer builds
 * what we send (JMAP imports the finished message, so headers and encodings are ours).
 */
object Mime {
    private val builder: DefaultMessageBuilder by lazy {
        DefaultMessageBuilder().apply { setMimeEntityConfig(MimeConfig.PERMISSIVE) }
    }

    /** The body tree of a message or of a bare MIME entity (what a PGP/MIME body decrypts to). */
    fun parse(raw: ByteArray): Part = toPart(builder.parseMessage(ByteArrayInputStream(raw)))

    private fun toPart(e: Entity): Part {
        val type = e.mimeType.lowercase()
        val ct = e.header.getField("Content-Type") as? ContentTypeField
        val protocol = ct?.getParameter("protocol")?.lowercase()
        val cid = e.header.getField("Content-ID")?.body?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() }
        return when (val body = e.body) {
            is Multipart -> Part(type, null, null, e.dispositionType, protocol, null, body.bodyParts.map { toPart(it) })
            is TextBody -> Part(type, e.charset ?: body.mimeCharset, e.filename, e.dispositionType, protocol, body.inputStream.readBytes(), emptyList(), cid)
            is BinaryBody -> Part(type, e.charset, e.filename, e.dispositionType, protocol, body.inputStream.readBytes(), emptyList(), cid)
            else -> Part(type, e.charset, e.filename, e.dispositionType, protocol, null, emptyList(), cid)
        }
    }

    /** The text to show: the first text/plain not meant as an attachment, else the first text/html turned to text. */
    fun text(root: Part): String? {
        val plain = firstText(root, "text/plain")
        if (plain != null) return plain.text
        val html = firstText(root, "text/html")
        return html?.text?.let { Html.toText(it) }
    }

    /** The HTML body, if the message has one. */
    fun html(root: Part): String? = firstText(root, "text/html")?.text

    private fun firstText(p: Part, type: String): Part? {
        if (p.children.isEmpty()) return if (p.type == type && p.bytes != null && !p.disposition.equals("attachment", true)) p else null
        for (c in p.children) firstText(c, type)?.let { return it }
        return null
    }

    /**
     * For a multipart/signed message or entity: the signed part exactly as transmitted (headers
     * and body, up to but not including the CRLF before the closing boundary) and the signature
     * part's body. Null if the top level isn't multipart/signed.
     */
    fun rawSignedPart(raw: ByteArray): Pair<ByteArray, ByteArray>? {
        val s = String(raw, Charsets.ISO_8859_1)
        val headerEnd = s.indexOf("\r\n\r\n").let { if (it < 0) s.indexOf("\n\n").let { n -> if (n < 0) return null else n to 2 } else it to 4 }
        val headers = s.substring(0, headerEnd.first).replace(Regex("\r?\n[ \t]+"), " ")
        val ct = headers.lines().firstOrNull { it.lowercase().startsWith("content-type:") } ?: return null
        if (!ct.lowercase().contains("multipart/signed")) return null
        val boundary = Regex("boundary=(?:\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE).find(ct)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: return null
        val body = s.substring(headerEnd.first + headerEnd.second)
        val delim = "--$boundary"
        val first = body.indexOf(delim).takeIf { it >= 0 } ?: return null
        val afterFirst = body.indexOf('\n', first + delim.length).takeIf { it >= 0 }?.plus(1) ?: return null
        val second = body.indexOf("\r\n$delim", afterFirst).let { if (it >= 0) it to 2 else body.indexOf("\n$delim", afterFirst).let { n -> if (n < 0) return null else n to 1 } }
        val signed = body.substring(afterFirst, second.first)
        val afterSecond = body.indexOf('\n', second.first + second.second + delim.length).takeIf { it >= 0 }?.plus(1) ?: return null
        val end = body.indexOf("\r\n$delim", afterSecond).let { if (it >= 0) it else body.indexOf("\n$delim", afterSecond).let { n -> if (n < 0) body.length else n } }
        val sigEntity = body.substring(afterSecond, end)
        val sigBody = sigEntity.indexOf("\r\n\r\n").let { if (it >= 0) sigEntity.substring(it + 4) else sigEntity.indexOf("\n\n").let { n -> if (n >= 0) sigEntity.substring(n + 2) else sigEntity } }
        return signed.toByteArray(Charsets.ISO_8859_1) to sigBody.toByteArray(Charsets.ISO_8859_1)
    }

    /** Everything a reader would call an attachment. */
    fun attachments(root: Part): List<Part> = root.leaves().filter { it.isAttachment && it.type != "application/pgp-encrypted" && it.type != "application/pgp-signature" }

    // ---- writing ----

    private val rfc5322Date = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)

    fun date(d: Date = Date()): String = rfc5322Date.format(d)

    fun messageId(host: String) = "<${UUID.randomUUID()}@${host.ifBlank { "localhost" }}>"

    private fun boundary() = "=_" + UUID.randomUUID().toString().replace("-", "")

    private fun isAscii(s: String) = s.all { it.code in 32..126 }

    /** RFC 2047 when needed. */
    fun encodeWord(s: String): String = if (isAscii(s)) s else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray()) + "?="

    /** `Name <addr>` with the name quoted or encoded as needed. */
    fun address(name: String?, addr: String): String {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return addr
        val shown = if (isAscii(n)) "\"" + n.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else encodeWord(n)
        return "$shown <$addr>"
    }

    fun quotedPrintable(text: String): String {
        val bytes = text.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var col = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            if (b == 13 && i + 1 < bytes.size && bytes[i + 1].toInt() == 10) {
                sb.append("\r\n")
                col = 0
                i += 2
                continue
            }
            val atLineEnd = i + 1 >= bytes.size || (bytes[i + 1].toInt() == 13)
            val plain = (b in 33..126 && b != 61) || (b == 32 && !atLineEnd) || b == 9 && !atLineEnd
            val tok = if (plain) b.toChar().toString() else "=%02X".format(b)
            if (col + tok.length > 75) {
                sb.append("=\r\n")
                col = 0
            }
            sb.append(tok)
            col += tok.length
            i++
        }
        return sb.toString()
    }

    fun base64Lines(bytes: ByteArray): String = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(bytes)

    /** A text/plain entity (headers + body), UTF-8, quoted-printable. */
    fun textEntity(text: String): ByteArray =
        ("Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n" + quotedPrintable(text) + "\r\n").toByteArray()

    private fun attachmentEntity(a: Attachment): ByteArray {
        val name = encodeWord(a.name).replace("\"", "")
        return ("Content-Type: ${a.type}; name=\"$name\"\r\nContent-Disposition: attachment; filename=\"$name\"\r\nContent-Transfer-Encoding: base64\r\n\r\n" + base64Lines(a.bytes) + "\r\n").toByteArray()
    }

    private fun multipart(type: String, params: String, parts: List<ByteArray>): ByteArray {
        val b = boundary()
        val out = ByteArrayOutputStream()
        out.write("Content-Type: $type; $params boundary=\"$b\"\r\n\r\n".replace(";  ", "; ").toByteArray())
        for (p in parts) {
            out.write("--$b\r\n".toByteArray())
            out.write(p)
            // the CRLF before a boundary belongs to the boundary (RFC 2046), so a signed part keeps its own last line ending
            out.write("\r\n".toByteArray())
        }
        out.write("--$b--\r\n".toByteArray())
        return out.toByteArray()
    }

    /** The text with its attachments: a bare text entity, or multipart/mixed. */
    fun bodyEntity(text: String, attachments: List<Attachment>): ByteArray =
        if (attachments.isEmpty()) textEntity(text)
        else multipart("multipart/mixed", "", listOf(textEntity(text)) + attachments.map { attachmentEntity(it) })

    /** PGP/MIME (RFC 3156): the version part and the armored ciphertext. */
    fun encryptedEntity(armored: String): ByteArray = multipart(
        "multipart/encrypted", "protocol=\"application/pgp-encrypted\";",
        listOf(
            "Content-Type: application/pgp-encrypted\r\nContent-Description: PGP/MIME version identification\r\n\r\nVersion: 1\r\n".toByteArray(),
            ("Content-Type: application/octet-stream; name=\"encrypted.asc\"\r\nContent-Description: OpenPGP encrypted message\r\nContent-Disposition: inline; filename=\"encrypted.asc\"\r\n\r\n" + armored + "\r\n").toByteArray(),
        ),
    )

    /** PGP/MIME signed: the entity as it was signed, then the detached signature. */
    fun signedEntity(inner: ByteArray, armoredSignature: String): ByteArray = multipart(
        "multipart/signed", "protocol=\"application/pgp-signature\"; micalg=pgp-sha256;",
        listOf(inner, ("Content-Type: application/pgp-signature; name=\"signature.asc\"\r\nContent-Description: OpenPGP digital signature\r\nContent-Disposition: attachment; filename=\"signature.asc\"\r\n\r\n" + armoredSignature + "\r\n").toByteArray()),
    )

    class Headers(
        val from: String,
        val to: List<String>,
        val cc: List<String> = emptyList(),
        val subject: String,
        val date: Date = Date(),
        val messageId: String,
        val inReplyTo: String? = null,
        val references: String? = null,
        val userAgent: String = "jmail",
    )

    /** A complete RFC 5322 message: the headers, then the entity's own headers and content. */
    fun message(h: Headers, entity: ByteArray): ByteArray {
        val sb = StringBuilder()
        sb.append("From: ").append(h.from).append("\r\n")
        if (h.to.isNotEmpty()) sb.append("To: ").append(h.to.joinToString(",\r\n ")).append("\r\n")
        if (h.cc.isNotEmpty()) sb.append("Cc: ").append(h.cc.joinToString(",\r\n ")).append("\r\n")
        sb.append("Subject: ").append(encodeWord(h.subject)).append("\r\n")
        sb.append("Date: ").append(date(h.date)).append("\r\n")
        sb.append("Message-ID: ").append(h.messageId).append("\r\n")
        h.inReplyTo?.let { sb.append("In-Reply-To: ").append(it).append("\r\n") }
        h.references?.let { sb.append("References: ").append(it).append("\r\n") }
        sb.append("User-Agent: ").append(h.userAgent).append("\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        val out = ByteArrayOutputStream()
        out.write(sb.toString().toByteArray())
        out.write(entity)
        return out.toByteArray()
    }

    /** Headers the way Thunderbird and gpg expect them when the signed entity is canonicalised: CRLF everywhere. */
    fun crlf(bytes: ByteArray): ByteArray {
        val s = String(bytes, Charsets.ISO_8859_1)
        return s.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)
    }

    init {
        rfc5322Date.timeZone = TimeZone.getDefault()
    }
}
