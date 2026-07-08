/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.Url
import org.dexpace.kuri.error.ParseResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlFuzzTest {
    private val alphabet: String = "abc019:/?#@[]%.\\ \t\n&=+-_~<>^`{}|!$(),;*'\""

    private fun randomInput(random: Random): String {
        val length = random.nextInt(0, MAX_LEN)
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    @Test
    fun `parsing arbitrary input never throws and success round-trips`() {
        val random = Random(SEED)
        repeat(ITERATIONS) {
            val input = randomInput(random)
            when (val result = Url.parse(input)) {
                is ParseResult.Ok -> {
                    val href = result.value.href
                    assertEquals(href, Url.parseOrThrow(href).href, "fuzz href not idempotent for input <$input>")
                }
                is ParseResult.Err -> Unit // a rejected input is a valid outcome
            }
        }
    }

    private companion object {
        private const val SEED: Long = 0x5EEDL
        private const val ITERATIONS: Int = 20_000
        private const val MAX_LEN: Int = 24
    }
}
