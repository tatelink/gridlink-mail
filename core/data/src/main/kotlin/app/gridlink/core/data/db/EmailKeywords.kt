package app.gridlink.core.data.db

/**
 * The custom keywords ("tags") a message carries, and how they are packed into
 * [EmailEntity.keywordsJson].
 *
 * ⚠️ Deliberately NOT the JSON that [EmailRecipients] uses, and that is the one interesting
 * decision here. Recipients are only ever read back into an object; tags have to be QUERIED —
 * the tag filter chip narrows the list in SQL, before the window's `LIMIT`, or it would be
 * answering "tagged, among the fifty most recent". So the column is stored in a form a `LIKE`
 * can match exactly: every name is lowercased, space-separated, and the whole string is wrapped
 * in one leading and one trailing space.
 *
 *     " work urgent "   matched by   keywordsJson LIKE '% work %'
 *
 * The wrapping spaces are what make that exact rather than a substring guess: without them
 * `'%work%'` would also match a tag called "homework". A name can never contain a space because
 * [toKeyword] is the only way one is minted and it emits `[a-z0-9_-]+`, which is also the
 * intersection of what JMAP (RFC 8621 §4.1.1 keywords) and IMAP (RFC 9051 atoms) will both carry.
 *
 * 🔴 System keywords are NEVER stored here. `$seen` and `$flagged` have their own columns and are
 * the app's read/star state; letting them in as well would give the user a removable "seen" tag
 * that fights the read state. [custom] is the filter every ingest path runs the server's keyword
 * set through, and it drops anything starting with `$` (JMAP) or `\` (IMAP) on principle rather
 * than by a list, so a system keyword invented after this was written is still excluded.
 */
object EmailKeywords {

    /** The separator/wrapper. One space, so the column reads plainly in a DB browser. */
    private const val SEP = " "

    /** Longest name we mint or accept. IMAP atoms have no hard limit; servers do. */
    const val MAX_LENGTH: Int = 64

    /**
     * The server's raw keyword set → the custom ones, canonicalised.
     *
     * Lowercased (both protocols treat keywords case-insensitively, so "Work" and "work" are one
     * tag and must not become two), de-duplicated, and sorted so two syncs of the same message
     * produce byte-identical column values and don't churn the cache.
     */
    fun custom(keywords: Collection<String>): List<String> =
        keywords.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("$") && !it.startsWith("\\") }
            .map { it.take(MAX_LENGTH) }
            .distinct()
            .sorted()
            .toList()

    /**
     * A label the user typed → the token that goes on the wire.
     *
     * Everything outside `[a-z0-9_-]` collapses to a single `-`, because that is the safe
     * intersection of a JMAP keyword and an IMAP atom, and a server that rejects one keyword
     * rejects the whole STORE. The display label with its capitals, spaces and emoji lives
     * on-device beside the colour; only this token syncs.
     *
     * Returns null when nothing usable survives (a label of only punctuation), which the caller
     * shows as "pick another name" rather than silently creating a tag with no wire identity.
     */
    fun toKeyword(label: String): String? {
        val slug = label.trim().lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-', '_')
            .take(MAX_LENGTH)
            .trim('-', '_')
        return slug.ifEmpty { null }
    }

    /**
     * A wire keyword -> the label to START a definition from, the loose inverse of [toKeyword].
     *
     * Loose because [toKeyword] is lossy: "Orders & Shipping" and "orders shipping" both slug to
     * the same token, and neither the ampersand nor the original casing survives to be restored.
     * So this makes no attempt to guess what was typed. It un-slugs to something a person would
     * accept without editing ("orders-shipping" -> "Orders shipping") and leaves the editor open
     * on it, which is the difference between adopting a tag and re-typing one.
     *
     * Sentence case, not title case: these are labels on a chip, and "Job Search" reads like a
     * proper noun where "Job search" reads like the thing it is.
     */
    fun toLabel(keyword: String): String {
        val words = keyword.split('-', '_').filter { it.isNotBlank() }
        if (words.isEmpty()) return keyword
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    /** Canonical names → the column value. Empty encodes to null: nothing to store. */
    fun encode(keywords: Collection<String>): String? {
        val canonical = custom(keywords)
        if (canonical.isEmpty()) return null
        return SEP + canonical.joinToString(SEP) + SEP
    }

    /** The column value → names. Total: null, blank, or a pre-v24 row all decode to empty. */
    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SEP).filter { it.isNotBlank() }
    }

    /**
     * The `LIKE` pattern that matches rows carrying [keyword], for the tag filter chip.
     *
     * 🔴 Built here rather than at the query site so the wrapping spaces can never drift apart
     * from [encode]. A pattern assembled by hand as `'%' || name || '%'` would match "homework"
     * for the tag "work" — the exact bug the wrapping exists to prevent.
     */
    fun likePattern(keyword: String): String = "%$SEP${keyword.trim().lowercase()}$SEP%"
}
