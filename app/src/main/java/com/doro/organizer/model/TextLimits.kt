package com.doro.organizer.model

/** The most UTF-16 units a note `content` holds (DEC-24, data-layer.md INV-1). */
const val MAX_CONTENT_CHARS = 200_000

/** The most UTF-16 units a person or project name holds after `TextWhitespace.trim` (DEC-32, data-layer.md INV-9). */
const val MAX_NAME_CHARS = 200
