package io.github.th3rumbl3m4t0r.jmail

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/** What the editor starts from. */
data class ComposeDraft(
    val draftId: String? = null,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val attachments: List<Attachment> = emptyList(),
    /** Attachments still on the server (a forward): fetched when the editor opens. */
    val fetch: List<OpenedAttachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: String? = null,
    /** null: encrypt when every recipient has a key. */
    val encrypt: Boolean? = null,
    /** `Name <addr>` to write from; null: the account's main identity. */
    val from: String? = null,
) {
    companion object {
        fun fromMailto(uri: String): ComposeDraft {
            val rest = uri.removePrefix("mailto:")
            val addr = rest.substringBefore('?')
            val params = rest.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=').lowercase() to runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("") }
            return ComposeDraft(to = runCatching { URLDecoder.decode(addr, "UTF-8") }.getOrDefault(addr), cc = params["cc"] ?: "", subject = params["subject"] ?: "", body = params["body"] ?: "")
        }

        private fun quote(o: Opened): String {
            val h = o.full.header
            return "On ${fmtFull(h.sentAt ?: h.receivedAt)}, ${h.from.firstOrNull()?.full ?: "someone"} wrote:\n" + o.text.lines().joinToString("\n") { "> $it" }
        }

        fun reply(o: Opened, all: Boolean): ComposeDraft {
            val h = o.full.header
            val me = Store.state.value.fromEmail?.lowercase()
            val to = o.full.replyTo.ifEmpty { h.from }
            val cc = if (all) (h.to + o.full.cc).filter { a -> a.email.lowercase() != me && to.none { it.email.equals(a.email, true) } } else emptyList()
            return ComposeDraft(
                from = Senders.replyFrom(h, o.full.cc),
                to = to.joinToString(", ") { it.full },
                cc = cc.distinctBy { it.email.lowercase() }.joinToString(", ") { it.full },
                subject = if (h.subject.startsWith("re:", true)) h.subject else "Re: ${h.subject}",
                body = "\n\n" + quote(o),
                inReplyTo = o.full.messageId.firstOrNull()?.let { "<$it>" },
                references = (o.full.references + o.full.messageId).distinct().joinToString(" ") { "<$it>" }.ifEmpty { null },
                encrypt = o.security?.encrypted,
            )
        }

        fun forward(o: Opened): ComposeDraft {
            val h = o.full.header
            val head = "---------- forwarded message ----------\nfrom: ${h.from.joinToString { it.full }}\ndate: ${fmtFull(h.sentAt ?: h.receivedAt)}\nsubject: ${h.subject}\nto: ${h.to.joinToString { it.full }}\n\n"
            return ComposeDraft(
                from = Senders.replyFrom(h, o.full.cc),
                subject = if (h.subject.startsWith("fwd:", true)) h.subject else "Fwd: ${h.subject}",
                body = "\n\n$head${o.text}",
                attachments = o.attachments.filter { it.bytes != null }.map { Attachment(it.name, it.type, it.bytes!!) },
                fetch = o.attachments.filter { it.bytes == null },
                encrypt = o.security?.encrypted,
            )
        }

        fun fromDraft(o: Opened): ComposeDraft {
            val h = o.full.header
            return ComposeDraft(
                draftId = h.id,
                from = h.from.firstOrNull()?.full,
                to = h.to.joinToString(", ") { it.full },
                cc = o.full.cc.joinToString(", ") { it.full },
                subject = h.subject,
                body = o.text,
                attachments = o.attachments.filter { it.bytes != null }.map { Attachment(it.name, it.type, it.bytes!!) },
                fetch = o.attachments.filter { it.bytes == null },
                inReplyTo = o.full.inReplyTo.firstOrNull()?.let { "<$it>" },
                references = o.full.references.joinToString(" ") { "<$it>" }.ifEmpty { null },
            )
        }
    }
}

/** The editor: a page of its own. Encrypts when every recipient has a key, signs when we have one. */
@Composable
fun ComposeView(initial: ComposeDraft, onDone: () -> Unit) {
    val x = LocalX.current
    val ctx = LocalContext.current
    val p by Store.state.collectAsState()
    val dbv by Db.version.collectAsState()
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf(initial) }
    var fromText by remember { mutableStateOf(initial.from ?: Senders.default()) }
    val fromAddr = remember(fromText) { Address.parse(fromText) }
    val fromWarn = remember(fromText, dbv) {
        when {
            fromAddr == null -> "from: that's not an address"
            !Senders.known(fromAddr.email) -> "${fromAddr.email.substringAfter('@')} isn't a domain of yours as far as the app knows; the server may still send it (settings → sending)"
            else -> null
        }
    }
    var showBcc by remember { mutableStateOf(initial.bcc.isNotEmpty()) }
    var wantEncrypt by remember { mutableStateOf(initial.encrypt ?: true) }
    var sign by remember { mutableStateOf(p.signByDefault) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var needPp by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val haveKey = remember(dbv) { Keys.ours().isNotEmpty() }
    val rcpts = remember(d.to, d.cc, d.bcc) { Address.parseList(listOf(d.to, d.cc, d.bcc).joinToString(",")) }
    val missing = remember(rcpts, dbv) { rcpts.filter { Keys.forEmail(it.email) == null }.map { it.email } }
    val canEncrypt = rcpts.isNotEmpty() && missing.isEmpty()
    val encrypt = wantEncrypt && canEncrypt

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { readAttachment(ctx, it) } }
            d = d.copy(attachments = d.attachments + added)
        }
    }

    LaunchedEffect(initial) {
        if (initial.fetch.isNotEmpty()) {
            busy = "fetching attachments"
            try {
                val got = initial.fetch.map { a -> Attachment(a.name, a.type, Reader.bytes(a)) }
                d = d.copy(attachments = d.attachments + got, fetch = emptyList())
            } catch (e: Exception) {
                msg = "attachments: ${Sync.describe(e)}"
            }
            busy = null
        }
    }

    fun outgoing() = Sync.Outgoing(
        draftId = d.draftId,
        from = fromAddr ?: Address(p.fromName, p.fromEmail ?: ""),
        to = Address.parseList(d.to), cc = Address.parseList(d.cc), bcc = Address.parseList(d.bcc),
        subject = d.subject.trim(), body = d.body, attachments = d.attachments,
        encrypt = encrypt, sign = sign && haveKey,
        inReplyTo = d.inReplyTo, references = d.references,
    )

    fun go(what: String, block: suspend () -> Unit) {
        busy = what
        msg = null
        scope.launch {
            try {
                block()
                onDone()
            } catch (e: NeedKey) {
                needPp = true
            } catch (e: Exception) {
                msg = Sync.describe(e)
            }
            busy = null
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(8.dp),
    ) {
        Window(if (d.draftId != null) "draft" else "write") {
            Label("from")
            Field(fromText, { fromText = it }, Modifier.padding(top = 4.dp))
            fromWarn?.let { Text(it, Modifier.padding(top = 4.dp), fontSize = 11.sp, lineHeight = 15.sp, color = if (fromAddr == null) x.danger else x.warn) }
            Label("to", Modifier.padding(top = 6.dp))
            Field(d.to, { d = d.copy(to = it) }, Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("cc", Modifier.weight(1f))
                if (!showBcc) XButton("bcc", onClick = { showBcc = true })
            }
            Field(d.cc, { d = d.copy(cc = it) }, Modifier.padding(top = 4.dp))
            if (showBcc) {
                Label("bcc", Modifier.padding(top = 6.dp))
                Field(d.bcc, { d = d.copy(bcc = it) }, Modifier.padding(top = 4.dp))
            }
            Label("subject", Modifier.padding(top = 6.dp))
            Field(d.subject, { d = d.copy(subject = it) }, Modifier.padding(top = 4.dp))
            Label("message", Modifier.padding(top = 6.dp))
            Area(d.body, { d = d.copy(body = it) }, Modifier.padding(top = 4.dp), minLines = 8)

            Label("attachments · ${d.attachments.size}", Modifier.padding(top = 8.dp))
            d.attachments.forEachIndexed { i, a ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${a.name} · ${fmtSize(a.bytes.size.toLong())}", Modifier.weight(1f), fontSize = 12.sp, maxLines = 1)
                    XButton("remove", onClick = { d = d.copy(attachments = d.attachments.filterIndexed { j, _ -> j != i }) })
                }
            }
            XButton("attach a file", onClick = { pick.launch(arrayOf("*/*")) }, Modifier.padding(top = 6.dp))

            Label("pgp", Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                XButton("encrypt", onClick = { wantEncrypt = !wantEncrypt }, Modifier.weight(1f), on = encrypt, enabled = canEncrypt)
                XButton("sign", onClick = { sign = !sign }, Modifier.weight(1f), on = sign && haveKey, enabled = haveKey)
            }
            Text(
                when {
                    rcpts.isEmpty() -> "Encryption needs a recipient with a key (settings → keys)."
                    missing.isNotEmpty() -> "No key for ${missing.joinToString()}: this goes in the clear."
                    encrypt -> "Encrypted to ${rcpts.joinToString { it.email }}" + if (haveKey) " and to you." else "; without a key of your own the Sent copy stays unreadable."
                    else -> "Not encrypted."
                } + if (!haveKey) " No key of yours: nothing is signed." else "",
                Modifier.padding(top = 4.dp), fontSize = 11.sp, lineHeight = 15.sp, color = if (missing.isNotEmpty() && wantEncrypt) x.warn else x.dim,
            )

            msg?.let { Text(it, Modifier.padding(top = 8.dp), color = x.danger, fontSize = 12.sp, lineHeight = 16.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (discard) DangerButton("sure?", onClick = onDone) else XButton("discard", onClick = { discard = true })
                XButton("save draft", enabled = busy == null, onClick = { go("saving") { Sync.saveDraft(outgoing()) } })
                Spacer(Modifier.weight(1f))
                Text(busy ?: "", color = x.warn, fontSize = 12.sp)
                XButton("send", enabled = busy == null && rcpts.isNotEmpty() && fromAddr != null, primary = true, onClick = { go("sending") { Sync.send(outgoing()) } })
            }
        }
    }
    if (needPp) Overlay(onDismiss = { needPp = false }) { KeyNeeded(NeedKey("Your key is locked. Unlock it to sign or encrypt.", true), onUnlocked = { needPp = false }) }
    }
}

private fun readAttachment(ctx: android.content.Context, uri: Uri): Attachment? {
    val r = ctx.contentResolver
    val name = r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment ?: "file"
    val type = r.getType(uri) ?: "application/octet-stream"
    val bytes = r.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return Attachment(name, type, bytes)
}
