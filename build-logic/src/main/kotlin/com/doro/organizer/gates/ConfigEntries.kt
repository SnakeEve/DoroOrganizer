package com.doro.organizer.gates

/** Entries of a `config/` list file: one per line; blank lines and lines starting with `#` are skipped. */
internal fun configEntries(text: String): List<String> =
    text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
