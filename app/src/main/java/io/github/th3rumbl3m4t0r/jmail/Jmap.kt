package io.github.th3rumbl3m4t0r.jmail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class JmapError(val code: Int, message: String) : IOException(message)

/** A method-level error in a response (`["error", {type, description}]`). */
class MethodError(val type: String, val description: String?) : IOException(description?.let { "$type: $it" } ?: type)

/** The parts of the JMAP session we use. URL templates are kept as paths and joined with our base. */
class Session(
    val accountId: String,
    val name: String,
    val api: String,
    val download: String,
    val upload: String,
    val eventSource: String?,
    val capabilities: Set<String>,
    val state: String?,
)

class QueryResult(val ids: List<String>, val total: Int?, val state: String)
class Changes(val created: List<String>, val updated: List<String>, val destroyed: List<String>, val newState: String, val hasMore: Boolean)

/**
 * Just enough JMAP (RFC 8620 / 8621 / 8621 submission) for one account over Basic auth.
 * Everything is synchronous; the callers run on IO.
 */
class Jmap(server: String, user: String, pass: String) {
    companion object {
        const val CORE = "urn:ietf:params:jmap:core"
        const val MAIL = "urn:ietf:params:jmap:mail"
        const val SUBMISSION = "urn:ietf:params:jmap:submission"
        const val SIEVE = "urn:ietf:params:jmap:sieve"
        private const val UA = "jmail/" + BuildConfig.VERSION_NAME

        val LIST_PROPERTIES = listOf("id", "blobId", "threadId", "mailboxIds", "keywords", "hasAttachment", "from", "to", "subject", "receivedAt", "sentAt", "size", "preview", "bodyStructure", "header:Delivered-To:asAddresses")
        val BODY_PROPERTIES = listOf("partId", "blobId", "size", "name", "type", "charset", "disposition", "cid", "subParts")
        private val FULL_PROPERTIES = LIST_PROPERTIES + listOf("cc", "replyTo", "messageId", "inReplyTo", "references", "bodyStructure", "bodyValues", "textBody", "htmlBody", "attachments")

        fun baseOf(server: String): HttpUrl {
            val s = server.trim().trimEnd('/').let { if (it.contains("://")) it else "https://$it" }
            return s.toHttpUrlOrNull() ?: throw IOException("bad server address")
        }

        /** `https://whatever/jmap/x` or `/jmap/x` → `/jmap/x` (the server may name itself differently from how we reach it). */
        fun pathOf(url: String): String = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+").replace(url, "")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        // ---- JSON helpers ----

        fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
        fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
        fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
        fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
        fun JsonObject.strings(k: String): List<String> = arr(k)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

        fun addresses(e: JsonElement?): List<Address> = (e as? JsonArray)?.mapNotNull { a ->
            val o = a as? JsonObject ?: return@mapNotNull null
            o.str("email")?.let { Address(o.str("name"), it) }
        } ?: emptyList()

        fun instant(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

        fun mailbox(o: JsonObject) = Mailbox(
            id = o.str("id")!!, name = o.str("name") ?: "", role = o.str("role"), parentId = o.str("parentId"),
            sortOrder = o.int("sortOrder") ?: 0, total = o.int("totalEmails") ?: 0, unread = o.int("unreadEmails") ?: 0,
        )

        fun email(o: JsonObject) = Email(
            id = o.str("id")!!,
            blobId = o.str("blobId") ?: "",
            threadId = o.str("threadId") ?: "",
            mailboxIds = o.obj("mailboxIds")?.filter { (_, v) -> (v as? JsonPrimitive)?.booleanOrNull == true }?.keys ?: emptySet(),
            keywords = o.obj("keywords")?.filter { (_, v) -> (v as? JsonPrimitive)?.booleanOrNull == true }?.keys ?: emptySet(),
            hasAttachment = o.bool("hasAttachment") ?: false,
            from = addresses(o["from"]),
            to = addresses(o["to"]),
            subject = (o.str("subject") ?: "").replace(Regex("[\\r\\n\\t]+"), " ").trim(),
            receivedAt = instant(o.str("receivedAt")) ?: 0,
            sentAt = instant(o.str("sentAt")),
            size = o.long("size") ?: 0,
            preview = o.str("preview") ?: "",
            kind = o.obj("bodyStructure")?.str("type")?.lowercase() ?: "",
            deliveredTo = addresses(o["header:Delivered-To:asAddresses"]).firstOrNull()?.email?.lowercase(),
        )

        fun part(o: JsonObject): BodyPart = BodyPart(
            partId = o.str("partId"), blobId = o.str("blobId"), size = o.long("size") ?: 0, name = o.str("name"),
            type = (o.str("type") ?: "application/octet-stream").lowercase(), charset = o.str("charset"),
            disposition = o.str("disposition"), cid = o.str("cid"),
            subParts = o.arr("subParts")?.mapNotNull { (it as? JsonObject)?.let { p -> part(p) } } ?: emptyList(),
        )

        fun full(o: JsonObject) = EmailFull(
            header = email(o),
            cc = addresses(o["cc"]), replyTo = addresses(o["replyTo"]),
            messageId = o.strings("messageId"), inReplyTo = o.strings("inReplyTo"), references = o.strings("references"),
            body = o.obj("bodyStructure")?.let { part(it) } ?: BodyPart(null, null, 0, null, "text/plain", null, null, null, emptyList()),
            textBody = o.arr("textBody")?.mapNotNull { (it as? JsonObject)?.let { p -> part(p) } } ?: emptyList(),
            htmlBody = o.arr("htmlBody")?.mapNotNull { (it as? JsonObject)?.let { p -> part(p) } } ?: emptyList(),
            attachments = o.arr("attachments")?.mapNotNull { (it as? JsonObject)?.let { p -> part(p) } } ?: emptyList(),
            bodyValues = o.obj("bodyValues")?.mapValues { (_, v) -> (v as? JsonObject)?.str("value") ?: "" } ?: emptyMap(),
        )
    }

    val base: HttpUrl = baseOf(server)
    private val auth = Credentials.basic(user, pass, Charsets.UTF_8)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    @Volatile
    var session: Session? = null

    private fun url(path: String): String = base.toString().trimEnd('/') + (if (path.startsWith("/")) path else "/$path")

    private fun call(req: Request.Builder): Response {
        var r = http.newCall(req.header("Authorization", auth).header("User-Agent", UA).build()).execute()
        var hops = 0
        while (r.code in setOf(301, 302, 307, 308) && hops++ < 5) {
            val loc = r.header("Location") ?: break
            r.close()
            val next = base.resolve(loc) ?: throw IOException("bad redirect $loc")
            r = http.newCall(req.url(next).build()).execute()
        }
        return r
    }

    private fun error(r: Response): JmapError {
        val detail = runCatching { r.body?.string() }.getOrNull()?.let { Regex("\"detail\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
        return JmapError(
            r.code,
            when (r.code) {
                401 -> "wrong user name or password"
                403 -> "not allowed" + (detail?.let { ": $it" } ?: "")
                404 -> "no JMAP at this address"
                else -> "server said ${r.code}" + (detail?.let { ": $it" } ?: "")
            },
        )
    }

    // ---- session ----

    /** GET /.well-known/jmap (following the redirect to /jmap/session); the mail account is the primary one. */
    fun connect(): Session {
        call(Request.Builder().url(url("/.well-known/jmap")).get()).use { r ->
            if (r.code != 200) throw error(r)
            val o = json.parseToJsonElement(r.body!!.string()).jsonObject
            val caps = o.obj("capabilities")?.keys ?: emptySet()
            val accountId = o.obj("primaryAccounts")?.str(MAIL) ?: throw IOException("this account has no mail")
            val name = o.obj("accounts")?.obj(accountId)?.str("name") ?: ""
            val s = Session(
                accountId = accountId, name = name,
                api = pathOf(o.str("apiUrl") ?: throw IOException("no apiUrl")),
                download = pathOf(o.str("downloadUrl") ?: throw IOException("no downloadUrl")),
                upload = pathOf(o.str("uploadUrl") ?: throw IOException("no uploadUrl")),
                eventSource = o.str("eventSourceUrl")?.let { pathOf(it) },
                capabilities = caps, state = o.str("state"),
            )
            session = s
            return s
        }
    }

    private fun s(): Session = session ?: connect()

    // ---- method calls ----

    class Call(val method: String, val args: JsonObject, val id: String)

    /** Runs the calls in one request; returns the response args by call id. A method error throws. */
    fun invoke(vararg calls: Call): Map<String, JsonObject> {
        val sess = s()
        val body = buildJsonObject {
            put("using", buildJsonArray { add(JsonPrimitive(CORE)); add(JsonPrimitive(MAIL)); add(JsonPrimitive(SUBMISSION)); if (sess.capabilities.contains(SIEVE)) add(JsonPrimitive(SIEVE)) })
            put("methodCalls", buildJsonArray {
                for (c in calls) add(buildJsonArray {
                    add(JsonPrimitive(c.method))
                    add(buildJsonObject { put("accountId", sess.accountId); for ((k, v) in c.args) put(k, v) })
                    add(JsonPrimitive(c.id))
                })
            })
        }
        call(Request.Builder().url(url(sess.api)).post(body.toString().toRequestBody("application/json".toMediaType()))).use { r ->
            if (r.code != 200) throw error(r)
            val resp = json.parseToJsonElement(r.body!!.string()).jsonObject
            val out = LinkedHashMap<String, JsonObject>()
            for (m in resp.arr("methodResponses") ?: JsonArray(emptyList())) {
                val a = m.jsonArray
                val name = a[0].jsonPrimitive.contentOrNull
                val args = a[1].jsonObject
                val id = a[2].jsonPrimitive.contentOrNull ?: ""
                if (name == "error") throw MethodError(args.str("type") ?: "error", args.str("description"))
                out[id] = args
            }
            return out
        }
    }

    private fun ids(list: List<String>) = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }
    private fun strs(list: List<String>) = ids(list)

    fun mailboxes(): List<Mailbox> {
        val r = invoke(Call("Mailbox/get", buildJsonObject { put("ids", JsonNull); put("properties", strs(listOf("id", "name", "role", "parentId", "sortOrder", "totalEmails", "unreadEmails"))) }, "m"))
        return r["m"]!!.arr("list")!!.map { mailbox(it.jsonObject) }
    }

    /** Newest first in a mailbox, optionally only those matching [text] (subject, addresses, body: the server decides). */
    fun query(mailboxId: String?, text: String?, position: Int, limit: Int): QueryResult {
        val r = invoke(
            Call("Email/query", buildJsonObject {
                putJsonObject("filter") {
                    if (mailboxId != null) put("inMailbox", mailboxId)
                    if (!text.isNullOrBlank()) put("text", text.trim())
                }
                put("sort", buildJsonArray { add(buildJsonObject { put("property", "receivedAt"); put("isAscending", false) }) })
                put("position", position)
                put("limit", limit)
                put("calculateTotal", true)
            }, "q"),
        )
        val q = r["q"]!!
        return QueryResult(q.strings("ids"), q.int("total"), q.str("queryState") ?: "")
    }

    /** The list rows for [idList] (at most a few hundred at a time). */
    fun emails(idList: List<String>): List<Email> {
        if (idList.isEmpty()) return emptyList()
        val out = ArrayList<Email>()
        for (batch in idList.chunked(200)) {
            val r = invoke(Call("Email/get", buildJsonObject { put("ids", ids(batch)); put("properties", strs(LIST_PROPERTIES)); put("bodyProperties", strs(listOf("type"))) }, "g"))
            r["g"]!!.arr("list")!!.forEach { out += email(it.jsonObject) }
        }
        return out
    }

    /** The current Email state, for the first Email/changes. */
    fun emailState(): String {
        val r = invoke(Call("Email/get", buildJsonObject { put("ids", ids(emptyList())) }, "g"))
        return r["g"]!!.str("state") ?: ""
    }

    fun changes(since: String): Changes {
        val r = invoke(Call("Email/changes", buildJsonObject { put("sinceState", since); put("maxChanges", 500) }, "c"))
        val c = r["c"]!!
        return Changes(c.strings("created"), c.strings("updated"), c.strings("destroyed"), c.str("newState") ?: since, c.bool("hasMoreChanges") ?: false)
    }

    fun email(id: String): EmailFull? {
        val r = invoke(
            Call("Email/get", buildJsonObject {
                put("ids", ids(listOf(id)))
                put("properties", strs(FULL_PROPERTIES))
                put("bodyProperties", strs(BODY_PROPERTIES))
                put("fetchTextBodyValues", true)
                put("fetchHTMLBodyValues", true)
                put("maxBodyValueBytes", 2_000_000)
            }, "g"),
        )
        return r["g"]!!.arr("list")!!.firstOrNull()?.let { full(it.jsonObject) }
    }

    /** Every header field of the message, in order. */
    fun headers(id: String): List<Pair<String, String>> {
        val r = invoke(Call("Email/get", buildJsonObject { put("ids", ids(listOf(id))); put("properties", strs(listOf("headers"))) }, "g"))
        val o = r["g"]!!.arr("list")!!.firstOrNull()?.jsonObject ?: return emptyList()
        return o.arr("headers")?.mapNotNull { h -> (h as? JsonObject)?.let { Pair(it.str("name") ?: return@mapNotNull null, it.str("value")?.trim() ?: "") } } ?: emptyList()
    }

    /** Email/set: [updates] are id → patch objects (`keywords/$seen`: true, `mailboxIds`: {...}); [destroy] deletes for good. */
    fun set(updates: Map<String, JsonObject> = emptyMap(), destroy: List<String> = emptyList()) {
        if (updates.isEmpty() && destroy.isEmpty()) return
        val r = invoke(
            Call("Email/set", buildJsonObject {
                if (updates.isNotEmpty()) put("update", JsonObject(updates))
                if (destroy.isNotEmpty()) put("destroy", ids(destroy))
            }, "s"),
        )
        val s = r["s"]!!
        val notUpdated = s.obj("notUpdated")
        val notDestroyed = s.obj("notDestroyed")
        val problem = notUpdated?.values?.firstOrNull() ?: notDestroyed?.values?.firstOrNull()
        if (problem != null) throw MethodError((problem as? JsonObject)?.str("type") ?: "setError", (problem as? JsonObject)?.str("description"))
    }

    fun download(blobId: String, name: String = "blob", type: String = "application/octet-stream"): ByteArray {
        val sess = s()
        val path = sess.download.replace("{accountId}", sess.accountId).replace("{blobId}", blobId)
            .replace("{name}", java.net.URLEncoder.encode(name.ifBlank { "blob" }, "UTF-8").replace("+", "%20"))
            .replace("{type}", java.net.URLEncoder.encode(type, "UTF-8"))
        call(Request.Builder().url(url(path)).get()).use { r ->
            if (r.code != 200) throw error(r)
            return r.body!!.bytes()
        }
    }

    /** Returns the blob id. */
    fun upload(bytes: ByteArray, type: String): String {
        val sess = s()
        val path = sess.upload.replace("{accountId}", sess.accountId)
        call(Request.Builder().url(url(path)).post(bytes.toRequestBody(type.toMediaType()))).use { r ->
            if (r.code !in 200..299) throw error(r)
            return json.parseToJsonElement(r.body!!.string()).jsonObject.str("blobId") ?: throw IOException("upload gave no blobId")
        }
    }

    /** A complete RFC 5322 message (already uploaded) into a mailbox; returns the new email id. */
    fun import(blobId: String, mailboxId: String, keywords: List<String>): String {
        val r = invoke(
            Call("Email/import", buildJsonObject {
                putJsonObject("emails") {
                    putJsonObject("m") {
                        put("blobId", blobId)
                        putJsonObject("mailboxIds") { put(mailboxId, true) }
                        putJsonObject("keywords") { keywords.forEach { put(it, true) } }
                    }
                }
            }, "i"),
        )
        val i = r["i"]!!
        i.obj("notCreated")?.obj("m")?.let { throw MethodError(it.str("type") ?: "importError", it.str("description")) }
        return i.obj("created")?.obj("m")?.str("id") ?: throw IOException("import gave no id")
    }

    /** Tries to add a sending identity for [email]; null when the server won't have it (not an address of the account). */
    fun createIdentity(name: String?, email: String): Identity? {
        val r = invoke(Call("Identity/set", buildJsonObject { putJsonObject("create") { putJsonObject("n") { put("name", name ?: ""); put("email", email) } } }, "i"))
        val created = r["i"]!!.obj("created")?.obj("n") ?: return null
        return Identity(created.str("id") ?: return null, name, email)
    }

    // ---- sieve (RFC 9661) ----

    val hasSieve: Boolean get() = s().capabilities.contains(SIEVE)

    fun sieveScripts(): List<SieveScript> {
        val r = invoke(Call("SieveScript/get", buildJsonObject { put("ids", JsonNull) }, "s"))
        return r["s"]!!.arr("list")!!.mapNotNull { e ->
            val o = e.jsonObject
            SieveScript(o.str("id") ?: return@mapNotNull null, o.str("name") ?: "", o.str("blobId") ?: "", o.bool("isActive") ?: false)
        }
    }

    fun sieveScript(blobId: String): String = String(download(blobId, "script.sieve", "application/sieve"), Charsets.UTF_8)

    /** Null when the script is fine, else the server's complaint. */
    fun sieveValidate(content: String): String? {
        val blob = upload(content.toByteArray(), "application/sieve")
        val r = invoke(Call("SieveScript/validate", buildJsonObject { put("blobId", blob) }, "v"))
        return r["v"]!!.obj("error")?.let { it.str("description") ?: it.str("type") }
    }

    /** Creates ([id] null) or replaces a script; [activate] makes it the one that runs. Returns the id. */
    fun sieveSave(id: String?, name: String, content: String, activate: Boolean): String {
        val blob = upload(content.toByteArray(), "application/sieve")
        val r = invoke(
            Call("SieveScript/set", buildJsonObject {
                if (id == null) putJsonObject("create") { putJsonObject("n") { put("name", name); put("blobId", blob) } }
                else putJsonObject("update") { putJsonObject(id) { put("name", name); put("blobId", blob) } }
                if (activate) put("onSuccessActivateScript", if (id == null) "#n" else id)
            }, "s"),
        )
        val s = r["s"]!!
        (s.obj("notCreated")?.obj("n") ?: s.obj("notUpdated")?.let { nu -> id?.let { nu.obj(it) } })?.let { throw MethodError(it.str("type") ?: "setError", it.str("description")) }
        return id ?: s.obj("created")?.obj("n")?.str("id") ?: throw IOException("the server gave the script no id")
    }

    /** Makes [id] the active script, or none when null. */
    fun sieveActivate(id: String?) {
        invoke(Call("SieveScript/set", buildJsonObject { put("onSuccessActivateScript", id?.let { JsonPrimitive(it) } ?: JsonNull) }, "s"))
    }

    fun sieveDelete(id: String) {
        val r = invoke(Call("SieveScript/set", buildJsonObject { put("destroy", ids(listOf(id))) }, "s"))
        r["s"]!!.obj("notDestroyed")?.obj(id)?.let { throw MethodError(it.str("type") ?: "setError", it.str("description")) }
    }

    fun identities(): List<Identity> {
        val r = invoke(Call("Identity/get", buildJsonObject { put("ids", JsonNull) }, "i"))
        return r["i"]!!.arr("list")!!.mapNotNull { e -> val o = e.jsonObject; o.str("email")?.let { Identity(o.str("id")!!, o.str("name"), it) } }
    }

    /**
     * Sends an email that is already stored (a draft); on success the server moves it from
     * [draftsId] to [sentId] and drops the draft keyword.
     */
    fun submit(emailId: String, identityId: String, from: String, rcpts: List<String>, sentId: String?, draftsId: String?) {
        val r = invoke(
            Call("EmailSubmission/set", buildJsonObject {
                putJsonObject("create") {
                    putJsonObject("s") {
                        put("emailId", emailId)
                        put("identityId", identityId)
                        putJsonObject("envelope") {
                            putJsonObject("mailFrom") { put("email", from) }
                            put("rcptTo", buildJsonArray { rcpts.forEach { add(buildJsonObject { put("email", it) }) } })
                        }
                    }
                }
                putJsonObject("onSuccessUpdateEmail") {
                    putJsonObject("#s") {
                        put("keywords/\$draft", JsonNull)
                        put("keywords/\$seen", true)
                        if (draftsId != null) put("mailboxIds/$draftsId", JsonNull)
                        if (sentId != null) put("mailboxIds/$sentId", true)
                    }
                }
            }, "sub"),
        )
        val s = r["sub"]!!
        s.obj("notCreated")?.obj("s")?.let { throw MethodError(it.str("type") ?: "submissionError", it.str("description")) }
    }
}
