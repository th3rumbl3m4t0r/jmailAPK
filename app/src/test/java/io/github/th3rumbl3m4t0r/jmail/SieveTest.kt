package io.github.th3rumbl3m4t0r.jmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SieveTest {
    @Test
    fun firstRuleIntoAnEmptyScript() {
        val r = Sieve.Rule(Sieve.Field.FROM, match = Sieve.Match.CONTAINS, value = "shop.example", action = Sieve.Action.MOVE, target = "Junk Mail")
        assertEquals(
            "require [\"fileinto\"];\n\n# from contains \"shop.example\" -> move to folder Junk Mail\nif address :contains \"from\" \"shop.example\" {\n    fileinto \"Junk Mail\";\n    stop;\n}\n",
            Sieve.addRule("", r),
        )
    }

    @Test
    fun requiresAreMergedAndKeptOnTop() {
        val one = Sieve.addRule("", Sieve.Rule(Sieve.Field.SUBJECT, match = Sieve.Match.IS, value = "hi", action = Sieve.Action.MOVE, target = "A"))
        val two = Sieve.addRule(one, Sieve.Rule(Sieve.Field.TO, match = Sieve.Match.CONTAINS, value = "list@x", action = Sieve.Action.READ))
        assertTrue(two, two.startsWith("require [\"fileinto\", \"imap4flags\"];\n\n# subject is \"hi\""))
        assertEquals(1, Regex("(?m)^require").findAll(two).count())
        assertTrue(two, two.contains("if address :contains [\"to\", \"cc\"] \"list@x\" {\n    addflag \"\\\\Seen\";\n    stop;\n}\n"))
        assertTrue(two.endsWith("}\n"))
        // the old string form of require is understood too; existing text is kept
        val legacy = "require \"fileinto\";\r\nif true { fileinto \"X\"; }\r\n"
        val three = Sieve.addRule(legacy, Sieve.Rule(Sieve.Field.FROM, match = Sieve.Match.CONTAINS, value = "a", action = Sieve.Action.FLAG, alsoRead = true))
        assertTrue(three, three.startsWith("require [\"fileinto\", \"imap4flags\"];\n\nif true { fileinto \"X\"; }\n\n# from contains"))
        assertTrue(three, three.contains("    addflag \"\\\\Seen\";\n    addflag \"\\\\Flagged\";\n    stop;"))
    }

    @Test
    fun quotingAndHeaderRules() {
        assertEquals("\"a \\\"b\\\" \\\\ c\"", Sieve.quote("a \"b\" \\ c"))
        val r = Sieve.Rule(Sieve.Field.HEADER, header = "List-Id", match = Sieve.Match.MATCHES, value = "*lists.example*", action = Sieve.Action.REDIRECT, target = "me@other.test")
        assertEquals("header :matches \"List-Id\" \"*lists.example*\"", Sieve.test(r))
        assertEquals(listOf("redirect \"me@other.test\";", "stop;"), Sieve.actions(r))
        assertTrue(Sieve.requires(r).isEmpty())
        val d = Sieve.Rule(Sieve.Field.FROM, match = Sieve.Match.IS, value = "x@y", action = Sieve.Action.DISCARD)
        assertEquals(listOf("discard;", "stop;"), Sieve.actions(d))
        assertEquals(setOf("fileinto", "imap4flags"), Sieve.required("require [\"fileinto\",\n \"imap4flags\"];\nkeep;"))
    }
}
