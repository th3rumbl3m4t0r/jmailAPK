package io.github.th3rumbl3m4t0r.jmail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor holds. */
private data class Edit(val id: String?, val name: String, val text: String, val active: Boolean)

/** The server's Sieve scripts: list, editor, rule builder. Everything goes to the server at once. */
@Composable
fun SieveView(onDone: () -> Unit) {
    val x = LocalX.current
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf<List<SieveScript>?>(null) }
    var edit by remember { mutableStateOf<Edit?>(null) }
    var builder by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }

    fun run(what: String, block: suspend () -> String?) {
        busy = true
        scope.launch {
            try {
                val note = withContext(Dispatchers.IO) { block() }
                scripts = withContext(Dispatchers.IO) { Sync.client().sieveScripts() }
                ok = true
                msg = note
            } catch (e: Exception) {
                ok = false
                msg = "$what: ${Sync.describe(e)}"
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        run("list") {
            val j = Sync.client()
            if (!j.hasSieve) throw java.io.IOException("this server has no JMAP Sieve")
            null
        }
    }

    Overlay(onDone) {
        Window("sieve rules") {
            Text("Filters the server runs when mail arrives. One script is active at a time; the others are kept but do nothing.", fontSize = 12.sp, lineHeight = 16.sp, color = x.dim)
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                val list = scripts
                when {
                    list == null -> Text(if (msg == null) "fetching" else "", Modifier.padding(top = 8.dp), color = x.dim, fontSize = 12.sp)
                    list.isEmpty() -> Text("no scripts on the server yet", Modifier.padding(top = 8.dp), color = x.dim, fontSize = 12.sp)
                    else -> list.forEach { s ->
                        Column(Modifier.fillMaxWidth().bottomLine(x.border).padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name.ifBlank { "(unnamed)" }, Modifier.weight(1f), fontSize = 13.sp, fontWeight = if (s.active) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (s.active) Text("active", fontSize = 11.sp, color = x.accent)
                            }
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                XButton("open", enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            val text = withContext(Dispatchers.IO) { Sync.client().sieveScript(s.blobId) }
                                            edit = Edit(s.id, s.name, text.replace("\r\n", "\n"), s.active)
                                            msg = null
                                        } catch (e: Exception) {
                                            ok = false
                                            msg = "open: ${Sync.describe(e)}"
                                        }
                                        busy = false
                                    }
                                })
                                if (s.active) XButton("deactivate", enabled = !busy, onClick = { run("deactivate") { Sync.client().sieveActivate(null); "no script active now" } })
                                else XButton("activate", enabled = !busy, onClick = { run("activate") { Sync.client().sieveActivate(s.id); "${s.name} is active" } })
                                if (confirm == s.id) DangerButton("sure?", onClick = { confirm = null; run("delete") { Sync.client().sieveDelete(s.id); "deleted ${s.name}" } })
                                else XButton("delete", enabled = !busy, onClick = { confirm = s.id })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                XButton("new script", enabled = scripts != null, primary = scripts?.isEmpty() == true, onClick = {
                    edit = Edit(null, if (scripts.isNullOrEmpty()) "rules" else "rules ${scripts!!.size + 1}", Sieve.TEMPLATE, false)
                })
                Text(msg ?: if (busy) "talking to the server" else "", Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = if (msg != null && !ok) x.danger else if (msg != null) x.ok else x.dim, maxLines = 3, overflow = TextOverflow.Ellipsis)
                XButton("close", onClick = onDone)
            }
        }
    }

    edit?.let { e ->
        var emsg by remember(e.id) { mutableStateOf<String?>(null) }
        var eok by remember(e.id) { mutableStateOf(false) }
        var ebusy by remember(e.id) { mutableStateOf(false) }
        fun act(what: String, block: suspend () -> String?) {
            ebusy = true
            scope.launch {
                try {
                    val note = withContext(Dispatchers.IO) { block() }
                    eok = true
                    emsg = note
                } catch (ex: Exception) {
                    eok = false
                    emsg = "$what: ${Sync.describe(ex)}"
                }
                ebusy = false
            }
        }
        Overlay(onDismiss = { edit = null }) {
            Window(if (e.id == null) "new script" else "script · ${e.name}") {
                Label("name")
                Field(e.name, { edit = e.copy(name = it) }, Modifier.padding(top = 4.dp))
                Label("script", Modifier.padding(top = 8.dp))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Area(e.text, { edit = e.copy(text = it) }, Modifier.padding(top = 4.dp), minLines = 10)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XButton("add a rule", onClick = { builder = true })
                    XButton("validate", enabled = !ebusy, onClick = {
                        act("validate") { Sync.client().sieveValidate(e.text)?.let { throw java.io.IOException(it) } ?: "the script is valid" }
                    })
                }
                emsg?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 12.sp, lineHeight = 16.sp, color = if (eok) x.ok else x.danger) }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    XButton("cancel", onClick = { edit = null })
                    Spacer(Modifier.weight(1f))
                    XButton("save", enabled = !ebusy && e.name.isNotBlank(), onClick = {
                        act("save") {
                            Sync.client().sieveValidate(e.text)?.let { throw java.io.IOException(it) }
                            val id = Sync.client().sieveSave(e.id, e.name.trim(), e.text, activate = false)
                            scripts = Sync.client().sieveScripts()
                            edit = e.copy(id = id)
                            "saved"
                        }
                    })
                    XButton("save + activate", enabled = !ebusy && e.name.isNotBlank(), primary = true, onClick = {
                        act("save") {
                            Sync.client().sieveValidate(e.text)?.let { throw java.io.IOException(it) }
                            Sync.client().sieveSave(e.id, e.name.trim(), e.text, activate = true)
                            scripts = Sync.client().sieveScripts()
                            edit = null
                            msg = "${e.name.trim()} saved and active"
                            ok = true
                            null
                        }
                    })
                }
            }
        }
        if (builder) RuleBuilder(onInsert = { r -> edit = e.copy(text = Sieve.addRule(e.text, r)); builder = false }, onDone = { builder = false })
    }
}

/** One rule from a few choices; inserted into the script as text. */
@Composable
private fun RuleBuilder(onInsert: (Sieve.Rule) -> Unit, onDone: () -> Unit) {
    val x = LocalX.current
    var field by remember { mutableStateOf(Sieve.Field.FROM) }
    var header by remember { mutableStateOf("") }
    var match by remember { mutableStateOf(Sieve.Match.CONTAINS) }
    var value by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(Sieve.Action.MOVE) }
    var target by remember { mutableStateOf("") }
    var alsoRead by remember { mutableStateOf(false) }
    val folders = remember { Db.mailboxes() }
    fun path(m: Mailbox): String {
        val parts = ArrayList<String>()
        var cur: Mailbox? = m
        var guard = 0
        while (cur != null && guard++ < 10) {
            parts.add(0, cur.name)
            cur = cur.parentId?.let { pid -> folders.firstOrNull { it.id == pid } }
        }
        return parts.joinToString("/")
    }
    val needsTarget = action == Sieve.Action.MOVE || action == Sieve.Action.REDIRECT
    val complete = value.isNotBlank() && (field != Sieve.Field.HEADER || header.isNotBlank()) && (!needsTarget || target.isNotBlank())
    val rule = Sieve.Rule(field, header, match, value.trim(), action, target.trim(), alsoRead)

    Overlay(onDone) {
        Window("new rule") {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Label("when")
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Sieve.Field.entries.forEach { f -> XButton(f.label, onClick = { field = f }, Modifier.weight(1f), on = f == field) }
                }
                if (field == Sieve.Field.HEADER) Field(header, { header = it }, Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Sieve.Match.entries.forEach { m -> XButton(m.label, onClick = { match = m }, Modifier.weight(1f), on = m == match) }
                }
                Field(value, { value = it }, Modifier.padding(top = 4.dp))
                Text(
                    when (field) {
                        Sieve.Field.FROM -> "The sender's address, e.g. shop.example or news@example.org."
                        Sieve.Field.TO -> "Any To or Cc address: the alias you gave someone, for example."
                        Sieve.Field.SUBJECT -> "A word in the subject. `matches` takes * and ? wildcards."
                        Sieve.Field.HEADER -> "A header name above (List-Id, X-Spam-Status, …) and its value here."
                    },
                    Modifier.padding(top = 4.dp), fontSize = 11.sp, lineHeight = 15.sp, color = x.dim,
                )

                Label("then", Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Sieve.Action.MOVE, Sieve.Action.READ, Sieve.Action.FLAG).forEach { a -> XButton(a.label, onClick = { action = a }, Modifier.weight(1f), on = a == action) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Sieve.Action.DISCARD, Sieve.Action.REDIRECT).forEach { a -> XButton(a.label, onClick = { action = a }, Modifier.weight(1f), on = a == action) }
                    XButton("also mark read", onClick = { alsoRead = !alsoRead }, Modifier.weight(1f), on = alsoRead, enabled = action != Sieve.Action.READ && action != Sieve.Action.DISCARD)
                }
                if (action == Sieve.Action.MOVE) {
                    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        folders.filter { it.role != "sent" && it.role != "drafts" }.forEach { m -> val p = path(m); XButton(p, onClick = { target = p }, on = p == target) }
                    }
                    Field(target, { target = it }, Modifier.padding(top = 4.dp))
                }
                if (action == Sieve.Action.REDIRECT) Field(target, { target = it }, Modifier.padding(top = 4.dp))

                Label("sieve", Modifier.padding(top = 10.dp))
                Text(if (complete) Sieve.block(rule) else "…", Modifier.padding(top = 4.dp), fontSize = 11.sp, lineHeight = 15.sp, color = x.dim)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                XButton("cancel", onClick = onDone)
                Spacer(Modifier.weight(1f))
                XButton("insert", enabled = complete, primary = true, onClick = { onInsert(rule) })
            }
        }
    }
}
