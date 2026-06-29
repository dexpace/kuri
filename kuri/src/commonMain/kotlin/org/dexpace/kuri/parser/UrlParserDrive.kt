/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.text.isAsciiAlpha

/** The code points that may terminate a Windows drive letter prefix (`/`, `\`, `?`, `#`). */
private const val WINDOWS_DRIVE_TERMINATORS: String = "/\\?#"

/**
 * True when [value] *is* a Windows drive letter: exactly an ASCII alpha followed by `:`
 * or `|` (SPEC §9.4 [PATH-14]).
 */
internal fun isWindowsDriveLetter(value: String): Boolean =
    value.length == 2 && value[0].isAsciiAlpha() && (value[1] == ':' || value[1] == '|')

/**
 * True when [value] is a *normalized* Windows drive letter: an ASCII alpha followed by `:`
 * (SPEC §9.4 [PATH-14]).
 */
internal fun isNormalizedWindowsDrive(value: String): Boolean =
    value.length == 2 && value[0].isAsciiAlpha() && value[1] == ':'

/**
 * True when [value] *starts with* a Windows drive letter (SPEC §9.4 [PATH-14]): a drive
 * letter that is either the whole string or immediately followed by `/`, `\`, `?`, or `#`.
 */
internal fun startsWithWindowsDrive(value: String): Boolean {
    val hasDrive = value.length >= 2 && value[0].isAsciiAlpha() && (value[1] == ':' || value[1] == '|')
    return hasDrive && (value.length == 2 || value[2] in WINDOWS_DRIVE_TERMINATORS)
}
