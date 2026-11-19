package com.doro.organizer.model

/** The key mode of the committed key slot (DEC-60, security.md § 2): encryption off, Keystore key, startup password. */
enum class KeyMode { PLAIN, DEVICE, PASSWORD }
