/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expansion cases are taken from the RFC 6570 specification's own worked examples (§1.2, §3.2), so a
 * pass here is conformance against the standard's text. Wire the community `uritemplate-test` corpus as
 * a ratcheting suite (like the IDNA/NFC fixtures) before release; see the module notes.
 */
class UriTemplateTest {
    private val vars: Map<String, Any?> =
        mapOf(
            "var" to "value",
            "hello" to "Hello World!",
            "path" to "/foo/bar",
            "list" to listOf("red", "green", "blue"),
            "keys" to mapOf("semi" to ";", "dot" to ".", "comma" to ","),
            "x" to "1024",
            "y" to "768",
            "empty" to "",
            "who" to "fred",
        )

    private fun expand(template: String): String = UriTemplate.parse(template).expand(vars)

    @Test
    fun `simple and reserved string expansion`() {
        assertEquals("value", expand("{var}"))
        assertEquals("Hello%20World%21", expand("{hello}"))
        assertEquals("value", expand("{+var}"))
        assertEquals("Hello%20World!", expand("{+hello}"))
        assertEquals("/foo/bar/here", expand("{+path}/here"))
    }

    @Test
    fun `fragment expansion`() {
        assertEquals("#value", expand("{#var}"))
        assertEquals("#Hello%20World!", expand("{#hello}"))
        assertEquals("X#value", expand("X{#var}"))
    }

    @Test
    fun `multiple variables and label expansion`() {
        assertEquals("1024,768", expand("{x,y}"))
        assertEquals("1024,Hello%20World%21,768", expand("{x,hello,y}"))
        assertEquals("?1024,", expand("?{x,empty}"))
        assertEquals("1024,Hello%20World!,768", expand("{+x,hello,y}"))
        assertEquals(".fred", expand("{.who}"))
    }

    @Test
    fun `path segment expansion`() {
        assertEquals("/value", expand("{/var}"))
        assertEquals("/value/1024/here", expand("{/var,x}/here"))
    }

    @Test
    fun `path-style parameter expansion`() {
        assertEquals(";x=1024;y=768", expand("{;x,y}"))
        assertEquals(";x=1024;y=768;empty", expand("{;x,y,empty}"))
    }

    @Test
    fun `form query expansion`() {
        assertEquals("?x=1024&y=768", expand("{?x,y}"))
        assertEquals("?x=1024&y=768&empty=", expand("{?x,y,empty}"))
        assertEquals("&x=1024", expand("{&x}"))
    }

    @Test
    fun `list expansion with and without explode`() {
        assertEquals("/red/green/blue", expand("{/list*}"))
        assertEquals("?list=red,green,blue", expand("{?list}"))
        assertEquals("?list=red&list=green&list=blue", expand("{?list*}"))
    }

    @Test
    fun `associative array expansion with and without explode`() {
        assertEquals("semi,%3B,dot,.,comma,%2C", expand("{keys}"))
        assertEquals("semi=%3B,dot=.,comma=%2C", expand("{keys*}"))
        assertEquals("?keys=semi,%3B,dot,.,comma,%2C", expand("{?keys}"))
        assertEquals("?semi=%3B&dot=.&comma=%2C", expand("{?keys*}"))
    }

    @Test
    fun `prefix modifier truncates before encoding`() {
        assertEquals("val", expand("{var:3}"))
        assertEquals("/foo/b/here", expand("{+path:6}/here"))
    }

    @Test
    fun `undefined variables contribute nothing`() {
        assertEquals("", expand("{undef}"))
        assertEquals("OX", expand("O{undef}X"))
        assertEquals("", expand("{?undef}"))
        assertEquals("", UriTemplate.parse("{?empty_list}").expand(mapOf("empty_list" to emptyList<String>())))
    }

    @Test
    fun `expand to url parses the expansion`() {
        val template = UriTemplate.parse("https://api.example.com/users/{id}{?page}")
        val url = template.expandToUrl(mapOf("id" to 42, "page" to 2)).getOrNull()
        assertEquals("https://api.example.com/users/42?page=2", url?.toString())
    }

    @Test
    fun `vararg expand and variable names`() {
        val template = UriTemplate.parse("/users/{id}{?tab}")
        assertEquals("/users/42?tab=repos", template.expand("id" to 42, "tab" to "repos"))
        assertEquals(setOf("id", "tab"), template.variableNames)
    }

    @Test
    fun `match inverts the common path plus query shape`() {
        val template = UriTemplate.parse("/users/{id}{?tab}")
        assertEquals(mapOf("id" to "42", "tab" to "repos"), template.match("/users/42?tab=repos"))
        assertEquals(mapOf("id" to "42"), template.match("/users/42"))
        assertNull(template.match("/posts/42"))
    }

    @Test
    fun `match returns null for uninvertible templates`() {
        assertNull(UriTemplate.parse("{/list*}").match("/a/b/c"))
    }

    @Test
    fun `malformed templates fail fast or parse to null`() {
        assertNull(UriTemplate.parseOrNull("{"))
        assertNull(UriTemplate.parseOrNull("{}"))
        assertNull(UriTemplate.parseOrNull("{=bad}"))
        assertNull(UriTemplate.parseOrNull("a}b"))
        assertNull(UriTemplate.parseOrNull("{+}"))
        assertTrue(UriTemplate.parseOrNull("{var}") != null)
    }

    @Test
    fun `malformed prefix modifiers are rejected`() {
        assertNull(UriTemplate.parseOrNull("{var:0}"))
        assertNull(UriTemplate.parseOrNull("{var:10000}"))
        assertNull(UriTemplate.parseOrNull("{var:abc}"))
    }

    @Test
    fun `malformed variable names are rejected`() {
        assertNull(UriTemplate.parseOrNull("{a,,b}"))
        assertNull(UriTemplate.parseOrNull("{v@r}"))
        assertNull(UriTemplate.parseOrNull("{ var}"))
    }

    @Test
    fun `percent-encoded variable names round-trip or reject malformed triplets`() {
        assertTrue(UriTemplate.parseOrNull("{va%7A}") != null)
        assertNull(UriTemplate.parseOrNull("{va%7}"))
        assertNull(UriTemplate.parseOrNull("{va%7g}"))
        assertNull(UriTemplate.parseOrNull("{va%g7}"))
    }

    @Test
    fun `variable names accept digits and underscores and dots and uppercase letters`() {
        assertEquals("value", UriTemplate.parse("{a1_b.c2}").expand(mapOf("a1_b.c2" to "value")))
        assertEquals("value", UriTemplate.parse("{ID}").expand(mapOf("ID" to "value")))
    }

    @Test
    fun `primitive and object arrays expand like a list`() {
        assertEquals("a,b", UriTemplate.parse("{v}").expand(mapOf("v" to arrayOf("a", "b"))))
        assertEquals("1,2", UriTemplate.parse("{v}").expand(mapOf("v" to intArrayOf(1, 2))))
        assertEquals("1,2", UriTemplate.parse("{v}").expand(mapOf("v" to longArrayOf(1L, 2L))))
        assertEquals("1,2", UriTemplate.parse("{v}").expand(mapOf("v" to shortArrayOf(1, 2))))
        assertEquals("1,2", UriTemplate.parse("{v}").expand(mapOf("v" to byteArrayOf(1, 2))))
        // Whole numbers deliberately avoided: Kotlin/JS's Double/Float.toString() drops a whole
        // number's trailing ".0" (JS numbers have no int/float distinction) while the JVM keeps it, so
        // a fractional value is used to keep the expectation identical on every target.
        assertEquals("1.5,2.5", UriTemplate.parse("{v}").expand(mapOf("v" to doubleArrayOf(1.5, 2.5))))
        assertEquals("1.5,2.5", UriTemplate.parse("{v}").expand(mapOf("v" to floatArrayOf(1.5f, 2.5f))))
        assertEquals("true,false", UriTemplate.parse("{v}").expand(mapOf("v" to booleanArrayOf(true, false))))
        assertEquals("a,b", UriTemplate.parse("{v}").expand(mapOf("v" to charArrayOf('a', 'b'))))
    }

    @Test
    fun `plain non-exploded list omits null members`() {
        val vars = mapOf("plain" to listOf("a", null, "b"))
        assertEquals("a,b", UriTemplate.parse("{plain}").expand(vars))
    }

    @Test
    fun `associative array with a null value and an empty map`() {
        assertEquals("a,", UriTemplate.parse("{m}").expand(mapOf("m" to mapOf("a" to null))))
        assertEquals("", UriTemplate.parse("{m}").expand(mapOf("m" to emptyMap<String, String>())))
    }

    @Test
    fun `exploded associative array with an empty value under named and unnamed operators`() {
        // Unnamed (SIMPLE): an empty-value pair always keeps its trailing "=". Named with an empty
        // `ifEmpty` (PARAMETER, `;`): an empty-value pair collapses to the bare key.
        val keys = mapOf("keys2" to mapOf("a" to "", "b" to "x"))
        assertEquals("a=,b=x", UriTemplate.parse("{keys2*}").expand(keys))
        assertEquals(";a;b=x", UriTemplate.parse("{;keys2*}").expand(keys))
    }

    @Test
    fun `equals hashCode and toString are based on the source template`() {
        val a = UriTemplate.parse("/users/{id}")
        val b = UriTemplate.parse("/users/{id}")
        val c = UriTemplate.parse("/users/{name}")
        // Malformed, so genuinely `null` at runtime — a real nullable value rather than a literal
        // `null`, so this exercises the `other is UriTemplate` check without a "always false"
        // compile-time condition or an `EqualsNullCall` lint violation.
        val notATemplate: UriTemplate? = UriTemplate.parseOrNull("{")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
        assertFalse(a.equals(notATemplate))
        assertFalse(a.equals("/users/{id}"))
        assertEquals("/users/{id}", a.toString())
    }

    @Test
    fun `match handles templates without a trailing named expression`() {
        assertEquals(emptyMap<String, String>(), UriTemplate.parse("").match(""))
        assertEquals(mapOf("id" to "42"), UriTemplate.parse("/simple/{id}").match("/simple/42"))
        assertNull(UriTemplate.parse("/simple/{id}extra").match("/other"))
    }

    @Test
    fun `match returns null when the trailing query uses explode or prefix`() {
        assertNull(UriTemplate.parse("/x{?tab*}").match("/x?tab=a,b"))
        assertNull(UriTemplate.parse("/x{?tab:3}").match("/x?tab=abc"))
    }

    @Test
    fun `match returns null for a multi-variable non-trailing expression`() {
        assertNull(UriTemplate.parse("/{a,b}/thing").match("/x,y/thing"))
    }

    @Test
    fun `match returns null when a non-trailing expression uses an unsupported operator`() {
        assertNull(UriTemplate.parse("{?a}/rest").match("?a=1/rest"))
    }

    @Test
    fun `match supports the path and label and reserved and fragment operators`() {
        assertEquals(mapOf("id" to "42"), UriTemplate.parse("{/id}").match("/42"))
        assertEquals(mapOf("id" to "42"), UriTemplate.parse("{.id}").match(".42"))
        assertEquals(mapOf("id" to "a/b"), UriTemplate.parse("{+id}").match("a/b"))
        assertEquals(mapOf("id" to "a/b"), UriTemplate.parse("{#id}").match("#a/b"))
    }

    @Test
    fun `match omits an absent trailing query variable`() {
        assertEquals(mapOf("tab" to "1"), UriTemplate.parse("/x{?tab,extra}").match("/x?tab=1"))
    }
}
