package com.doro.organizer.testing

/**
 * The marker that redaction and leak tests plant into every text and secret field and then search for in
 * `toString()`, logs and messages (testing.md § 7, I-39). No other sentinel exists.
 */
const val SENTINEL = "Zq7SentinelXk"
