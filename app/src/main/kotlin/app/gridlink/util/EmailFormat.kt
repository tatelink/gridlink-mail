package app.gridlink.util

/**
 * Lightweight email-format check: a non-empty local part, an `@`, a domain, and a dot-separated
 * extension of at least two characters — so `a@b` and `a@b.c` are rejected but `name@example.com`
 * passes. Deliberately NOT full RFC 5322: just enough to catch typos (missing `@`, missing or
 * one-character TLD). Pure, so it is unit-tested.
 */
val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s.]{2,}$")

/** True when [address] (trimmed) looks like a well-formed email address. See [EMAIL_REGEX]. */
fun isValidEmail(address: String): Boolean = EMAIL_REGEX.matches(address.trim())
