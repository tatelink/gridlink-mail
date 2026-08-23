package app.gridlink.core.data.settings

import app.gridlink.core.data.db.EmailKeywords
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One colour-coded tag: what the server calls it, what the reader calls it, and what colour it is.
 *
 * ## 🔴 Only [keyword] syncs. The other two are ours alone.
 * Neither JMAP (RFC 8621 §4.1.1) nor IMAP (RFC 9051 §2.3.2) carries anything about a keyword
 * except its name — no colour, no display casing, no ordering. So a tag is two halves that live in
 * two places: the keyword goes on the message and every other client on the mailbox sees it, while
 * the label and the colour live in this device's settings and travel only in a settings backup.
 *
 * That is not a limitation to work around, and in particular it must NOT be worked around by
 * encoding the colour into the keyword ("work-blue") the way some clients have: the colour would
 * then be unchangeable without re-tagging every message, and every other client would display the
 * word "blue" as part of the tag's name.
 *
 * The practical consequence, which the tag manager says out loud rather than hiding: install
 * Gridlink on a second phone and the tags are all there, in grey, under their wire names, until
 * that phone's settings are restored from a backup or the colours are picked again.
 *
 * @param keyword the wire name, always [EmailKeywords.toKeyword]'s output: `[a-z0-9_-]+`.
 * @param label what the chip reads, as typed — capitals, spaces, emoji and all.
 * @param color a [TagColor] name. Stored by name, not by value, so a palette tweak reaches every
 *   tag already created instead of leaving them on the old hex.
 */
@Serializable
data class MailTag(
    val keyword: String,
    val label: String,
    val color: String = TagColor.BLUE.name,
) {
    /** The palette entry, falling back rather than throwing on a name from a newer build. */
    val tagColor: TagColor get() = TagColor.byName(color)
}

/**
 * The fixed tag palette.
 *
 * ⚠️ Fixed, and a free colour picker was declined on purpose: Tate's answer to how tag colours
 * should be chosen was "You pick, from a fixed palette" (2026-08-12). A curated set stays legible
 * on the Gridlink glass at chip size, where an arbitrary picked colour is one tap away from dark
 * grey text on a dark grey pill.
 *
 * 🔴 No purple, and that is not an oversight — see the house style doc. The set runs warm to cool
 * and stops at slate.
 *
 * Each entry is one vivid ARGB. The chip's fill is derived from it at low alpha in the UI rather
 * than being a second stored colour, so a tag can never end up with a fill and a border that
 * disagree.
 */
enum class TagColor(val argb: Long) {
    RED(0xFFE5484D),
    ORANGE(0xFFF76B15),
    AMBER(0xFFFFB224),
    GREEN(0xFF30A46C),
    TEAL(0xFF12A594),
    CYAN(0xFF00A2C7),
    BLUE(0xFF3E8FF9),
    SLATE(0xFF8B95A5),
    ;

    companion object {
        /** By name, falling back to [BLUE] for a value this build does not know. */
        fun byName(name: String?): TagColor =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BLUE

        /**
         * The colour to draw a tag that has no definition on this device — one another client
         * invented, or this device's own before a settings restore.
         *
         * Derived from the name so it is at least STABLE: the same tag is the same colour on every
         * screen and across restarts, rather than flickering as the list re-composes. It is still
         * a guess, and the tag manager offers to adopt the tag and pick properly.
         */
        fun forUnknown(keyword: String): TagColor =
            entries[(keyword.hashCode().toUInt() % entries.size.toUInt()).toInt()]
    }
}

/**
 * Definitions for keywords already on the mail, several at a time.
 *
 * ## Why this is not just a loop over the one-at-a-time path
 * Adopting one tag lets the reader pick its colour. Adopting ten cannot ask ten times, so the
 * colours are chosen here, and the choice has to satisfy two things that pull against each other:
 *
 * - Each keyword is ALREADY drawing somewhere, as a dot on a message row and in this screen's own
 *   list, in [TagColor.forUnknown]'s derived colour. Adopting it should not repaint it out from
 *   under the reader, so that colour is the first preference.
 * - [TagColor.forUnknown] is a hash into eight entries, so ten keywords will collide. Handing back
 *   three identical dots is the exact outcome the palette exists to prevent.
 *
 * So: take the derived colour when it is still free, otherwise the first free entry, and once the
 * palette is exhausted wrap round rather than refusing to adopt. Order is the caller's, which is
 * the order the reader is looking at.
 *
 * Keywords already defined in [existing] are skipped rather than redefined — adopting is additive,
 * and a second definition for the same wire name is the one thing that would make the manager lie.
 *
 * @return only the NEW definitions, for the caller to append.
 */
fun adoptTags(keywords: List<String>, existing: List<MailTag>): List<MailTag> {
    val defined = existing.map { it.keyword }.toSet()
    val taken = existing.map { it.tagColor }.toMutableSet()
    val adopted = mutableListOf<MailTag>()
    for (keyword in keywords) {
        if (keyword in defined) continue
        val derived = TagColor.forUnknown(keyword)
        val color = when {
            derived !in taken -> derived
            taken.size < TagColor.entries.size -> TagColor.entries.first { it !in taken }
            else -> TagColor.entries[(existing.size + adopted.size) % TagColor.entries.size]
        }
        taken += color
        adopted += MailTag(
            keyword = keyword,
            label = EmailKeywords.toLabel(keyword),
            color = color.name,
        )
    }
    return adopted
}

/**
 * The user's tag definitions, as one DataStore string.
 *
 * A single JSON blob rather than a `stringSetPreferencesKey` of packed rows, because a set has no
 * order and the tag manager's list does: tags are shown in the order they were created, and a set
 * would reshuffle them on every write.
 */
object MailTagCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MailTag.serializer())

    fun encode(tags: List<MailTag>): String = json.encodeToString(serializer, tags)

    /** Total: null, blank or unparseable all decode to "no tags defined". */
    fun decode(raw: String?): List<MailTag> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
