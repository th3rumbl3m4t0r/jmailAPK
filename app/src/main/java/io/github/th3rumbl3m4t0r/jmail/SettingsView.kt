package io.github.th3rumbl3m4t0r.jmail

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId

/** Account, notifications, PGP, and the look (applied right away, like on the web pages). */
@Composable
fun SettingsView(p: Prefs, keysFirst: String? = null, onDone: () -> Unit) {
    val x = LocalX.current
    val ctx = LocalContext.current
    val dbv by Db.version.collectAsState()
    var hex by remember { mutableStateOf(p.accent) }
    var img by remember { mutableStateOf(p.imageDomains) }
    var snd by remember { mutableStateOf(p.senderDomains) }
    var confirmOut by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf(keysFirst != null) }
    var sieve by remember { mutableStateOf(false) }
    val mine = remember(dbv) { Keys.ours() }
    fun accent(a: String) = Store.update { it.copy(accent = a) }
    Overlay(onDone) {
        Window("settings") {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Label("account")
                Text("${p.user} // ${p.host}", Modifier.padding(top = 2.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.fromEmail != null) Text("sends as ${Mime.address(p.fromName, p.fromEmail)}", fontSize = 12.sp, color = x.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        p.lastSyncOk > 0 && p.lastError == null -> "synced ${ago(p.lastSyncOk, now())}"
                        p.lastError != null -> "last sync: ${p.lastError}"
                        else -> "not synced yet"
                    },
                    fontSize = 12.sp, color = if (p.lastError != null) x.danger else x.dim,
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XButton("sync now", onClick = { Sync.trigger("button", 0) })
                    if (confirmOut) DangerButton("sure? the keys stay", onClick = { Store.signOut(); Reader.forget(); onDone() })
                    else XButton("sign out", onClick = { confirmOut = true })
                }

                Label("notifications", Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    XButton(if (p.notify) "on" else "off", onClick = { Store.update { it.copy(notify = !it.notify) } }, Modifier.width(52.dp), on = p.notify)
                    Text("New mail, checked every 15 minutes and whenever the app opens. Per folder:", Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
                }
                val folders = remember(dbv) { Db.mailboxes() }
                val watched = p.notifyIds(folders)
                folders.forEach { m ->
                    val on = m.id in watched
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        XButton(if (on) "on" else "off", enabled = p.notify, onClick = { Store.update { it.withNotify(folders, m.id, !on) } }, modifier = Modifier.width(52.dp), on = on && p.notify)
                        Text(m.name, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (p.notify) x.fg else x.dim)
                    }
                }

                Label("pgp", Modifier.padding(top = 14.dp))
                Text(
                    if (mine.isEmpty()) "No key of yours yet. Import the private key the server encrypts your mail to."
                    else "Your key: ${mine.first().label}",
                    Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = if (mine.isEmpty()) x.warn else x.fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XButton("keys", onClick = { keys = true }, primary = mine.isEmpty())
                    XButton("sign by default", onClick = { Store.update { it.copy(signByDefault = !it.signByDefault) } }, on = p.signByDefault)
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XButton("remember passphrase", onClick = {
                        Store.update { it.copy(rememberPassphrase = !it.rememberPassphrase, passphrase = if (it.rememberPassphrase) null else it.passphrase) }
                    }, on = p.rememberPassphrase)
                    XButton("forget it now", enabled = p.passphrase != null || Keys.sessionPassphrase != null, onClick = {
                        Store.update { it.copy(passphrase = null) }
                        Keys.sessionPassphrase = null
                        Reader.forget()
                    })
                }
                Text("Decrypted mail is kept in memory only while the app runs; nothing decrypted is written to the phone.", Modifier.padding(top = 4.dp), fontSize = 11.sp, lineHeight = 15.sp, color = x.dim)

                Label("html mail", Modifier.padding(top = 14.dp))
                Text("Online content (images, tracking pixels) stays blocked until you load it. Senders from these domains load right away:", Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
                Field(img, { v -> img = v; Store.update { it.copy(imageDomains = v) } }, Modifier.padding(top = 4.dp))

                Label("sending", Modifier.padding(top = 14.dp))
                val mine = remember(dbv, p.senderDomains, p.identities) { Senders.domains() }
                Text(
                    "You can write from any address; a reply goes out from the address the mail was sent to. Domains the app treats as yours " +
                        "(the server's identities, the list below, and the domains received mail was addressed to): " +
                        mine.sorted().joinToString(", ").ifEmpty { "none yet" },
                    Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = x.dim,
                )
                Text("Your domains (comma separated), for the ones the app can't tell on its own:", Modifier.padding(top = 4.dp), fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
                Field(snd, { v -> snd = v; Store.update { it.copy(senderDomains = v) } }, Modifier.padding(top = 4.dp))

                Label("filters", Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    XButton("sieve rules", onClick = { sieve = true })
                    Text("Server-side rules (Sieve) the server runs on arriving mail: move, flag, discard, redirect.", Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
                }

                Label("look · accent", Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    XButton(if (p.night) "night" else "day", onClick = { Store.update { it.copy(night = !it.night) } }, Modifier.width(72.dp))
                    Field(hex, { v ->
                        hex = v
                        if (parseHex(v) != null) accent("#" + v.trim().removePrefix("#").lowercase())
                    }, Modifier.width(112.dp))
                    XButton("reset", onClick = {
                        hex = DEFAULT_ACCENT
                        Store.update { it.copy(night = true, accent = DEFAULT_ACCENT) }
                    })
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ACCENTS.forEach { a ->
                        val on = a == p.accent
                        Box(
                            Modifier.size(30.dp).background(parseHex(a)!!)
                                .border(if (on) 3.dp else 1.dp, if (on) x.fg else x.border)
                                .clickable { hex = a; accent(a) },
                        )
                    }
                }
                Text("mail ${BuildConfig.VERSION_NAME} · JMAP + OpenPGP, no OpenKeychain", Modifier.padding(top = 12.dp), fontSize = 11.sp, color = x.dim)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Spacer(Modifier.weight(1f))
                XButton("close", onClick = onDone, primary = true)
            }
        }
    }
    if (keys) KeysView(initialText = keysFirst ?: "", onDone = { keys = false })
    if (sieve) SieveView(onDone = { sieve = false })
}

/** Our key and the others', import by paste or file, export to the clipboard. */
@Composable
fun KeysView(initialText: String = "", onDone: () -> Unit) {
    val x = LocalX.current
    val ctx = LocalContext.current
    val dbv by Db.version.collectAsState()
    val all = remember(dbv) { Keys.all() }
    var text by remember { mutableStateOf(initialText) }
    var msg by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()

    fun import(bytes: ByteArray) {
        try {
            val added = Keys.import(bytes)
            Reader.forget()
            ok = true
            msg = "imported " + added.joinToString { (if (it.secret) "your key " else "") + it.label }
            text = ""
        } catch (e: Exception) {
            ok = false
            msg = e.message ?: "that's not a key"
        }
    }

    // a key shared to the app: no need to press anything
    LaunchedEffect(initialText) { if (initialText.isNotBlank()) import(initialText.toByteArray(Charsets.ISO_8859_1)) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let { import(it) }
    }

    @Composable
    fun KeyRow(k: Key) {
        Column(Modifier.fillMaxWidth().bottomLine(x.border).padding(vertical = 6.dp)) {
            Text(k.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (k.userIds.size > 1) Text(k.userIds.drop(1).joinToString(", "), fontSize = 11.sp, color = x.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Pgp.pretty(k.fingerprint), fontSize = 10.sp, color = x.dim)
            val created = Instant.ofEpochMilli(k.created).atZone(zone).toLocalDate()
            val exp = k.expires?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            Text("${k.algorithm} · since $created" + (exp?.let { " · ${if (k.expired) "expired" else "expires"} $it" } ?: ""), fontSize = 11.sp, color = if (k.expired) x.danger else x.dim)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                XButton("copy public key", onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("pgp key", Keys.export(k)))
                    ok = true
                    msg = "public key copied to the clipboard"
                })
                if (confirm == k.fingerprint) DangerButton("sure?", onClick = { Keys.remove(k.fingerprint); confirm = null; Reader.forget() })
                else XButton("remove", onClick = { confirm = k.fingerprint })
            }
        }
    }

    Overlay(onDone) {
        Window("pgp keys") {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Label("your key")
                val mine = all.filter { it.secret }
                if (mine.isEmpty()) Text("None yet. Import the private key the server encrypts your mail to (the one whose public key you gave Stalwart).", Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = x.warn)
                mine.forEach { KeyRow(it) }

                Label("other people's keys", Modifier.padding(top = 12.dp))
                val theirs = all.filter { !it.secret }
                if (theirs.isEmpty()) Text("None. With someone's public key you can send them encrypted mail and check their signatures.", Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
                theirs.forEach { KeyRow(it) }

                Label("import", Modifier.padding(top = 12.dp))
                Text("Paste an armored key (private or public) below, pick a .asc / .gpg file, or share a key file to this app.", Modifier.padding(top = 2.dp), fontSize = 11.sp, lineHeight = 15.sp, color = x.dim)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XButton("import pasted", enabled = text.isNotBlank(), primary = text.isNotBlank(), onClick = { import(text.toByteArray(Charsets.ISO_8859_1)) })
                    XButton("from a file", onClick = { pick.launch(arrayOf("*/*")) })
                }
                msg?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 12.sp, lineHeight = 16.sp, color = if (ok) x.ok else x.danger) }
                Area(text, { text = it }, Modifier.padding(top = 6.dp), minLines = 3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Spacer(Modifier.weight(1f))
                XButton("close", onClick = onDone, primary = true)
            }
        }
    }
}
