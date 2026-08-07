package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * RESOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument as [TranslationParityTest]: it reads
 * the nine `strings.xml` as text and proves nothing about what the dialog does on screen.
 *
 * What it closes is #127. The composer's delete confirmation renders its body in the `text` slot of
 * an `AlertDialog`, a height-bounded box. That slot used to have NO `verticalScroll`, so whatever
 * did not fit was cut, with no ellipsis and no way to reach it: at `font_scale 2.0` the
 * 169-character English body — 205 in German, French and Dutch — lost its last sentence, which is
 * precisely the one saying that what was typed since the composer opened is not kept.
 *
 * ⚠ The slot SCROLLS now, and [ComposeDeleteWiringTest] pins that, so the silent cut is gone. The
 * cap below is not: it bounds the LENGTH, which scrolling does not. A body that overflows still
 * asks the reader for a scroll gesture that nothing on screen announces, and a sentence pushed
 * under the fold is a sentence she has no reason to look for. So do not read a green run here as
 * "the dialog fits": read it as "the body stayed inside the budget it was given".
 *
 * The CAP is the instrument. A `contains` on an expected word is blind to everything a translation
 * ADDS, and growing back is exactly how this string broke; the cap is the only guard that sees a
 * sentence come back. The word tables under it carry the other half: that shortening did not drop
 * one of the two facts the dialog owes the reader, in this order — (a) where the draft goes (the
 * Trash, or deletion when this account has no folder recognised as one), and (b) that anything
 * typed since it opened is not saved.
 *
 * The tables are deliberately literal, one row per language, no rule recomputed from the text: a
 * translator who rewords this dialog has to come here and say so. [ComposeDeleteWiringTest] refuses
 * the outbox's own delete strings at the call site; this refuses their words inside the translation,
 * where a translator working from a glossary would naturally reach for them — and they are false
 * here, since this draft lives on the server and goes to the Trash exactly as it would from the
 * list.
 */
class DraftDeleteBodyTest {

    /**
     * ⭐ The rule of #127. 140 is a GROWTH CEILING, not a measurement of the slot and not a
     * derivation: it is set below the only length ever SEEN to fail — 147, German, on a Moto G
     * (Android 9, 720×1280, `font_scale 2.0`) on 2026-08-07.
     *
     * What the bench actually saw, and nothing more:
     *  - 140 held, in FRENCH, with the dialog's buttons on ONE line. That layout is WIDER than the
     *    German one, where the buttons STACK and eat a line of the slot, so the French reading does
     *    NOT transpose to German.
     *  - 147 was cut in German, and the cut fell at “…getippt hast, wird”: the missing
     *    “nicht gespeichert.” is 19 characters. So the German layout was showing on the order of
     *    ~128 characters — an order of magnitude DEDUCED from where the text stopped, never
     *    measured. No German reading supports 140, and the German shipped here (136) sits ABOVE
     *    that single German figure.
     *  - Russian held at 130 while filling the screen.
     *
     * ⛔ This cap does NOT guarantee the body fits. What removes the silent cut is the
     * `verticalScroll` on the slot, which [ComposeDeleteWiringTest] pins. The cap only bounds what
     * goes under the fold — a scroll gesture nothing on screen announces. The bench stays the only
     * judge, and neither the German at 136 nor the Dutch at 135 has ever been seen on a screen.
     *
     * ⚠ Accepted debt, deliberately left open: raising this constant (140 → 160) keeps every test
     * in this file green — nothing pins its VALUE. A test asserting `CAP == 140` would copy the
     * code instead of guarding it, so the number is re-read by hand, like any bound of this kind.
     * Lower it when the bench sees a shorter body run off the bottom, and rewrite the translation
     * that broke it — never truncate.
     */
    @Test
    fun `the draft-delete body fits the dialog in every language`() {
        val overlong = bodies()
            .filterValues { it.length > CAP }
            .map { (locale, text) -> "$locale: ${text.length} chars (cap $CAP) — $text" }
        assertEquals(
            "the composer's delete confirmation renders in an AlertDialog text slot, a bounded " +
                "box. It scrolls, so past the cap nothing is cut any more — but the end of the " +
                "sentence goes under the fold, reachable only by a scroll gesture nothing on " +
                "screen announces, and what sits at the end is the fact that unsaved typing is " +
                "lost (#127). Shorten the translation, do not truncate it — both facts must survive",
            emptyList<String>(),
            overlong,
        )
    }

    /**
     * The cap alone would be satisfied by an empty string, so each language states, in order, the
     * destination of the draft and the fate of what was typed. One row per language, by hand.
     */
    @Test
    fun `every language still states where the draft goes and what happens to the typing`() {
        assertEquals("a language ships without a row in the expected-words table", LOCALES, REQUIRED.keys.sorted())
        val broken = bodies().flatMap { (locale, text) ->
            val required = REQUIRED.getValue(locale)
            val positions = required.map { text.indexOf(it, ignoreCase = true) }
            val missing = required.zip(positions).filter { it.second < 0 }.map { it.first }
            when {
                missing.isNotEmpty() -> listOf("$locale: missing $missing in “$text”")
                positions != positions.sorted() ->
                    listOf("$locale: $required out of order (found at $positions) in “$text”")
                else -> emptyList()
            }
        }
        assertEquals(
            "the body must still say (a) that the draft goes to the Trash, or is deleted when this " +
                "account has no folder recognised as a Trash, then (b) that anything typed since the " +
                "composer opened is not saved. Shortening may not cost a fact",
            emptyList<String>(),
            broken,
        )
    }

    /**
     * The words this dialog may not borrow, per language: the outbox's ("isn't saved anywhere else",
     * "will be lost" and their calques — false here, the draft is on the server and recoverable), an
     * unreserved "for good" (the destroy sits behind a five-second Undo), a possessive "your Trash
     * folder" (the code tests for a folder it RECOGNISES as one, not for one the reader owns), and
     * the affirmative future (offline the move fails and is not queued, so the present tense is what
     * is true: it describes the action attempted). German has no row for the future: its present
     * passive is built on `wird`.
     */
    @Test
    fun `no language borrows the outbox's words, or promises the future`() {
        assertEquals("a language ships without a row in the forbidden-words table", LOCALES, FORBIDDEN.keys.sorted())
        val borrowed = bodies().flatMap { (locale, text) ->
            FORBIDDEN.getValue(locale)
                .filter { text.contains(it, ignoreCase = true) }
                .map { "$locale: “$it” in “$text”" }
        }
        assertEquals(
            "this draft goes to the Trash and can be brought back; the outbox's message cannot, and " +
                "its wording is a lie here. Nor may the dialog promise a move that fails offline",
            emptyList<String>(),
            borrowed,
        )
    }

    /** There is no format argument in this string, and a translator must not invent one. */
    @Test
    fun `the draft-delete body takes no format argument`() {
        val withArgs = bodies()
            .filterValues { PLACEHOLDER.containsMatchIn(it) }
            .map { (locale, text) -> "$locale: $text" }
        assertEquals(
            "compose_delete_draft_body is read with a plain stringResource(): a %1\$s added in one " +
                "language renders as itself, or throws",
            emptyList<String>(),
            withArgs,
        )
    }

    // -- reading the resources ----------------------------------------------------------------

    /** `compose_delete_draft_body` as the reader sees it, per `values*` directory. */
    private fun bodies(): Map<String, String> {
        val files = (res.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.startsWith("values") }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }
        val found = files.mapNotNull { file ->
            BODY.find(file.readText())?.let { file.parentFile.name to rendered(it.groupValues[1]) }
        }.toMap()
        assertEquals("compose_delete_draft_body is missing from a language", LOCALES, found.keys.sorted())
        return found
    }

    /**
     * The XML text as it reaches the screen: entities resolved, Android's backslash escapes dropped
     * — `\'` is one apostrophe on screen and must be counted as one character, not two.
     */
    private fun rendered(raw: String): String = raw
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#160;", " ").replace("&amp;", "&")
        .replace(Regex("""\\(.)"""), "$1")
        .trim()

    private companion object {
        const val CAP = 140

        val LOCALES = listOf(
            "values", "values-de", "values-es", "values-fr", "values-it",
            "values-nl", "values-pl", "values-pt", "values-ru",
        )

        /**
         * Per language, in this order: the destination, the deletion verb, the NEGATION that
         * carries the branch, the unsaved typing.
         *
         * The negation is load-bearing and was added after a review found the mutation it lets
         * through: drop `no` from "has no Trash folder" and every other row still matches, in
         * order, under the cap — while the sentence now announces destruction to precisely the
         * accounts that have a Trash. The words weigh nothing on their own; the polarity is the
         * fact. (`MailRepository.deleteWouldDestroy`, `MailRepository.kt:3197-3200`: destroy is
         * the branch taken when NO mailbox is recognised as the trash role.)
         *
         * German and Dutch stopped repeating the Trash's name in that clause to fit the cap, so
         * their anchor is the WHOLE clause — `Konto keinen hat`, `account er geen heeft` — and not
         * the bare `keinen` / `geen`. A review demonstrated why: with the bare word, both of these
         * pass, in order, under the cap. “…wird gelöscht, wenn du keinen hast” makes the
         * destruction depend on what the READER owns, which the forbidden-words rule below exists
         * to refuse — the code branches on a folder it RECOGNISES as the trash role. And “…of
         * wordt verwijderd als er geen ruimte meer is” drops the real condition altogether and
         * invents a disk-space one. A negation has to stay attached to what it negates, as the
         * seven other rows do (`no Trash folder`, `n'a pas de dossier`, `non ha una cartella`).
         * That is rigid on purpose: rewording this clause means coming here and saying so.
         *
         * The Trash is named as the app names it elsewhere — each row matches that language's
         * `folder_trash`, which is why Portuguese says Lixeira and not Lixo.
         */
        val REQUIRED = mapOf(
            "values" to listOf("Trash", "deleted", "no Trash folder", "isn't saved"),
            "values-de" to listOf("Papierkorb", "gelöscht", "Konto keinen hat", "nicht gespeichert"),
            "values-es" to listOf("Papelera", "se elimina", "no tiene carpeta", "no se guarda"),
            "values-fr" to listOf("Corbeille", "supprimé", "n'a pas de dossier", "n'est pas enregistré"),
            "values-it" to listOf("Cestino", "eliminata", "non ha una cartella", "non viene salvato"),
            "values-nl" to listOf("Prullenbak", "verwijderd", "account er geen heeft", "niet opgeslagen"),
            "values-pl" to listOf("Kosz", "usunięta", "nie ma folderu", "nie jest zapisywane"),
            "values-pt" to listOf("Lixeira", "eliminado", "não tiver pasta", "não é guardado"),
            "values-ru" to listOf("Корзин", "удаляется", "нет папки", "не сохраняется"),
        )

        /**
         * Per language: outbox calques, unreserved destruction, possessive Trash, plain future.
         *
         * The destruction row lists the idiom THIS app actually uses for "forever" — the one a
         * translator reaching for the glossary would find. A review caught five languages banning
         * a synonym the app never writes while leaving the house idiom open: `empty_trash_body`
         * says "pour de bon", "para siempre", "per sempre", "voorgoed", "de vez", not
         * "définitivement". Both are banned now.
         *
         * ⚠ Known gap, accepted: the possessive is one inflected form per language, so a declined
         * "в вашу Корзину" or "deinem Papierkorb" slips through. Chasing every case would cost
         * more than the rule is worth — this row catches the careless reach, not the determined one.
         */
        val FORBIDDEN = mapOf(
            "values" to listOf("anywhere else", "will be lost", "for good", "forever", "your Trash", "will "),
            "values-de" to listOf("nirgendwo sonst", "gehen verloren", "endgültig", "für immer", "deinen Papierkorb"),
            "values-es" to listOf(
                "ningún otro", "perderán", "definitivamente", "para siempre", "tu carpeta", "se eliminará", "irá",
            ),
            "values-fr" to listOf(
                "nulle part ailleurs", "perdus", "définitivement", "pour de bon", "votre dossier", "sera", "ira",
            ),
            "values-it" to listOf(
                "altra parte", "andranno persi", "definitivamente", "per sempre", "tua cartella", "verrà", "andrà",
            ),
            "values-nl" to listOf("nergens anders", "gaan verloren", "definitief", "voorgoed", "je Prullenbak", "zal "),
            "values-pl" to listOf("nigdzie indziej", "utracon", "na dobre", "na zawsze", "twoj", "zostanie"),
            "values-pt" to listOf(
                "nenhum outro", "serão perdidos", "definitivamente", "de vez", "sua pasta", "será", "irá",
            ),
            "values-ru" to listOf("нигде больше", "будут потеряны", "навсегда", "вашей", "будет"),
        )

        val BODY = Regex(
            """<string\s+name="compose_delete_draft_body"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val PLACEHOLDER = Regex("""%(?:\d+\$)?[sd]""")

        /** Repo root, found by walking up from the module's working directory. */
        val res: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/res") }
                .firstOrNull { File(it, "values/strings.xml").isFile }
                ?: error(
                    "cannot locate app/src/main/res from ${File("").absolutePath} — this test reads " +
                        "the resources as text and needs a working directory inside the checkout",
                )
        }
    }
}
