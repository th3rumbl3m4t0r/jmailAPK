package io.github.th3rumbl3m4t0r.jmail

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

const val DEFAULT_SERVER = ""

/** What the user set and what the sync remembers. Secrets are kept encrypted (see [Crypto]). */
data class Prefs(
    val server: String = DEFAULT_SERVER,
    val user: String = "",
    val password: String = "",
    val accountId: String? = null,
    val identityId: String? = null,
    val fromName: String? = null,
    val fromEmail: String? = null,
    val night: Boolean = true,
    val accent: String = DEFAULT_ACCENT,
    val notify: Boolean = true,
    /** The PGP passphrase, when the user chose to keep it. */
    val passphrase: String? = null,
    val rememberPassphrase: Boolean = true,
    /** Sign outgoing mail when we have a key. */
    val signByDefault: Boolean = true,
    val lastSyncOk: Long = 0,
    val lastAttempt: Long = 0,
    val lastError: String? = null,
    /** The newest message a notification was shown for. */
    val notifiedUpTo: Long = 0,
    /** Sender domains whose online content (images) loads without asking. */
    val imageDomains: String = "",
    /** Domains we may send from, besides the ones seen in Delivered-To and the identities. */
    val senderDomains: String = "",
    /** The server's identities, as JSON `[[id, name, email], …]`. */
    val identities: String = "[]",
    /** Mailbox ids that notify; the default `*inbox` means whichever folder has the inbox role. */
    val notifyFolders: String = "*inbox",
) {
    /** The ids of the folders that notify. */
    fun notifyIds(mailboxes: List<Mailbox>): Set<String> =
        if (notifyFolders == "*inbox") mailboxes.filter { it.role == "inbox" }.map { it.id }.toSet()
        else notifyFolders.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun withNotify(mailboxes: List<Mailbox>, id: String, on: Boolean): Prefs {
        val ids = notifyIds(mailboxes).toMutableSet()
        if (on) ids += id else ids -= id
        return copy(notifyFolders = ids.joinToString(","))
    }
    val signedIn get() = user.isNotBlank() && password.isNotEmpty() && accountId != null
    val host get() = server.removePrefix("https://").removePrefix("http://").trimEnd('/')
    val imageDomainList get() = domains(imageDomains)
    val senderDomainList get() = domains(senderDomains)
    fun identityList(): List<Identity> = runCatching {
        val a = org.json.JSONArray(identities)
        List(a.length()) { i -> val e = a.getJSONArray(i); Identity(e.getString(0), e.getString(1).ifEmpty { null }, e.getString(2)) }
    }.getOrDefault(emptyList())

    companion object {
        fun domains(s: String): Set<String> = s.split(',', ';', ' ', '\n').map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }.toSet()
        fun identitiesJson(l: List<Identity>): String = org.json.JSONArray(l.map { org.json.JSONArray(listOf(it.id, it.name ?: "", it.email)) }).toString()
    }
}

object Store {
    private lateinit var sp: SharedPreferences
    lateinit var crypto: Crypto
        private set
    val state = MutableStateFlow(Prefs())

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        crypto = Crypto()
        fun secret(k: String) = sp.getString(k, null)?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
        state.value = Prefs(
            server = sp.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER,
            user = sp.getString("user", "") ?: "",
            password = secret("pw") ?: "",
            accountId = sp.getString("accountId", null),
            identityId = sp.getString("identityId", null),
            fromName = sp.getString("fromName", null),
            fromEmail = sp.getString("fromEmail", null),
            night = sp.getBoolean("night", true),
            accent = sp.getString("accent", DEFAULT_ACCENT) ?: DEFAULT_ACCENT,
            notify = sp.getBoolean("notify", true),
            passphrase = secret("pp"),
            rememberPassphrase = sp.getBoolean("rememberPp", true),
            signByDefault = sp.getBoolean("sign", true),
            lastSyncOk = sp.getLong("lastSyncOk", 0),
            lastAttempt = sp.getLong("lastAttempt", 0),
            lastError = sp.getString("lastError", null),
            notifiedUpTo = sp.getLong("notifiedUpTo", 0),
            imageDomains = sp.getString("imageDomains", "") ?: "",
            senderDomains = sp.getString("senderDomains", "") ?: "",
            identities = sp.getString("identities", "[]") ?: "[]",
            notifyFolders = sp.getString("notifyFolders", "*inbox") ?: "*inbox",
        )
    }

    fun update(f: (Prefs) -> Prefs): Prefs = synchronized(this) {
        val old = state.value
        val p = f(old)
        if (p == old) return p
        state.value = p
        val e = sp.edit()
            .putString("server", p.server).putString("user", p.user)
            .putString("accountId", p.accountId).putString("identityId", p.identityId)
            .putString("fromName", p.fromName).putString("fromEmail", p.fromEmail)
            .putBoolean("night", p.night).putString("accent", p.accent).putBoolean("notify", p.notify)
            .putBoolean("rememberPp", p.rememberPassphrase).putBoolean("sign", p.signByDefault)
            .putLong("lastSyncOk", p.lastSyncOk).putLong("lastAttempt", p.lastAttempt).putString("lastError", p.lastError)
            .putLong("notifiedUpTo", p.notifiedUpTo)
            .putString("imageDomains", p.imageDomains).putString("senderDomains", p.senderDomains).putString("identities", p.identities)
            .putString("notifyFolders", p.notifyFolders)
        if (p.password != old.password || !sp.contains("pw")) {
            if (p.password.isEmpty()) e.remove("pw") else e.putString("pw", crypto.encrypt(p.password))
        }
        if (p.passphrase != old.passphrase) {
            if (p.passphrase == null) e.remove("pp") else e.putString("pp", crypto.encrypt(p.passphrase))
        }
        e.apply()
        p
    }

    /** Forget the account and everything synced from it; keys and the look stay. */
    fun signOut() {
        update { Prefs(server = it.server, night = it.night, accent = it.accent, notify = it.notify, passphrase = it.passphrase, rememberPassphrase = it.rememberPassphrase, signByDefault = it.signByDefault, imageDomains = it.imageDomains, senderDomains = it.senderDomains, notifyFolders = it.notifyFolders) }
        Db.clearMail()
    }
}

fun now() = System.currentTimeMillis()
