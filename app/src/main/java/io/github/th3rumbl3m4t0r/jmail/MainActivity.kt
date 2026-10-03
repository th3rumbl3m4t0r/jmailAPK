package io.github.th3rumbl3m4t0r.jmail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var mailto by mutableStateOf<String?>(null)
    private var sharedKey by mutableStateOf<String?>(null)

    /** A SEND intent: key text, or a file we read as text. */
    private fun take(intent: Intent?) {
        if (intent == null) return
        mailto = intent.dataString?.takeIf { it.startsWith("mailto:") }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            sharedKey = text ?: uri?.let { u -> runCatching { contentResolver.openInputStream(u)?.use { String(it.readBytes(), Charsets.ISO_8859_1) } }.getOrNull() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Db.init(this)
        Sync.ctx = applicationContext
        Notify.channel(this)
        SyncWorker.schedule(this)
        bars(Store.state.value.night)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        take(intent)
        setContent {
            val p by Store.state.collectAsState()
            LaunchedEffect(p.night) { bars(p.night) }
            X11Theme(p.night, p.accent) { Screen(mailto, sharedKey, onMailtoTaken = { mailto = null }, onKeyTaken = { sharedKey = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** Status and navigation bar icons follow the app's night / day, not the system's. */
    private fun bars(night: Boolean) {
        val t = android.graphics.Color.TRANSPARENT
        val style = if (night) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        enableEdgeToEdge(style, style)
    }

    override fun onStart() {
        super.onStart()
        Sync.trigger("open", 5_000)
    }
}

/** Where the screen is: the list, one message, or the editor. */
sealed class Page {
    data object List : Page()
    data class Read(val id: String) : Page()
    data class Write(val draft: ComposeDraft) : Page()
}

@Composable
private fun Screen(mailto: String?, sharedKey: String?, onMailtoTaken: () -> Unit, onKeyTaken: () -> Unit) {
    val p by Store.state.collectAsState()
    val running by Sync.running.collectAsState()
    val dbv by Db.version.collectAsState()
    var page by remember { mutableStateOf<Page>(Page.List) }
    var mailboxId by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var keyText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedKey) {
        if (sharedKey != null) {
            keyText = sharedKey
            settings = true
            onKeyTaken()
        }
    }
    var folders by remember { mutableStateOf(false) }
    var want by rememberSaveable { mutableIntStateOf(Sync.PAGE) }
    val mailboxes = remember(dbv) { Db.mailboxes() }
    val current = mailboxes.firstOrNull { it.id == mailboxId } ?: mailboxes.firstOrNull { it.role == "inbox" }

    BackHandler(enabled = page != Page.List) { page = Page.List }
    LaunchedEffect(current?.id, want, p.signedIn) { if (p.signedIn && current != null) Sync.loadMailbox(current.id, want) }
    LaunchedEffect(mailto) {
        if (mailto != null && p.signedIn) {
            page = Page.Write(ComposeDraft.fromMailto(mailto))
            onMailtoTaken()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Topbar(p, page, current, onBack = { page = Page.List }, onWrite = { page = Page.Write(ComposeDraft()) }, onSettings = { settings = true }, onFolders = { folders = true })
            Statusbar(p, running, current)
            when {
                !p.signedIn -> Login(Modifier.weight(1f))
                page is Page.Read -> ReaderView(
                    (page as Page.Read).id,
                    textOnly = current?.role == "junk",
                    onBack = { page = Page.List },
                    onWrite = { page = Page.Write(it) },
                )
                page is Page.Write -> ComposeView((page as Page.Write).draft, onDone = { page = Page.List })
                else -> MailList(current, dbv, want, onOpen = { page = Page.Read(it) }, onMore = { want += Sync.PAGE })
            }
        }
        if (folders) FolderPick(mailboxes, current?.id, onPick = { mailboxId = it; want = Sync.PAGE; folders = false }, onDone = { folders = false })
        if (settings) SettingsView(p, keysFirst = keyText, onDone = { settings = false; keyText = null })
    }
}

/** #topbar: brand, write + settings; the second row depends on the page. */
@Composable
private fun Topbar(p: Prefs, page: Page, current: Mailbox?, onBack: () -> Unit, onWrite: () -> Unit, onSettings: () -> Unit, onFolders: () -> Unit) {
    val x = LocalX.current
    Column(
        Modifier.fillMaxWidth().background(x.panel).bottomLine(x.border)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("MAIL", color = x.accent, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("// ${if (p.signedIn) p.host else "no account"}", Modifier.weight(1f), color = x.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.signedIn && page == Page.List) XButton("write", onClick = onWrite)
            if (page != Page.List) XButton("back", onClick = onBack)
            XButton("settings", onClick = onSettings)
        }
        if (p.signedIn && page == Page.List) Row(Modifier.fillMaxWidth().padding(start = 9.dp, end = 9.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            XButton((current?.name ?: "folders") + if (current != null && current.unread > 0) " · ${current.unread}" else "", onClick = onFolders, Modifier.weight(1f), on = true)
            XButton("refresh", onClick = { Sync.trigger("button", 0) })
        }
    }
}

/** .statusbar: the sync state, colour-coded. */
@Composable
private fun Statusbar(p: Prefs, running: Boolean, current: Mailbox?) {
    val x = LocalX.current
    val now = ticker()
    Row(
        Modifier.fillMaxWidth().background(x.titlebar).bottomLine(x.border)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val small = 11.sp
        when {
            !p.signedIn -> Text("not signed in", color = x.dim, fontSize = small)
            running -> Text("syncing", color = x.warn, fontSize = small)
            p.lastSyncOk > 0 -> Text("synced ${ago(p.lastSyncOk, now)}", color = x.ok, fontSize = small)
            else -> Text("never synced", color = x.dim, fontSize = small)
        }
        if (p.signedIn && p.lastError != null && !running) {
            Text(p.lastError, Modifier.weight(1f, fill = false), color = x.danger, fontSize = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        if (p.signedIn && Keys.ours().isEmpty()) Text("no pgp key", color = x.dim, fontSize = small)
        else if (p.signedIn && current != null) Text("${current.total} in ${current.name.lowercase()}", color = x.dim, fontSize = small, maxLines = 1)
    }
}

// ---- the list ----

@Composable
private fun MailList(current: Mailbox?, dbv: Int, want: Int, onOpen: (String) -> Unit, onMore: () -> Unit) {
    val x = LocalX.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rows = remember(dbv, current?.id, results, want) {
        val r = results
        if (r != null) r.mapNotNull { Db.email(it) } else current?.let { Db.emails(it.id, want) } ?: emptyList()
    }
    val total = remember(dbv, current?.id) { current?.let { Db.state("total:${it.id}")?.toIntOrNull() } }
    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(query, { query = it; if (it.isBlank()) results = null }, Modifier.weight(1f))
                XButton(if (searching) "…" else "search", enabled = query.isNotBlank() && !searching, onClick = {
                    searching = true
                    scope.launch {
                        try {
                            results = Sync.search(current?.id, query)
                        } catch (e: Exception) {
                            Store.update { it.copy(lastError = Sync.describe(e)) }
                        }
                        searching = false
                    }
                })
            }
        }
        item {
            val title = when {
                results != null -> "search · ${results!!.size}"
                current == null -> "no folder"
                else -> "${current.name} · ${current.unread} unread" + (total?.let { " · $it" } ?: "")
            }
            Titlebar(title, Modifier.sideLines(x.border).padding(top = 1.dp))
        }
        if (rows.isEmpty()) item {
            Text(if (results != null) "nothing found" else "nothing here", Modifier.fillMaxWidth().background(x.panel).sideLines(x.border).bottomLine(x.border).padding(10.dp), color = x.dim, fontSize = 12.sp)
        }
        items(rows, key = { it.id }) { e ->
            Box(Modifier.fillMaxWidth().background(x.panel).sideLines(x.border)) { MailRow(e) { onOpen(e.id) } }
        }
        if (results == null && current != null && (total == null || rows.size < total)) item {
            XButton("more", onClick = onMore, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun MailRow(e: Email, onClick: () -> Unit) {
    val x = LocalX.current
    val unread = !e.seen
    Row(
        Modifier.fillMaxWidth().background(if (unread) x.accentSoft else Color.Transparent).bottomLine(x.border).clickable(onClick = onClick)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val who = if (e.draft) "draft" else e.from.firstOrNull()?.short ?: "?"
                Text(who, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (unread) FontWeight.Bold else null, color = if (e.draft) x.warn else x.fg)
                Text(fmtShort(e.receivedAt), fontSize = 11.sp, color = if (unread) x.accent else x.dim)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(e.subject.ifBlank { "(no subject)" }, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (unread) FontWeight.Bold else null)
                if (e.flagged) Text("flag", fontSize = 10.sp, color = x.warn)
                if (e.answered) Text("re", fontSize = 10.sp, color = x.dim)
                if (e.encrypted) Text("pgp", fontSize = 10.sp, color = x.accent)
                else if (e.signed) Text("sig", fontSize = 10.sp, color = x.dim)
                if (e.hasAttachment && !e.encrypted && !e.signed) Text("att", fontSize = 10.sp, color = x.dim)
            }
            val line = if (e.encrypted) "encrypted" else e.preview.replace('\n', ' ').trim()
            if (line.isNotEmpty()) Text(line, fontSize = 11.sp, lineHeight = 14.sp, color = x.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The folder list as an overlay. */
@Composable
fun FolderPick(mailboxes: List<Mailbox>, currentId: String?, onPick: (String) -> Unit, onDone: () -> Unit) {
    val x = LocalX.current
    Overlay(onDone) {
        Window("folders") {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                mailboxes.forEach { m ->
                    val on = m.id == currentId
                    Row(
                        Modifier.fillMaxWidth().background(if (on) x.accentSoft else Color.Transparent).bottomLine(x.border)
                            .clickable { onPick(m.id) }.padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.name, Modifier.weight(1f), fontSize = 13.sp, fontWeight = if (m.unread > 0) FontWeight.Bold else null)
                        if (m.unread > 0) Text("${m.unread}", fontSize = 12.sp, color = x.accent)
                        Text("  ${m.total}", Modifier.width(56.dp), fontSize = 11.sp, color = x.dim)
                    }
                }
            }
            XButton("close", onClick = onDone, Modifier.padding(top = 10.dp))
        }
    }
}

/** No account yet: the sign-in form is the page. */
@Composable
private fun Login(modifier: Modifier) {
    val x = LocalX.current
    val p by Store.state.collectAsState()
    var server by rememberSaveable { mutableStateOf(p.server) }
    var user by rememberSaveable { mutableStateOf(p.user) }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(8.dp),
    ) {
        Window("account") {
            Text(
                "Signs in to a JMAP mail server (e.g. Stalwart): its address, like mail.example.com. Use an app password if the account has one; it is kept on the phone only, encrypted. " +
                    "Mail the server keeps encrypted at rest is read with your PGP key (settings → keys).",
                fontSize = 12.sp, lineHeight = 16.sp, color = x.dim,
            )
            Label("server", Modifier.padding(top = 10.dp))
            Field(server, { server = it }, Modifier.padding(top = 4.dp))
            Label("user", Modifier.padding(top = 8.dp))
            Field(user, { user = it }, Modifier.padding(top = 4.dp))
            Label("password", Modifier.padding(top = 8.dp))
            PasswordField(pass, { pass = it }, Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(msg ?: "", Modifier.weight(1f), color = if (busy) x.warn else x.danger, fontSize = 12.sp, lineHeight = 16.sp)
                XButton(if (busy) "signing in" else "sign in", enabled = !busy && server.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(), primary = true, onClick = {
                    busy = true
                    msg = "asking the server"
                    scope.launch {
                        try {
                            msg = Sync.signIn(server, user.trim(), pass)
                        } catch (e: Exception) {
                            msg = Sync.describe(e)
                        }
                        busy = false
                    }
                })
            }
        }
    }
}
