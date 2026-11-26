package br.com.xlinx.habblaud.data

import java.text.Normalizer

internal actual fun decomposeSearchText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
