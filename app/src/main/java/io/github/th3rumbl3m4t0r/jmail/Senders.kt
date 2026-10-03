package io.github.th3rumbl3m4t0r.jmail

/**
 * Which addresses are ours to write from. The server only tells us its identities; the
 * catch-all aliases have to be inferred: an address that received mail was sent to (as the
 * only recipient) is one of ours, and so is its domain. Anything else the user types is
 * allowed, with a warning.
 */
object Senders {
    private fun identityDomains(): Set<String> = Store.state.value.identityList().map { it.email.substringAfter('@').lowercase() }.toSet()

    /** Identity domains, the settings list, and every domain that received mail was addressed to. */
    fun domains(): Set<String> {
        val p = Store.state.value
        val seen = Db.seenAddresses().map { it.first.substringAfter('@', "") }.filter { it.isNotEmpty() }.toSet()
        return identityDomains() + p.senderDomainList + seen
    }

    /** Identity addresses, then the received-to addresses whose domain is ours. */
    fun addresses(): List<String> {
        val p = Store.state.value
        val ds = domains()
        val out = LinkedHashSet<String>()
        p.identityList().forEach { out += it.email.lowercase() }
        Db.seenAddresses().forEach { (a, _) -> if (a.substringAfter('@', "") in ds) out += a }
        return out.toList()
    }

    fun known(email: String): Boolean = email.substringAfter('@', "").lowercase() in domains()

    /** `Name <addr>` for the account's main identity. */
    fun default(): String {
        val p = Store.state.value
        return Address(p.fromName, p.fromEmail ?: "").full
    }

    /**
     * The display name to use on [alias]: the identity's name when it is a real name; when the
     * identity's "name" is just an address (Stalwart's default), the alias itself, so an alias
     * never carries the primary address in front of it.
     */
    fun displayName(identityName: String?, primary: String?, alias: String): String {
        val n = identityName?.trim().orEmpty()
        return if (n.isEmpty() || n.contains('@') || n.equals(primary, true) || alias.equals(primary, true) && n.isEmpty()) alias else n
    }

    /** `Name <alias>` (or `alias <alias>`) for another of our addresses. */
    fun withName(email: String): String {
        val p = Store.state.value
        return Address(displayName(p.fromName, p.fromEmail, email), email).full
    }

    /** The address to answer from: the first To / Cc address that is ours (by address, then by domain), else the default. */
    fun replyFrom(h: Email, cc: List<Address>): String {
        val rcpts = h.to + cc
        val mine = addresses().toSet()
        rcpts.firstOrNull { it.email.lowercase() in mine }?.let { return withName(it.email) }
        rcpts.firstOrNull { known(it.email) }?.let { return withName(it.email) }
        return default()
    }
}
