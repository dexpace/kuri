/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url

/** Which profile a bind targets. Fixed by the entry point / base-builder type. */
internal enum class Profile { URL, URI }

/** Binds an annotated object onto a `Url.Builder`. Implemented by reflection now; by codegen later. */
internal interface UrlBinder {
    fun bind(
        base: Url.Builder,
        target: Any,
        options: BindOptions,
    ): Url.Builder
}

/** Binds an annotated object onto a `Uri.Builder`. */
internal interface UriBinder {
    fun bind(
        base: Uri.Builder,
        target: Any,
        options: BindOptions,
    ): Uri.Builder
}
