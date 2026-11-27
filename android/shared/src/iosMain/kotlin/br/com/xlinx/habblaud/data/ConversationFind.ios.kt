@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package br.com.xlinx.habblaud.data

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping

internal actual fun decomposeSearchText(text: String): String =
    NSString.create(string = text).decomposedStringWithCanonicalMapping
