/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

/** Inclusive lower bound of the Unicode code-point space. */
private const val MIN_CODE_POINT: Int = 0x0

/** Inclusive upper bound of the Unicode code-point space (U+10FFFF). */
private const val MAX_CODE_POINT: Int = 0x10FFFF

/** Radix used to decode each record's hex range-start. */
private const val RADIX_HEX: Int = 16

/** Separator between records in the decoded blob (never appears inside a record). */
private const val RECORD_SEPARATOR: Char = '\n'

/** Separator between a record's hex start and its payload. */
private const val FIELD_SEPARATOR: Char = ' '

/** Payload kind letter: the code point is valid as-is. */
private const val KIND_VALID: Char = 'V'

/** Payload kind letter: the code point is ignored (dropped). */
private const val KIND_IGNORED: Char = 'I'

/** Payload kind letter: the code point is disallowed. */
private const val KIND_DISALLOWED: Char = 'D'

/** Payload kind letter: the code point is mapped; the rest of the payload is the replacement. */
private const val KIND_MAPPED: Char = 'M'

/** Payload kind letter: the code point is a deviation character (kept, non-transitional). */
private const val KIND_DEVIATION: Char = 'Y'

/**
 * The bundled UTS-46 IDNA mapping table (Unicode 17.0.0), implementing the mapping step of
 * domain-to-ASCII processing (SPEC §7.4, [HOST-26] step 2; parameters per [HOST-28]).
 *
 * The compact data in [IDNA_MAPPING_TABLE_CHUNKS] is decoded once, lazily, into two parallel
 * arrays: [DecodedTable.starts] holds each range's inclusive start code point (sorted, gap-free,
 * beginning at U+0000), and [DecodedTable.mappings] holds the corresponding [IdnaMapping]. A range
 * runs until the next range's start, so a single binary search over the starts locates any code
 * point's status. Decoded [IdnaMapping] instances are shared, so [map] performs no allocation.
 */
internal object IdnaMappingTable {
    /** Decoded form of the mapping table: range starts paired with their mapping outcomes. */
    private class DecodedTable(
        val starts: IntArray,
        val mappings: Array<IdnaMapping>,
    )

    private val table: DecodedTable by lazy { decode() }

    /**
     * Returns the UTS-46 mapping outcome for [codePoint].
     *
     * @param codePoint a Unicode code point in `0..0x10FFFF`.
     * @return the [IdnaMapping] for the range containing [codePoint].
     */
    internal fun map(codePoint: Int): IdnaMapping {
        require(codePoint in MIN_CODE_POINT..MAX_CODE_POINT) { "code point out of range: $codePoint" }
        val index = findRangeIndex(table.starts, codePoint)
        check(index in table.mappings.indices) { "no range covers code point: $codePoint" }
        return table.mappings[index]
    }

    /**
     * Binary searches [starts] for the greatest index whose value is `<= codePoint`.
     *
     * [starts] is sorted, gap-free and begins at U+0000, so for any in-range [codePoint] a covering
     * range always exists. Returns `-1` only for the impossible case of an empty table.
     */
    private fun findRangeIndex(
        starts: IntArray,
        codePoint: Int,
    ): Int {
        require(starts.isNotEmpty()) { "mapping table is empty" }
        var low = 0
        var high = starts.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= codePoint) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /** Concatenates the data chunks and decodes them into the parallel [DecodedTable] arrays. */
    private fun decode(): DecodedTable {
        val records = IDNA_MAPPING_TABLE_CHUNKS.joinToString(separator = "").split(RECORD_SEPARATOR)
        check(records.isNotEmpty()) { "mapping table data is empty" }
        val starts = IntArray(records.size)
        val mappings =
            Array<IdnaMapping>(records.size) { index ->
                val record = records[index]
                val separator = record.indexOf(FIELD_SEPARATOR)
                check(separator > 0) { "malformed record: $record" }
                starts[index] = record.substring(0, separator).toInt(RADIX_HEX)
                decodePayload(record, separator + 1)
            }
        check(starts[0] == MIN_CODE_POINT) { "mapping table must start at U+0000" }
        return DecodedTable(starts, mappings)
    }

    /** Decodes a single record's payload (kind letter plus optional replacement) into a mapping. */
    private fun decodePayload(
        record: String,
        payloadStart: Int,
    ): IdnaMapping =
        when (val kind = record[payloadStart]) {
            KIND_VALID -> IdnaMapping.Valid
            KIND_IGNORED -> IdnaMapping.Ignored
            KIND_DISALLOWED -> IdnaMapping.Disallowed
            KIND_DEVIATION -> IdnaMapping.Deviation
            KIND_MAPPED -> IdnaMapping.Mapped(record.substring(payloadStart + 1))
            else -> error("unknown mapping kind '$kind' in record: $record")
        }
}
