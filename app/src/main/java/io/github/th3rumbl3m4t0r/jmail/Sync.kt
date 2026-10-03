package io.github.th3rumbl3m4t0r.jmail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Keeps the folder list and the message list in step with the server (Email/changes since the
 * state we hold; a full first page when there is none), pushes flag and folder changes, sends.
 * Runs on a trigger: app opened, refresh, an action, the 15 min worker.
 */
object Sync {
    private const val TAG = "mail.sync"
    const val PAGE = 50

    val running = MutableStateFlow(false)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var ctx: Context

    @Volatile
    private var cached: Pair<Triple<String, String, String>, Jmap>? = null

    /** The client for the signed-in account; its session is fetched once per credentials. */
    fun client(): Jmap {
        val p = Store.state.value
        val key = Triple(p.server, p.user, p.password)
        cached?.let { if (it.first == key) return it.second }
        val j = Jmap(p.server, p.user, p.password)
        cached = key to j
        return j
    }

    fun trigger(reason: String, minGapMs: Long = 20_000) {
        scope.launch { run(reason, minGapMs) }
    }

    fun describe(e: Exception): String = when (e) {
        is JmapError -> e.message ?: "server error"
        is MethodError -> e.message ?: "server error"
        is PgpError -> e.message ?: "pgp error"
        is UnknownHostException, is ConnectException, is SocketTimeoutException, is SSLException -> "server not reachable"
        else -> e.message ?: e.javaClass.simpleName
    }

    suspend fun run(reason: String, minGapMs: Long = 0): Boolean = withContext(Dispatchers.IO) {
        val p = Store.state.value
        if (!p.signedIn) return@withContext false
        if (now() - p.lastAttempt < minGapMs) return@withContext true
        if (!lock.tryLock()) return@withContext true
        running.value = true
        try {
            Store.update { it.copy(lastAttempt = now()) }
            Log.i(TAG, "sync ($reason)")
            syncAll(client())
            Store.update { it.copy(lastSyncOk = now(), lastError = null) }
            Notify.check(ctx)
            true
        } catch (e: Exception) {
            Log.i(TAG, "sync failed: $e")
            Store.update { it.copy(lastError = describe(e)) }
            false
        } finally {
            lock.unlock()
            running.value = false
        }
    }

    /** Checks the account, finds the identity and the folders, loads the inbox. Throws with a plain message. */
    suspend fun signIn(server: String, user: String, pass: String): String = withContext(Dispatchers.IO) {
        val j = Jmap(server, user, pass)
        val s = j.connect()
        val ids = j.identities()
        val me = ids.firstOrNull { it.email.equals(user, true) } ?: ids.firstOrNull() ?: throw IOException("this account cannot send mail (no identity)")
        Db.clearMail()
        Reader.forget()
        Store.update {
            it.copy(
                server = j.base.toString().trimEnd('/'), user = user, password = pass,
                accountId = s.accountId, identityId = me.id, fromName = me.name?.ifBlank { null } ?: s.name.ifBlank { null }, fromEmail = me.email,
                identities = Prefs.identitiesJson(ids),
                lastError = null, lastSyncOk = 0, lastAttempt = 0, notifiedUpTo = now(),
            )
        }
        cached = null
        trigger("signin", 0)
        me.email
    }

    /** The server's sending identities, kept in prefs (tiny, and the editor needs them without the network). */
    private fun refreshIdentities(j: Jmap) {
        runCatching { j.identities() }.getOrNull()?.let { ids -> Store.update { it.copy(identities = Prefs.identitiesJson(ids)) } }
    }

    private fun syncAll(j: Jmap) {
        Db.saveMailboxes(j.mailboxes())
        refreshIdentities(j)
        val state = Db.state("emailState")
        if (state == null) initial(j)
        else try {
            changes(j, state)
        } catch (e: MethodError) {
            if (e.type == "cannotCalculateChanges" || e.type == "invalidArguments") {
                Log.i(TAG, "state too old, starting over")
                Db.removeEmails(Db.mailboxes().flatMap { Db.emails(it.id, Int.MAX_VALUE) }.map { it.id })
                initial(j)
            } else throw e
        }
    }

    private fun initial(j: Jmap) {
        val state = j.emailState()
        Db.mailboxByRole("inbox")?.let { loadPage(j, it.id, PAGE) }
        Db.setState("emailState", state)
    }

    private fun changes(j: Jmap, since: String) {
        var s = since
        var c: Changes
        do {
            c = j.changes(s)
            val ids = (c.created + c.updated).distinct()
            if (ids.isNotEmpty()) Db.saveEmails(j.emails(ids))
            if (c.destroyed.isNotEmpty()) {
                Db.removeEmails(c.destroyed)
                c.destroyed.forEach { Reader.forget(it) }
            }
            s = c.newState
        } while (c.hasMore)
        Db.setState("emailState", s)
    }

    /** The newest [want] messages of a folder, fetching the ones we don't have yet. */
    private fun loadPage(j: Jmap, mailboxId: String, want: Int) {
        val q = j.query(mailboxId, null, 0, want)
        val have = Db.emails(mailboxId, Int.MAX_VALUE).map { it.id }.toSet()
        val missing = q.ids.filter { it !in have }
        if (missing.isNotEmpty()) Db.saveEmails(j.emails(missing))
        Db.setState("total:$mailboxId", (q.total ?: q.ids.size).toString())
    }

    /** A folder was opened or "more" pressed: make sure its first [want] rows are here. */
    fun loadMailbox(mailboxId: String, want: Int) {
        scope.launch {
            try {
                loadPage(client(), mailboxId, want)
            } catch (e: Exception) {
                Store.update { it.copy(lastError = describe(e)) }
            }
        }
    }

    /** Server-side search in a folder (or everywhere); returns the ids newest first after caching their rows. */
    suspend fun search(mailboxId: String?, text: String): List<String> = withContext(Dispatchers.IO) {
        val j = client()
        val q = j.query(mailboxId, text, 0, 100)
        val have = Db.emails(mailboxId ?: "", Int.MAX_VALUE).map { it.id }.toSet()
        val missing = q.ids.filter { it !in have && Db.email(it) == null }
        if (missing.isNotEmpty()) Db.saveEmails(j.emails(missing))
        q.ids
    }

    // ---- actions: the database first (the screen follows), then the server ----

    private fun push(what: String, f: (Jmap) -> Unit) {
        scope.launch {
            try {
                f(client())
                Store.update { it.copy(lastError = null) }
            } catch (e: Exception) {
                Log.w(TAG, "$what failed: $e")
                Store.update { it.copy(lastError = "$what: ${describe(e)}") }
                trigger("after error", 0)
            }
        }
    }

    private fun keywordPatch(name: String, on: Boolean): JsonObject = buildJsonObject { if (on) put("keywords/$name", true) else put("keywords/$name", JsonNull) }

    fun setSeen(id: String, seen: Boolean) {
        val e = Db.email(id) ?: return
        if (e.seen == seen) return
        Db.patchKeywords(id, if (seen) setOf("\$seen") else emptySet(), if (seen) emptySet() else setOf("\$seen"))
        push("mark ${if (seen) "read" else "unread"}") { it.set(mapOf(id to keywordPatch("\$seen", seen))) }
    }

    fun setFlagged(id: String, on: Boolean) {
        Db.patchKeywords(id, if (on) setOf("\$flagged") else emptySet(), if (on) emptySet() else setOf("\$flagged"))
        push("flag") { it.set(mapOf(id to keywordPatch("\$flagged", on))) }
    }

    fun move(id: String, mailboxId: String) {
        Db.moveLocal(id, mailboxId)
        push("move") { it.set(mapOf(id to buildJsonObject { putJsonObject("mailboxIds") { put(mailboxId, true) } })) }
    }

    /** To the trash, or gone for good when it is there already. */
    fun trash(id: String) {
        val trash = Db.mailboxByRole("trash")
        val e = Db.email(id)
        if (trash == null || (e != null && trash.id in e.mailboxIds)) {
            Db.removeEmails(listOf(id))
            Reader.forget(id)
            push("delete") { it.set(destroy = listOf(id)) }
        } else move(id, trash.id)
    }

    // ---- sending ----

    class Outgoing(
        val draftId: String?,
        /** The address the recipient sees; any of ours (the catch-all aliases included). */
        val from: Address,
        val to: List<Address>,
        val cc: List<Address>,
        val bcc: List<Address>,
        val subject: String,
        val body: String,
        val attachments: List<Attachment>,
        val encrypt: Boolean,
        val sign: Boolean,
        val inReplyTo: String?,
        val references: String?,
    )

    /** The finished RFC 5322 message, PGP/MIME when asked. */
    private fun build(o: Outgoing, encryptToSelfOnly: Boolean): ByteArray {
        val p = Store.state.value
        val from = o.from.email
        var entity = Mime.bodyEntity(o.body, o.attachments)
        val mine = Keys.ours().firstOrNull()
        val secret = mine?.let { Pgp.secretRing(it.encoded) }
        val pp = Keys.passphrase()
        if (o.encrypt || encryptToSelfOnly) {
            val rings = ArrayList<org.bouncycastle.openpgp.PGPPublicKeyRing>()
            if (!encryptToSelfOnly) for (a in o.to + o.cc + o.bcc) {
                val k = Keys.forEmail(a.email) ?: throw PgpError("no key for ${a.email}")
                rings += Keys.publicRing(k)
            }
            if (mine != null) rings += Keys.publicRing(mine)
            if (rings.isEmpty()) throw PgpError("no key to encrypt to")
            val signer = if (o.sign && secret != null) secret else null
            if (signer != null && !Pgp.unlocks(signer, pp)) throw NeedKey("Your key is locked.", passphrase = true)
            entity = Mime.encryptedEntity(Pgp.encrypt(entity, rings, signer, pp))
        } else if (o.sign && secret != null) {
            if (!Pgp.unlocks(secret, pp)) throw NeedKey("Your key is locked.", passphrase = true)
            val canon = Mime.crlf(entity)
            entity = Mime.signedEntity(canon, Pgp.signDetached(canon, secret, pp))
        }
        return Mime.message(
            Mime.Headers(
                from = Mime.address(o.from.name ?: p.fromName, from),
                to = o.to.map { Mime.address(it.name, it.email) },
                cc = o.cc.map { Mime.address(it.name, it.email) },
                subject = o.subject,
                messageId = Mime.messageId(from.substringAfter('@', "localhost")),
                inReplyTo = o.inReplyTo,
                references = o.references,
                userAgent = "jmail/" + BuildConfig.VERSION_NAME,
            ),
            entity,
        )
    }

    /** Sends; the copy lands in Sent. Throws with a readable message, in which case nothing was sent. */
    suspend fun send(o: Outgoing) = withContext(Dispatchers.IO) {
        val p = Store.state.value
        val rcpts = (o.to + o.cc + o.bcc).map { it.email }.distinct()
        if (rcpts.isEmpty()) throw IOException("no recipient")
        val msg = build(o, encryptToSelfOnly = false)
        val j = client()
        val drafts = Db.mailboxByRole("drafts")
        val sent = Db.mailboxByRole("sent")
        val blob = j.upload(msg, "message/rfc822")
        val id = j.import(blob, (drafts ?: sent ?: Db.mailboxByRole("inbox") ?: throw IOException("no folders")).id, listOf("\$draft", "\$seen"))
        // Stalwart wants the envelope sender to be the identity's own address; the From header is free.
        // An identity for the exact address is used when the server has (or lets us add) one.
        val ids = p.identityList()
        var identity = ids.firstOrNull { it.email.equals(o.from.email, true) }
        if (identity == null && !o.from.email.equals(p.fromEmail, true)) {
            identity = runCatching { j.createIdentity(o.from.name ?: p.fromName, o.from.email) }.getOrNull()
            if (identity != null) refreshIdentities(j)
        }
        val chosen = identity ?: ids.firstOrNull { it.id == p.identityId } ?: ids.firstOrNull()
            ?: Identity(p.identityId ?: throw IOException("no identity"), p.fromName, p.fromEmail ?: throw IOException("no sending address"))
        j.submit(id, chosen.id, chosen.email, rcpts, sent?.id, drafts?.id)
        o.draftId?.let { old -> runCatching { j.set(destroy = listOf(old)) }; Db.removeEmails(listOf(old)); Reader.forget(old) }
        trigger("sent", 0)
    }

    /** Into Drafts, encrypted to our own key when we have one (the server doesn't encrypt what we upload). Returns the draft's id. */
    suspend fun saveDraft(o: Outgoing): String = withContext(Dispatchers.IO) {
        val msg = build(o.copy(sign = false), encryptToSelfOnly = Keys.ours().isNotEmpty())
        val j = client()
        val drafts = Db.mailboxByRole("drafts") ?: throw IOException("no drafts folder")
        val blob = j.upload(msg, "message/rfc822")
        val id = j.import(blob, drafts.id, listOf("\$draft", "\$seen"))
        o.draftId?.let { old -> runCatching { j.set(destroy = listOf(old)) }; Db.removeEmails(listOf(old)); Reader.forget(old) }
        trigger("draft", 0)
        id
    }

    private fun Outgoing.copy(sign: Boolean) = Outgoing(draftId, from, to, cc, bcc, subject, body, attachments, encrypt, sign, inReplyTo, references)
}

/** New mail in the inbox since the last look → a notification per message (a few at most). */
object Notify {
    private const val CHANNEL = "mail"

    fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "New mail", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun check(ctx: Context) {
        val p = Store.state.value
        if (!p.notify) return
        val mailboxes = Db.mailboxes()
        val watched = mailboxes.filter { it.id in p.notifyIds(mailboxes) }
        if (watched.isEmpty()) return
        val fresh = watched.flatMap { m -> Db.newSince(m.id, p.notifiedUpTo).map { it to m } }
            .distinctBy { it.first.id }.sortedByDescending { it.first.receivedAt }
        if (fresh.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for ((e, m) in fresh.take(5)) {
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(e.from.firstOrNull()?.short ?: "mail")
                .setContentText(e.subject.ifBlank { "(no subject)" } + (if (e.encrypted) " · encrypted" else "") + (if (m.role != "inbox") " · ${m.name.lowercase()}" else ""))
                .setWhen(e.receivedAt)
                .setShowWhen(true)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EMAIL)
                .build()
            nm.notify(e.id.hashCode(), n)
        }
        Store.update { it.copy(notifiedUpTo = maxOf(it.notifiedUpTo, fresh.maxOf { f -> f.first.receivedAt })) }
    }
}

/** The background half: every 15 minutes with a network. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Sync.run("periodic", 60_000)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
