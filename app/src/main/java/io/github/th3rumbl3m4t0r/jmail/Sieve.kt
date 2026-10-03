package io.github.th3rumbl3m4t0r.jmail

/**
 * The little Sieve we write: one `if` block per rule, appended to the script, with the
 * `require` line kept in step. The script itself stays plain text the user can edit.
 */
object Sieve {
    enum class Field(val label: String) { FROM("from"), TO("to"), SUBJECT("subject"), HEADER("header") }
    enum class Match(val label: String, val tag: String) { CONTAINS("contains", ":contains"), IS("is", ":is"), MATCHES("matches", ":matches") }
    enum class Action(val label: String) { MOVE("move to folder"), READ("mark read"), FLAG("flag"), DISCARD("discard"), REDIRECT("redirect to") }

    data class Rule(
        val field: Field,
        val header: String = "",
        val match: Match,
        val value: String,
        val action: Action,
        /** The folder for [Action.MOVE], the address for [Action.REDIRECT]. */
        val target: String = "",
        val alsoRead: Boolean = false,
    )

    fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun requires(r: Rule): Set<String> = buildSet {
        if (r.action == Action.MOVE) add("fileinto")
        if (r.action == Action.READ || r.action == Action.FLAG || r.alsoRead) add("imap4flags")
    }

    fun test(r: Rule): String = when (r.field) {
        Field.FROM -> "address ${r.match.tag} \"from\" ${quote(r.value)}"
        Field.TO -> "address ${r.match.tag} [\"to\", \"cc\"] ${quote(r.value)}"
        Field.SUBJECT -> "header ${r.match.tag} \"subject\" ${quote(r.value)}"
        Field.HEADER -> "header ${r.match.tag} ${quote(r.header.trim())} ${quote(r.value)}"
    }

    fun actions(r: Rule): List<String> = buildList {
        if (r.alsoRead && r.action != Action.READ) add("addflag \"\\\\Seen\";")
        when (r.action) {
            Action.MOVE -> add("fileinto ${quote(r.target)};")
            Action.READ -> add("addflag \"\\\\Seen\";")
            Action.FLAG -> add("addflag \"\\\\Flagged\";")
            Action.DISCARD -> add("discard;")
            Action.REDIRECT -> add("redirect ${quote(r.target)};")
        }
        add("stop;")
    }

    /** The rule as a commented `if` block. */
    fun block(r: Rule): String {
        val what = r.field.label + (if (r.field == Field.HEADER) " " + r.header.trim() else "")
        val does = r.action.label + (if (r.target.isNotEmpty()) " " + r.target else "")
        return "# $what ${r.match.label} ${quote(r.value)} -> $does\nif ${test(r)} {\n" + actions(r).joinToString("") { "    $it\n" } + "}\n"
    }

    private val REQUIRE = Regex("(?m)^[ \\t]*require[ \\t]+(\\[[^\\]]*\\]|\"[^\"]*\")[ \\t]*;[ \\t]*\\r?\\n?")

    /** The extensions a script's `require` lines ask for. */
    fun required(script: String): Set<String> =
        REQUIRE.findAll(script).flatMap { m -> Regex("\"([^\"]*)\"").findAll(m.groupValues[1]).map { it.groupValues[1] } }.toSet()

    /** [script] with the rule appended and one `require` line on top covering everything. */
    fun addRule(script: String, r: Rule): String {
        val have = (required(script) + requires(r)).toSortedSet()
        val body = REQUIRE.replace(script, "").trimStart('\r', '\n').trimEnd()
        val req = if (have.isEmpty()) "" else "require [" + have.joinToString(", ") { "\"$it\"" } + "];\n\n"
        return req + (if (body.isEmpty()) "" else body + "\n\n") + block(r)
    }

    /** A fresh script's text. */
    const val TEMPLATE = "# Sieve rules, run by the server when mail arrives.\n# Add rules with the button below or write them here.\n"
}
