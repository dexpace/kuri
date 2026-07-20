/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri;

import java.util.Arrays;
import java.util.List;
import org.dexpace.kuri.error.ParseResult;
import org.dexpace.kuri.error.UriParseError;
import org.dexpace.kuri.error.ValidationError;
import org.dexpace.kuri.error.ValidationErrorKind;
import org.dexpace.kuri.host.Host;
import org.dexpace.kuri.idna.Idn;
import org.dexpace.kuri.percent.Percent;
import org.dexpace.kuri.query.QueryParameter;
import org.dexpace.kuri.query.QueryParameters;
import org.dexpace.kuri.scheme.Schemes;
import org.junit.Assert;
import org.junit.Test;

/**
 * Exercises the ergonomic public API from a real Java call site, mirroring the {@code UriDxTest} /
 * {@code UrlDxTest} Kotlin suites. Every declaration here is written exactly as an external Java
 * consumer would: {@code ()}-style accessors, the static facades, and the {@code Builder}s, with
 * no Kotlin-only constructs and no {@code internal} access, so the build fails the moment a Java
 * call site stops compiling or a documented behaviour drifts.
 *
 * <p>Note the deliberate typing trap this pins down: {@link Url#origin()} for an opaque origin
 * returns the four-character String {@code "null"} rather than a {@code null} reference. Both
 * {@link Uri#effectivePort()} and {@link Url#effectivePort()} return a boxed {@link Integer}
 * (nullable), reporting "no default port" as {@code null} rather than a sentinel.
 */
public final class PublicApiDxJavaTest {

    // --- Facades: Percent / Idn / Schemes (static) ---

    @Test
    public void percentEncodeAndDecodeFromJava() {
        // COMPONENT is encodeURIComponent-equivalent: it escapes the space and the '/' separator.
        Assert.assertEquals("a%20b%2Fc", Percent.encode("a b/c", Percent.Component.COMPONENT));
        Assert.assertEquals("a b", Percent.decode("a%20b"));
    }

    @Test
    public void idnToAsciiAndToUnicodeFromJava() {
        // Declare the generic type so getOrThrow() yields String, not Object. The precomposed
        // (NFC) 'u-umlaut' is written as an ASCII unicode escape so it is deterministic and
        // independent of the source file encoding: toUnicode returns the precomposed U+00FC code
        // point, so the expected literal must be exactly that code point to match.
        ParseResult<String> ascii = Idn.toAscii("b\u00fccher.example");
        Assert.assertEquals("xn--bcher-kva.example", ascii.getOrThrow());

        Assert.assertEquals("b\u00fccher.example", Idn.toUnicode("xn--bcher-kva.example"));
    }

    @Test
    public void schemesFactsFromJava() {
        // defaultPort returns a boxed Integer so it can report "no default" as null.
        Assert.assertEquals(Integer.valueOf(443), Schemes.defaultPort("https"));
        Assert.assertNull(Schemes.defaultPort("mailto"));

        Assert.assertTrue(Schemes.isSpecial("ws"));
        Assert.assertTrue(Schemes.isValid("http"));
    }

    // --- Uri: instance () accessors ---

    @Test
    public void uriPathIsDecodedWhileEncodedPathStaysVerbatim() {
        Uri uri = Uri.parseOrThrow("http://h/a%2Fb");

        Assert.assertEquals("/a/b", uri.path());
        Assert.assertEquals("/a%2Fb", uri.encodedPath());
    }

    @Test
    public void uriQueryParametersReadAndBuilderQueryEditing() {
        Uri built = new Uri.Builder().scheme("http").host("h").setQueryParameter("k", "v").build();
        Assert.assertEquals("v", built.queryParameters().get("k"));

        Uri added = built.newBuilder().addQueryParameter("k", "v2").build();
        Assert.assertEquals(Arrays.asList("v", "v2"), added.queryParameters().getAll("k"));

        Uri removed = added.newBuilder().removeAllQueryParameters("k").build();
        Assert.assertFalse(removed.queryParameters().has("k"));
    }

    @Test
    public void uriBuilderBuildOrNullReturnsNullForUserInfoWithoutHost() {
        Uri.Builder invalid = new Uri.Builder().userInfo("u").encodedPath("/p");

        // An unrepresentable assembly (userinfo with no host) is punned to null, never thrown.
        Assert.assertNull(invalid.buildOrNull());
    }

    @Test
    public void uriBuilderAcceptsAStructuredHost() {
        Host structuredHost = Uri.parseOrThrow("http://[0:0:0:0:0:0:0:1]/").host();
        Assert.assertNotNull(structuredHost);

        Uri rebuilt = new Uri.Builder().scheme("http").host(structuredHost).encodedPath("/").build();

        // The host is re-canonicalized to its RFC 5952 form on the way through the builder.
        Assert.assertEquals("[::1]", rebuilt.hostName());
    }

    @Test
    public void uriRelativizeEmitsARelativeReference() {
        Uri base = Uri.parseOrThrow("http://h/a/b/");
        Uri target = Uri.parseOrThrow("http://h/a/b/c/d");

        Uri relative = base.relativize(target);

        Assert.assertEquals("c/d", relative.uriString());
        Assert.assertEquals(target, base.resolveOrThrow(relative.uriString()));
    }

    @Test
    public void uriFileNameAndFileExtension() {
        Assert.assertEquals("c d.txt", Uri.parseOrThrow("http://h/a/b/c%20d.txt").fileName());
        Assert.assertEquals("gz", Uri.parseOrThrow("http://h/a/archive.tar.gz").fileExtension());
    }

    @Test
    public void uriIsAbsoluteReflectsScheme() {
        Assert.assertTrue(Uri.parseOrThrow("http://h/p").isAbsolute());
        Assert.assertFalse(Uri.parseOrThrow("/rel").isAbsolute());
    }

    @Test
    public void uriEffectivePortIsABoxedInteger() {
        // Assigning to Integer proves the boxed, nullable return.
        Integer httpPort = Uri.parseOrThrow("http://h/").effectivePort();
        Assert.assertEquals(Integer.valueOf(80), httpPort);

        Integer mailtoPort = Uri.parseOrThrow("mailto:a@b.example").effectivePort();
        Assert.assertNull(mailtoPort);
    }

    @Test
    public void uriWithPortAndWithoutFragment() {
        // withPort takes a boxed Integer (null elides the port).
        Assert.assertEquals(Integer.valueOf(2), Uri.parseOrThrow("http://h:1/").withPort(2).port());
        Assert.assertNull(Uri.parseOrThrow("http://h:1/").withPort(null).port());

        Assert.assertNull(Uri.parseOrThrow("http://h/p#x").withoutFragment().fragment());
    }

    @Test
    public void uriRedactStripsUserinfoQueryAndFragment() {
        Uri redacted = Uri.parseOrThrow("http://user:pass@h:8080/p?q=1#frag").redact();

        Assert.assertEquals("http://h:8080/p", redacted.uriString());
        Assert.assertNull(redacted.userInfo());
        Assert.assertNull(redacted.query());
        Assert.assertNull(redacted.fragment());
    }

    @Test
    public void uriIsDirectoryAndHasTrailingSlashAgree() {
        Uri directory = Uri.parseOrThrow("http://h/a/");
        Assert.assertTrue(directory.isDirectory());
        Assert.assertTrue(directory.hasTrailingSlash());

        Uri file = Uri.parseOrThrow("http://h/a");
        Assert.assertFalse(file.isDirectory());
        Assert.assertFalse(file.hasTrailingSlash());
    }

    @Test
    public void uriResolveAndConvertToUrl() {
        Assert.assertEquals("http://h/x", Uri.parseOrThrow("http://h/a/b").resolveOrThrow("../x").uriString());

        // Resolution needs an absolute base; a scheme-less base puns to null.
        Assert.assertNull(Uri.parseOrThrow("/rel").resolveOrNull("x"));

        Assert.assertEquals("http://h/p", Uri.parseOrThrow("http://h/p").toUrlOrThrow().href());
    }

    // --- Url ---

    @Test
    public void urlOpaqueOriginIsTheLiteralNullString() {
        Assert.assertFalse(Url.parseOrThrow("https://h/").hasOpaqueOrigin());

        Url mailto = Url.parseOrThrow("mailto:a@b.example");
        // The four-character String "null", NOT a null reference.
        Assert.assertEquals("null", mailto.origin());
        Assert.assertTrue(mailto.hasOpaqueOrigin());
    }

    @Test
    public void urlValidationErrorsListReflectsRepairs() {
        Assert.assertTrue(Url.parseOrThrow("https://example.com/a/b").validationErrors().isEmpty());

        // A backslash under a special scheme is repaired to '/', recorded (with its offset), and
        // the parse still succeeds.
        Url repaired = Url.parseOrThrow("https://example.com/a\\b");
        List<ValidationError> errors = repaired.validationErrors();
        Assert.assertFalse(errors.isEmpty());
        boolean hasBackslashError = false;
        for (ValidationError error : errors) {
            if (error.getKind() == ValidationErrorKind.BACKSLASH_AS_SOLIDUS) {
                hasBackslashError = true;
                Assert.assertEquals(21, error.getAt());
                Assert.assertFalse(error.isFailure());
            }
        }
        Assert.assertTrue(hasBackslashError);
        Assert.assertEquals("https://example.com/a/b", repaired.href());
    }

    @Test
    public void urlIsSpecial() {
        Assert.assertTrue(Url.parseOrThrow("https://h/").isSpecial());
        Assert.assertFalse(Url.parseOrThrow("mailto:a@b.example").isSpecial());
    }

    @Test
    public void urlBuilderQueryAndPathEditingAndBuildOrNull() {
        Url edited =
                Url.parseOrThrow("https://h/a/b/c?a=1")
                        .newBuilder()
                        .addQueryParameter("a", "2")
                        .setPathSegment(1, "X")
                        .removePathSegment(0)
                        .build();
        Assert.assertEquals("https://h/X/c?a=1&a=2", edited.href());

        Url appended = Url.parseOrThrow("https://h/").newBuilder().addPathSegments("x/y/z").build();
        Assert.assertEquals("https://h/x/y/z", appended.href());

        Url cleared = Url.parseOrThrow("https://h/?a=1").newBuilder().removeAllQueryParameters("a").build();
        Assert.assertNull(cleared.query());

        Url assembled = new Url.Builder().scheme("https").host("example.com").buildOrNull();
        Assert.assertNotNull(assembled);
        Assert.assertEquals("https://example.com/", assembled.href());

        // A special scheme with no host cannot form a URL; buildOrNull puns that to null.
        Assert.assertNull(new Url.Builder().scheme("https").buildOrNull());
    }

    @Test
    public void urlEffectivePortIsABoxedInteger() {
        // Assigning to Integer proves the boxed, nullable return: no -1 sentinel.
        Integer httpsPort = Url.parseOrThrow("https://h/").effectivePort();
        Assert.assertEquals(Integer.valueOf(443), httpsPort);

        Integer noDefault = Url.parseOrThrow("mailto:a@b.example").effectivePort();
        Assert.assertNull(noDefault);
    }

    @Test
    public void urlWithPortAndWithoutFragment() {
        Url ported = Url.parseOrThrow("https://h/").withPort(8443);
        Assert.assertEquals("https://h:8443/", ported.href());
        Assert.assertEquals(Integer.valueOf(8443), ported.port());

        Assert.assertNull(Url.parseOrThrow("https://h/p#x").withoutFragment().fragment());
    }

    @Test
    public void urlRedactStripsUserinfoQueryAndFragment() {
        Url redacted = Url.parseOrThrow("https://user:pass@h:8443/p?q=1#frag").redact();

        Assert.assertEquals("https://h:8443/p", redacted.href());
        Assert.assertEquals("", redacted.username());
        Assert.assertEquals("", redacted.password());
        Assert.assertNull(redacted.query());
        Assert.assertNull(redacted.fragment());
    }

    @Test
    public void urlIsDirectoryAndHasTrailingSlashAgree() {
        Url directory = Url.parseOrThrow("https://h/a/");
        Assert.assertTrue(directory.isDirectory());
        Assert.assertTrue(directory.hasTrailingSlash());

        Url file = Url.parseOrThrow("https://h/a");
        Assert.assertFalse(file.isDirectory());
        Assert.assertFalse(file.hasTrailingSlash());
    }

    // --- Query + ParseResult + Host ---

    @Test
    public void queryParametersParseGetAllAndHas() {
        QueryParameters params = QueryParameters.parse("a=1&a=2");

        Assert.assertEquals(Arrays.asList("1", "2"), params.getAll("a"));
        // contains() is @JvmSynthetic (hidden from Java); has() is the canonical Java membership test.
        Assert.assertTrue(params.has("a"));
        Assert.assertFalse(params.has("z"));
    }

    @Test
    public void queryParametersFormEncodingRoundTrip() {
        QueryParameters formSource = QueryParameters.of(new QueryParameter("a", "b c"));
        Assert.assertEquals("a=b+c", formSource.toFormUrlEncoded());

        QueryParameters parsedForm = QueryParameters.parseForm("a=b+c");
        Assert.assertEquals("b c", parsedForm.get("a"));
    }

    @Test
    public void queryParametersOfVarargPreservesDuplicates() {
        QueryParameters params = QueryParameters.of(new QueryParameter("k", "1"), new QueryParameter("k", "2"));

        Assert.assertEquals(Arrays.asList("1", "2"), params.getAll("k"));
        Assert.assertEquals("1", params.get("k"));
    }

    @Test
    public void parseResultErrPathIsReadableFromJava() {
        ParseResult<Uri> result = Uri.parse("://bad");

        Assert.assertFalse(result.isOk());
        Assert.assertTrue(result instanceof ParseResult.Err);

        UriParseError error = ((ParseResult.Err) result).getError();
        String message = error.getMessage();
        Assert.assertNotNull(message);
        Assert.assertFalse(message.isEmpty());
    }

    @Test
    public void parseResultOkPathFromJava() {
        ParseResult<Uri> result = Uri.parse("http://h/p");

        Assert.assertTrue(result.isOk());
        Assert.assertTrue(result instanceof ParseResult.Ok);
        Assert.assertEquals("http", result.getOrThrow().scheme());
    }

    @Test
    public void constructingAnInvalidIpv6HostThrows() {
        // An IPv6 host needs exactly eight pieces; two must be rejected inline.
        Assert.assertThrows(
                IllegalArgumentException.class,
                () -> new Host.Ipv6(java.util.List.of(1, 2), null));
    }
}
