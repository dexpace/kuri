/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri;

import org.dexpace.kuri.error.ParseResult;
import org.dexpace.kuri.error.UriParseError;
import org.dexpace.kuri.host.Host;
import org.dexpace.kuri.query.QueryParameters;
import org.junit.Assert;
import org.junit.Test;

/**
 * Compiles and runs the public API from a real Java call site, mirroring the README's "From Java"
 * examples. Its job is to fail the build if a documented Java call ever stops compiling or behaving,
 * so a non-compiling Java snippet can never ship. Everything here is invoked exactly as an external
 * Java consumer would (no Kotlin-only constructs, no {@code internal} access).
 */
public final class JavaInteropTest {
    @Test
    public void parseGetOrThrowReturnsTypedUrl() {
        Url url = Url.parse("https://example.com/a?b=1").getOrThrow();

        Assert.assertEquals("https", url.scheme());
        Assert.assertEquals("example.com", url.hostName());
    }

    @Test
    public void parseOrThrowFactoriesReturnValues() {
        Url a = Url.parseOrThrow("https://x/");
        Assert.assertEquals("https", a.scheme());

        Uri c = Uri.parseOrThrow("http://h/p");
        Assert.assertEquals("http", c.scheme());
    }

    @Test
    public void parseOrNullReturnsNullOnFailure() {
        Url ok = Url.parseOrNull("https://x/");
        Assert.assertNotNull(ok);

        Url bad = Url.parseOrNull("::bad");
        Assert.assertNull(bad);
    }

    @Test
    public void errorMessageIsReadableWithoutThrowing() {
        ParseResult<Url> result = Url.parse("::bad");

        Assert.assertFalse(result.isOk());
        Assert.assertNull(result.getOrNull());
        Assert.assertTrue(result instanceof ParseResult.Err);

        UriParseError error = ((ParseResult.Err) result).getError();
        String message = error.getMessage();
        Assert.assertNotNull(message);
        Assert.assertFalse(message.isEmpty());
    }

    @Test
    public void queryParametersReadAsAJavaConsumer() {
        QueryParameters q = QueryParameters.parse("a=1&b=2");

        Assert.assertEquals("2", q.get("b"));
        Assert.assertEquals(2, q.size());
        Assert.assertTrue(q.has("b"));

        java.util.Map<String, String> m = q.toMap();
        Assert.assertEquals("1", m.get("a"));
    }

    @Test
    public void hostAsTextRendersTheAuthority() {
        Url url = Url.parse("https://example.com/a?b=1").getOrThrow();

        Host host = url.host();
        Assert.assertNotNull(host);
        Assert.assertEquals("example.com", host.asText());
    }
}
