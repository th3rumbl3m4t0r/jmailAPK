package io.github.th3rumbl3m4t0r.jmail

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

/**
 * The phone's copy of the folders and the message list (headers only: bodies are fetched when
 * a message is opened and never written to disk), plus the PGP keys. [version] ticks on every
 * write so the screens know to look again.
 */
object Db {
    private lateinit var h: Helper
    val version = MutableStateFlow(0)

    fun init(ctx: Context) {
        if (::h.isInitialized) return
        h = Helper(ctx.applicationContext)
    }

    private val db: SQLiteDatabase get() = h.writableDatabase
    private fun bump() { version.value++ }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "mail.db", null, 3) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE mailboxes(id TEXT PRIMARY KEY, name TEXT NOT NULL, role TEXT, parent TEXT, sortorder INTEGER NOT NULL, total INTEGER NOT NULL, unread INTEGER NOT NULL)")
            d.execSQL(
                "CREATE TABLE emails(id TEXT PRIMARY KEY, blob TEXT NOT NULL, thread TEXT NOT NULL, mailboxes TEXT NOT NULL, keywords TEXT NOT NULL, " +
                    "hasatt INTEGER NOT NULL, sender TEXT NOT NULL, rcpts TEXT NOT NULL, subject TEXT NOT NULL, received INTEGER NOT NULL, sent INTEGER, " +
                    "size INTEGER NOT NULL, preview TEXT NOT NULL, kind TEXT NOT NULL DEFAULT '', deliveredto TEXT)",
            )
            d.execSQL("CREATE INDEX emails_received ON emails(received)")
            d.execSQL("CREATE TABLE state(k TEXT PRIMARY KEY, v TEXT)")
            d.execSQL("CREATE TABLE seen(addr TEXT PRIMARY KEY, n INTEGER NOT NULL)")
            d.execSQL(
                "CREATE TABLE keys(fpr TEXT PRIMARY KEY, uids TEXT NOT NULL, emails TEXT NOT NULL, keyids TEXT NOT NULL, created INTEGER NOT NULL, " +
                    "expires INTEGER, secret INTEGER NOT NULL, algo TEXT NOT NULL, ring TEXT NOT NULL)",
            )
        }

        override fun onUpgrade(d: SQLiteDatabase, from: Int, to: Int) {
            if (from < 2) {
                // 2: the Delivered-To address per message; the list is fetched again to fill it
                d.execSQL("ALTER TABLE emails ADD COLUMN deliveredto TEXT")
                d.execSQL("DELETE FROM emails")
                d.execSQL("DELETE FROM state")
            }
            // 3: the addresses received mail was sent to (our aliases), counted as the rows come in;
            // the list is fetched again so the ones already here are counted too
            if (from < 3) {
                d.execSQL("CREATE TABLE seen(addr TEXT PRIMARY KEY, n INTEGER NOT NULL)")
                d.execSQL("DELETE FROM emails")
                d.execSQL("DELETE FROM state")
            }
        }
    }

    private fun jsonList(l: Collection<String>): String = JSONArray(l).toString()
    private fun listJson(s: String?): List<String> {
        if (s.isNullOrEmpty()) return emptyList()
        val a = JSONArray(s)
        return List(a.length()) { a.getString(it) }
    }

    private fun addrsJson(l: List<Address>): String = JSONArray(l.map { JSONArray(listOf(it.name ?: "", it.email)) }).toString()
    private fun jsonAddrs(s: String?): List<Address> {
        if (s.isNullOrEmpty()) return emptyList()
        val a = JSONArray(s)
        return List(a.length()) { i -> val p = a.getJSONArray(i); Address(p.getString(0).ifEmpty { null }, p.getString(1)) }
    }

    // ---- state ----

    fun state(k: String): String? = db.rawQuery("SELECT v FROM state WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setState(k: String, v: String?) {
        if (v == null) db.delete("state", "k = ?", arrayOf(k))
        else db.insertWithOnConflict("state", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- mailboxes ----

    private fun Cursor.mailbox() = Mailbox(getString(0), getString(1), getString(2), getString(3), getInt(4), getInt(5), getInt(6))

    fun mailboxes(): List<Mailbox> = db.rawQuery("SELECT id, name, role, parent, sortorder, total, unread FROM mailboxes", null).use { c ->
        buildList { while (c.moveToNext()) add(c.mailbox()) }.sortedWith(compareBy({ it.rank }, { it.sortOrder }, { it.name.lowercase() }))
    }

    fun mailbox(id: String): Mailbox? = mailboxes().firstOrNull { it.id == id }
    fun mailboxByRole(role: String): Mailbox? = mailboxes().firstOrNull { it.role == role }

    fun saveMailboxes(list: List<Mailbox>) {
        db.beginTransaction()
        try {
            db.delete("mailboxes", null, null)
            for (m in list) db.insert("mailboxes", null, ContentValues().apply {
                put("id", m.id); put("name", m.name); put("role", m.role); put("parent", m.parentId)
                put("sortorder", m.sortOrder); put("total", m.total); put("unread", m.unread)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    // ---- emails ----

    private const val COLS = "id, blob, thread, mailboxes, keywords, hasatt, sender, rcpts, subject, received, sent, size, preview, kind, deliveredto"

    private fun Cursor.email() = Email(
        id = getString(0), blobId = getString(1), threadId = getString(2),
        mailboxIds = listJson(getString(3)).toSet(), keywords = listJson(getString(4)).toSet(),
        hasAttachment = getInt(5) != 0, from = jsonAddrs(getString(6)), to = jsonAddrs(getString(7)),
        subject = getString(8), receivedAt = getLong(9), sentAt = if (isNull(10)) null else getLong(10),
        size = getLong(11), preview = getString(12), kind = getString(13), deliveredTo = getString(14),
    )

    private fun values(e: Email) = ContentValues().apply {
        put("id", e.id); put("blob", e.blobId); put("thread", e.threadId)
        put("mailboxes", jsonList(e.mailboxIds)); put("keywords", jsonList(e.keywords))
        put("hasatt", if (e.hasAttachment) 1 else 0); put("sender", addrsJson(e.from)); put("rcpts", addrsJson(e.to))
        put("subject", e.subject); put("received", e.receivedAt)
        if (e.sentAt == null) putNull("sent") else put("sent", e.sentAt)
        put("size", e.size); put("preview", e.preview); put("kind", e.kind); put("deliveredto", e.deliveredTo)
    }

    fun saveEmails(list: List<Email>) {
        if (list.isEmpty()) return
        val outgoing = mailboxes().filter { it.role == "sent" || it.role == "drafts" }.map { it.id }.toSet()
        db.beginTransaction()
        try {
            for (e in list) {
                val fresh = email(e.id) == null
                db.insertWithOnConflict("emails", null, values(e), SQLiteDatabase.CONFLICT_REPLACE)
                // mail sent to exactly one address that reached us: that address is one of ours
                if (fresh && e.to.size == 1 && e.mailboxIds.none { it in outgoing }) {
                    val a = e.to[0].email.lowercase()
                    db.execSQL("INSERT OR REPLACE INTO seen(addr, n) VALUES (?, COALESCE((SELECT n FROM seen WHERE addr = ?), 0) + 1)", arrayOf(a, a))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    /** The addresses received mail was sent to, most used first: (address, how many messages). */
    fun seenAddresses(): List<Pair<String, Int>> =
        db.rawQuery("SELECT addr, n FROM seen ORDER BY n DESC, addr", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }

    fun removeEmails(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            for (id in ids) db.delete("emails", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    fun email(id: String): Email? = db.rawQuery("SELECT $COLS FROM emails WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.email() else null }

    /** Newest first, in one mailbox. */
    fun emails(mailboxId: String, limit: Int): List<Email> =
        db.rawQuery("SELECT $COLS FROM emails WHERE mailboxes LIKE ? ORDER BY received DESC LIMIT ?", arrayOf("%\"$mailboxId\"%", limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.email()) }.filter { mailboxId in it.mailboxIds }
        }

    fun countIn(mailboxId: String): Int = emails(mailboxId, Int.MAX_VALUE).size

    /** A local change to the flags, shown at once; the server gets it from the sync. */
    fun patchKeywords(id: String, add: Set<String>, remove: Set<String>) {
        val e = email(id) ?: return
        saveEmails(listOf(e.copy(keywords = e.keywords - remove + add)))
    }

    fun moveLocal(id: String, to: String) {
        val e = email(id) ?: return
        saveEmails(listOf(e.copy(mailboxIds = setOf(to))))
    }

    /** Unseen messages that arrived after [since], newest first (for the notification). */
    fun newSince(mailboxId: String, since: Long): List<Email> =
        emails(mailboxId, 50).filter { it.receivedAt > since && !it.seen }

    fun clearMail() {
        db.delete("emails", null, null)
        db.delete("seen", null, null)
        db.delete("mailboxes", null, null)
        db.delete("state", null, null)
        bump()
    }

    // ---- keys ----

    private fun Cursor.key(): Key {
        val secret = getInt(6) != 0
        val stored = getString(8)
        val encoded = if (secret) Base64.decode(Store.crypto.decrypt(stored), Base64.NO_WRAP) else Base64.decode(stored, Base64.NO_WRAP)
        return Key(
            fingerprint = getString(0), userIds = listJson(getString(1)), emails = listJson(getString(2)),
            keyIds = listJson(getString(3)).map { it.toLong() }, created = getLong(4), expires = if (isNull(5)) null else getLong(5),
            secret = secret, algorithm = getString(7), encoded = encoded,
        )
    }

    fun keys(): List<Key> = db.rawQuery("SELECT fpr, uids, emails, keyids, created, expires, secret, algo, ring FROM keys ORDER BY secret DESC, uids", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { c.key() }.getOrNull()?.let { add(it) } }
    }

    fun saveKey(k: Key) {
        val b64 = Base64.encodeToString(k.encoded, Base64.NO_WRAP)
        db.insertWithOnConflict("keys", null, ContentValues().apply {
            put("fpr", k.fingerprint); put("uids", jsonList(k.userIds)); put("emails", jsonList(k.emails))
            put("keyids", jsonList(k.keyIds.map { it.toString() })); put("created", k.created)
            if (k.expires == null) putNull("expires") else put("expires", k.expires)
            put("secret", if (k.secret) 1 else 0); put("algo", k.algorithm)
            put("ring", if (k.secret) Store.crypto.encrypt(b64) else b64)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        bump()
    }

    fun removeKey(fingerprint: String) {
        db.delete("keys", "fpr = ?", arrayOf(fingerprint))
        bump()
    }
}
