package io.github.th3rumbl3m4t0r.jmail

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val REMOTE = Regex("(?i)(\\bsrc|\\bbackground|\\bposter)\\s*=\\s*[\"']?\\s*(https?:)?//|url\\(\\s*[\"']?\\s*(https?:)?//|<link\\b[^>]*\\bhref")

/** True if rendering the HTML would make the device fetch something from the network. */
fun hasRemoteContent(html: String) = REMOTE.containsMatchIn(html)

/**
 * One message: fetched (and decrypted) when the page opens; actions at the bottom.
 * HTML is rendered unless [textOnly] (the junk folder) or the reader is switched to text;
 * online content stays blocked until asked for, or when the sender's domain is allowed.
 */
@Composable
fun ReaderView(id: String, textOnly: Boolean, onBack: () -> Unit, onWrite: (ComposeDraft) -> Unit) {
    val x = LocalX.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p by Store.state.collectAsState()
    val dbv by Db.version.collectAsState()
    var opened by remember(id) { mutableStateOf<Opened?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var needKey by remember(id) { mutableStateOf<NeedKey?>(null) }
    var reload by remember(id) { mutableIntStateOf(0) }
    var confirm by remember(id) { mutableStateOf(false) }
    var moving by remember(id) { mutableStateOf(false) }
    var msg by remember(id) { mutableStateOf<String?>(null) }
    var showHtml by remember(id, textOnly) { mutableStateOf(!textOnly) }
    var headers by remember(id) { mutableStateOf<List<Pair<String, String>>?>(null) }
    var showHeaders by remember(id) { mutableStateOf(false) }
    var online by remember(id) { mutableStateOf(false) }
    val header = remember(id, dbv) { Db.email(id) }
    val h = opened?.full?.header ?: header
    val senderDomain = h?.from?.firstOrNull()?.email?.substringAfter('@', "")?.lowercase().orEmpty()
    val allowed = senderDomain.isNotEmpty() && senderDomain in p.imageDomainList

    LaunchedEffect(id, reload) {
        error = null
        needKey = null
        try {
            opened = Reader.open(id)
            Sync.setSeen(id, true)
        } catch (e: NeedKey) {
            needKey = e
        } catch (e: Exception) {
            error = Sync.describe(e)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Window("message") {
            Text(h?.subject?.ifBlank { "(no subject)" } ?: "", fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            if (h != null) {
                Text("from  ${h.from.joinToString { it.full }}", Modifier.padding(top = 4.dp), fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
                Text("to    ${h.to.joinToString { it.full }}", fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
                opened?.full?.cc?.takeIf { it.isNotEmpty() }?.let { Text("cc    ${it.joinToString { a -> a.full }}", fontSize = 12.sp, lineHeight = 16.sp, color = x.dim) }
                Text("date  ${fmtFull(h.sentAt ?: h.receivedAt)}", fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
            }
            opened?.security?.let { s ->
                val line = buildString {
                    if (s.encrypted) append("encrypted")
                    if (s.signed) {
                        if (isNotEmpty()) append(" · ")
                        append(if (s.valid) "signed by ${s.signer}" else "signed")
                    }
                }
                Text(line, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = if (s.signed && !s.valid) x.warn else x.ok)
                s.note?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, color = if (it.startsWith("BAD")) x.danger else x.warn) }
            }
        }

        when {
            needKey != null -> KeyNeeded(needKey!!, onUnlocked = { Reader.forget(id); reload++ })
            error != null -> Window("could not open") {
                Text(error!!, color = x.danger, fontSize = 12.sp, lineHeight = 16.sp)
                XButton("retry", onClick = { Reader.forget(id); reload++ }, Modifier.padding(top = 8.dp))
            }
            opened == null -> Window("body") { Text("fetching", color = x.dim, fontSize = 12.sp) }
            else -> {
                val o = opened!!
                val html = o.html
                val remote = remember(html) { html?.let { hasRemoteContent(it) } ?: false }
                Window("body") {
                    if (html != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            XButton("html", onClick = { showHtml = true }, on = showHtml)
                            XButton("text", onClick = { showHtml = false }, on = !showHtml)
                            Spacer(Modifier.weight(1f))
                            if (showHtml && remote && !online && !allowed) {
                                XButton("load online content", onClick = { online = true })
                            }
                        }
                        if (showHtml && remote) {
                            if (!online && !allowed) {
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Images and other online content are blocked; loading them tells the sender you opened this.", Modifier.weight(1f), fontSize = 11.sp, lineHeight = 15.sp, color = x.dim)
                                    if (senderDomain.isNotEmpty()) XButton("always for $senderDomain", onClick = {
                                        Store.update { it.copy(imageDomains = (it.imageDomainList + senderDomain).joinToString(", ")) }
                                    })
                                }
                            } else Text(if (allowed) "online content from $senderDomain loads right away (settings → html mail)" else "online content loaded", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = x.dim)
                        }
                        if (textOnly && showHtml) Text("junk folder: shown as text by default", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = x.dim)
                    }
                    if (html != null && showHtml) HtmlView(html, o.inline, online || allowed, Modifier.padding(top = 6.dp))
                    else SelectionContainer { Text(o.text.ifBlank { "(empty)" }, Modifier.padding(top = if (html != null) 6.dp else 0.dp), fontSize = 13.sp, lineHeight = 18.sp, color = if (o.text.isBlank()) x.dim else x.fg) }
                }
                if (o.attachments.isNotEmpty()) Window("attachments · ${o.attachments.size}") {
                    o.attachments.forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontSize = 13.sp, maxLines = 1)
                                Text("${a.type} · ${fmtSize(a.size)}", fontSize = 11.sp, color = x.dim)
                            }
                            XButton("open", onClick = {
                                scope.launch {
                                    try {
                                        val bytes = Reader.bytes(a)
                                        withContext(Dispatchers.IO) {
                                            val dir = File(ctx.cacheDir, "open").apply { mkdirs() }
                                            val f = File(dir, a.name.replace('/', '_'))
                                            f.writeBytes(bytes)
                                            val uri = FileProvider.getUriForFile(ctx, "io.github.th3rumbl3m4t0r.jmail.files", f)
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    } catch (e: Exception) {
                                        msg = "open: ${e.message ?: "no app for ${a.type}"}"
                                    }
                                }
                            })
                            XButton("save", onClick = {
                                scope.launch {
                                    try {
                                        val bytes = Reader.bytes(a)
                                        withContext(Dispatchers.IO) { saveToDownloads(ctx, a.name, a.type, bytes) }
                                        msg = "saved ${a.name} to Downloads"
                                    } catch (e: Exception) {
                                        msg = "save: ${e.message}"
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }

        if (h != null) Window("actions") {
            val o = opened
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                XButton("reply", enabled = o != null, onClick = { onWrite(ComposeDraft.reply(o!!, all = false)) }, modifier = Modifier.weight(1f))
                XButton("reply all", enabled = o != null, onClick = { onWrite(ComposeDraft.reply(o!!, all = true)) }, modifier = Modifier.weight(1f))
                XButton("forward", enabled = o != null, onClick = { onWrite(ComposeDraft.forward(o!!)) }, modifier = Modifier.weight(1f))
            }
            if (h.draft) XButton("edit draft", enabled = o != null, onClick = { onWrite(ComposeDraft.fromDraft(o!!)) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), primary = true)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                XButton("headers", onClick = {
                    showHeaders = true
                    if (headers == null) scope.launch {
                        try {
                            headers = withContext(Dispatchers.IO) { Sync.client().headers(id) }
                        } catch (e: Exception) {
                            headers = listOf("error" to Sync.describe(e))
                        }
                    }
                }, Modifier.weight(1f))
                XButton("unread", onClick = { Sync.setSeen(id, false); onBack() }, Modifier.weight(1f))
                XButton(if (h.flagged) "unflag" else "flag", onClick = { Sync.setFlagged(id, !h.flagged) }, Modifier.weight(1f), on = h.flagged)
                XButton("move", onClick = { moving = true }, Modifier.weight(1f))
                val inTrash = Db.mailboxByRole("trash")?.let { it.id in h.mailboxIds } ?: false
                if (inTrash && !confirm) XButton("delete", onClick = { confirm = true }, Modifier.weight(1f))
                else if (inTrash) DangerButton("sure?", onClick = { Sync.trash(id); onBack() }, Modifier.weight(1f))
                else XButton("trash", onClick = { Sync.trash(id); onBack() }, Modifier.weight(1f))
            }
            msg?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = if (it.startsWith("saved")) x.ok else x.danger) }
        }
        Spacer(Modifier.padding(4.dp))
    }
    if (moving) FolderPick(Db.mailboxes(), header?.mailboxIds?.firstOrNull(), onPick = { Sync.move(id, it); moving = false; onBack() }, onDone = { moving = false })
    if (showHeaders) Overlay(onDismiss = { showHeaders = false }) {
        Window("headers") {
            val hs = headers
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                if (hs == null) Text("fetching", color = x.dim, fontSize = 12.sp)
                else SelectionContainer {
                    Text(hs.joinToString("\n") { (n, v) -> "$n: $v" }, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            XButton("close", onClick = { showHeaders = false }, Modifier.padding(top = 10.dp), primary = true)
        }
    }
    }
}

/** The body can't be read until the key is there or unlocked. */
@Composable
fun KeyNeeded(need: NeedKey, onUnlocked: () -> Unit) {
    val x = LocalX.current
    var pp by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Window(if (need.passphrase) "key locked" else "no key") {
        Text(need.message ?: "", fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
        if (need.passphrase) {
            Label("passphrase", Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PasswordField(pp, { pp = it; wrong = false }, Modifier.weight(1f))
                XButton("unlock", enabled = pp.isNotEmpty(), primary = true, onClick = { if (Keys.tryPassphrase(pp)) onUnlocked() else wrong = true })
            }
            if (wrong) Text("that's not it", Modifier.padding(top = 4.dp), color = x.danger, fontSize = 12.sp)
            val p = Store.state.value
            Text(if (p.rememberPassphrase) "The passphrase is kept on the phone, encrypted (settings → keys to change that)." else "Kept for this session only.", Modifier.padding(top = 6.dp), fontSize = 11.sp, lineHeight = 15.sp, color = x.dim)
        }
    }
}

private fun saveToDownloads(ctx: android.content.Context, name: String, type: String, bytes: ByteArray) {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, type)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("Downloads refused the file")
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
    } else {
        @Suppress("DEPRECATION")
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        File(dir, name).writeBytes(bytes)
    }
}
