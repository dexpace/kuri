/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri;

import org.dexpace.kuri.error.ParseResult;
import org.dexpace.kuri.error.ResourceLimit;
import org.dexpace.kuri.error.UriParseError;
import org.junit.Assert;
import org.junit.Test;

/**
 * Exercises the resource-limit surface from a real Java call site: the four per-parse limit setters
 * on {@code ParseOptions.Builder}, the {@code ResourceLimit} registry with its {@code getDefaultMax()}
 * accessor, and the {@code UriParseError.LimitExceeded} variant with its {@code instanceof} test and
 * getters. A non-compiling or misbehaving Java call fails the build here.
 */
public final class ResourceLimitJavaTest {
    @Test
    public void builderExposesTheFourLimitSetters() {
        ParseOptions options =
                new ParseOptions.Builder()
                        .inputLength(1000)
                        .expandedLength(2000)
                        .pathSegments(5)
                        .resolutionDepth(16)
                        .build();

        Assert.assertEquals(1000, options.getInputLength());
        Assert.assertEquals(2000, options.getExpandedLength());
        Assert.assertEquals(5, options.getPathSegments());
        Assert.assertEquals(16, options.getResolutionDepth());
    }

    @Test
    public void resourceLimitDefaultsReadAsInt() {
        int inputLength = ResourceLimit.InputLength.getDefaultMax();
        int pathSegments = ResourceLimit.PathSegments.getDefaultMax();

        Assert.assertEquals(65536, inputLength);
        Assert.assertEquals(10000, pathSegments);
        Assert.assertEquals(7, ResourceLimit.values().length);
    }

    @Test
    public void limitExceededExposesItsLimitObservedAndMax() {
        ParseOptions options = new ParseOptions.Builder().pathSegments(2).build();

        ParseResult<Uri> result = Uri.parse("s:/a/b/c", options);

        Assert.assertTrue(result instanceof ParseResult.Err);
        UriParseError error = ((ParseResult.Err) result).getError();
        Assert.assertTrue(error instanceof UriParseError.LimitExceeded);

        UriParseError.LimitExceeded limit = (UriParseError.LimitExceeded) error;
        Assert.assertEquals(ResourceLimit.PathSegments, limit.getLimit());
        Assert.assertEquals(3, limit.getObserved());
        Assert.assertEquals(2, limit.getMax());
    }
}
