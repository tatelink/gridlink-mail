package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads four source files as text and proves nothing about
 * what is drawn. Same instrument and disclaimer as [UnreadTintWiringTest].
 *
 * `SettingsBackupPureBlackTest` runs the decision (the default, the key, the backup field) and
 * `PulledToBlackTest` runs `applyPureBlack`, the pure function this switch feeds. Neither can see
 * the PLUMBING: `MainActivity`, `SternaTheme`, the view model and the Appearance screen are all
 * Compose or Android code, and this module has no Robolectric, no `compose-ui-test`, no
 * `androidTest` — adding one is a dependency decision, not a test decision. Four silent edits:
 *
 *  1. ⭐ **the setting collected and never handed to the theme.** `SternaTheme`'s `pureBlack`
 *     parameter already defaults to `false`, so dropping the argument compiles, keeps every value
 *     test green, and leaves the switch storing a preference nothing reads;
 *  2. ⭐ **the default written out as a literal.** It exists in THREE copies — the repository, the
 *     activity's `initial =` and the view model's `initialValue =`. A `true` in either of the last
 *     two paints the first frames after launch black for people who never asked;
 *  3. the switch moved back INSIDE the `Build.VERSION.SDK_INT >= S` guard it sits after: dynamic
 *     colour needs Android 12, a black background does not, and inside that guard the setting
 *     silently disappears on everything older;
 *  4. the switch wired to the wrong string keys, or missing from the backup snapshot / restore.
 *
 * Every rule compares a WHOLE line (leading and trailing blanks removed, nothing else), never a
 * `contains` for something it expects to find: a substring check is blind to any mutation that
 * LENGTHENS the line — `initial = PURE_BLACK_DEFAULT || true` contains the constant. `contains`
 * appears only where this file screens for something that must NOT be there.
 */
class PureBlackWiringTest {

    // -- 1 & 2. the default and the theme argument -----------------------------------------------

    @Test fun `the repository states the default once, and it is off`() {
        assertEquals(
            "SettingsRepository.kt must declare 'const val PURE_BLACK_DEFAULT = false' — the one " +
                "definition the other two copies read. Flipped here, the update that ships this " +
                "setting turns every dark-theme user's app black without anyone asking.",
            listOf("const val PURE_BLACK_DEFAULT = false"),
            codeLines(SETTINGS_REPOSITORY).filter { "PURE_BLACK_DEFAULT =" in it },
        )
    }

    @Test fun `the activity's first frame reads the shared default`() {
        assertEquals(
            "MainActivity.kt must collect the setting as '$EXPECTED_ACTIVITY_COLLECT' — the whole " +
                "line. A literal here is a second copy of the default: disagree with the " +
                "repository and the theme repaints itself one frame after launch, every launch.",
            listOf(EXPECTED_ACTIVITY_COLLECT),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("val pureBlack by") },
        )
    }

    @Test fun `the activity hands the value it collected to the theme`() {
        assertEquals(
            "MainActivity.kt must call '$EXPECTED_THEME_CALL' — the whole line, arguments " +
                "included. SternaTheme's pureBlack parameter defaults to false, so dropping the " +
                "argument (or passing a literal) compiles and leaves the switch inert.",
            listOf(EXPECTED_THEME_CALL),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("SternaTheme(") },
        )
    }

    /** The negative screen — a `contains`, deliberately: it looks for what must be absent. */
    @Test fun `no copy of the default is spelled out as a boolean`() {
        val offenders = codeLines(MAIN_ACTIVITY)
            .filter { "pureBlack" in it || "PURE_BLACK" in it }
            .filter { Regex("""\b(true|false)\b""").containsMatchIn(it) }
        assertEquals(
            "no line about this setting in MainActivity.kt may write 'true' or 'false': the first " +
                "frame must read PURE_BLACK_DEFAULT, and the theme must be handed the collected " +
                "value, not a constant of its own. A literal that agrees with the repository " +
                "today is the one that silently disagrees with it tomorrow. Found:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the settings screen's first frame reads the shared default too`() {
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "val pureBlack = settings.pureBlack.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = PURE_BLACK_DEFAULT,",
                ")",
            ),
            "the view model's pureBlack state",
        )
    }

    // -- 3 & 4. the switch on the Appearance screen ----------------------------------------------

    @Test fun `the theme section carries the switch, with its own two strings`() {
        val section = themeSection().map { it.second }
        val at = switchAt(section)
        assertTrue(
            "the 'Theme' section of AppearanceScreen must contain a SettingSwitch reading " +
                "settings_pure_black_* — the setting is otherwise stored, read and unreachable. " +
                "Section was:\n" + section.joinToString("\n"),
            at >= 0,
        )
        val found = section.subList(at, section.size)
        val mismatches = EXPECTED_SWITCH.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == EXPECTED_SWITCH[i]) null
            else "line ${i + 1} of the switch: expected '${EXPECTED_SWITCH[i]}' but found '$actual'"
        }
        assertEquals(
            "the switch is pinned WHOLE, line by line and in order: its two string keys (a row " +
                "labelled with the neighbouring setting's text is a lie the parity test cannot " +
                "see), the state it shows, and the setter it calls. Mismatches:\n" +
                mismatches.joinToString("\n") + "\nat index $at",
            emptyList<String>(),
            mismatches,
        )
    }

    @Test fun `the switch sits outside the Android 12 guard, between it and the caption`() {
        val section = themeSection()
        val lines = section.map { it.second }
        val guard = lines.indexOfFirst { it.startsWith("if (Build.VERSION.SDK_INT") }
        check(guard >= 0) {
            "no Android-12 guard in the Theme section of SettingsScreen.kt — the section was " +
                "reshaped and this lint must be taught the new shape rather than left green"
        }
        val switch = switchAt(lines)
        check(switch >= 0) { "no black-background switch in the Theme section of SettingsScreen.kt" }
        val caption = lines.indexOfFirst { it == "text = stringResource(R.string.settings_theme_sterna_caption)," }
        assertTrue(
            "the black-background switch must come AFTER the Material You guard and BEFORE the " +
                "palette caption. Guard at $guard, switch at $switch, caption at $caption:\n" +
                lines.joinToString("\n"),
            switch in (guard + 1) until caption,
        )
        assertEquals(
            "the switch must sit at the top level of the Theme section, NOT inside " +
                "'if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)'. That guard exists because " +
                "dynamic colour needs Android 12; a black background does not, and nested there " +
                "the setting vanishes on every older device — with the suite green.",
            0,
            section[switch].first,
        )
    }

    /** The negative screen for the switch itself: nothing may dim it or make it conditional. */
    @Test fun `the switch is neither greyed out nor hidden behind a condition`() {
        val lines = themeSection().map { it.second }
        val start = switchAt(lines)
        check(start >= 0) { "no black-background switch in the Theme section of SettingsScreen.kt" }
        val block = lines.subList(start, minOf(start + EXPECTED_SWITCH.size, lines.size))
        assertTrue(
            "the black-background switch must carry no 'enabled =' argument and no 'if (': it " +
                "stays visible and usable in LIGHT theme, where it is inert and says so in its " +
                "subtitle. A switch that appears and disappears with the current theme is a " +
                "coupled toggle. Block was:\n" + block.joinToString("\n"),
            block.none { "enabled =" in it } && block.none { "if (" in it },
        )
    }

    @Test fun `the screen collects the setting from the view model`() {
        assertEquals(
            "AppearanceScreen must read the state through " +
                "'val pureBlack by viewModel.pureBlack.collectAsStateWithLifecycle()' — the whole " +
                "line, like every other setting on the screen.",
            listOf("val pureBlack by viewModel.pureBlack.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val pureBlack by") },
        )
    }

    // -- the write path --------------------------------------------------------------------------

    /**
     * The half of the setting no other test here touches: **storing** what the user chose. Every
     * rule above reads — the default, the collect, the theme argument, the backup — and the pure
     * decision function under test elsewhere is a pure READ of `Preferences`. Nothing executes
     * `setPureBlack`, so writing a constant instead of the argument leaves the whole feature dead
     * (the switch flips, then springs back on the next emission) with the suite entirely green.
     *
     * Both links of the chain are pinned, whole: the view model must hand the argument down, and
     * the repository must store the argument rather than a value of its own.
     */
    @Test fun `flipping the switch stores what the user chose, not a constant`() {
        assertEquals(
            "SettingsRepository.kt must write the argument: 'dataStore.edit { it[KEY_PURE_BLACK] = " +
                "enabled }'. A constant here — or the negation of the argument — makes the setting " +
                "unsettable: it flips on screen and springs back, and nothing else in this suite " +
                "would notice.",
            listOf("dataStore.edit { it[KEY_PURE_BLACK] = enabled }"),
            codeLines(SETTINGS_REPOSITORY).filter { "it[KEY_PURE_BLACK]" in it },
        )
        assertEquals(
            "SettingsViewModel.kt must pass the argument through: " +
                "'viewModelScope.launch { settings.setPureBlack(enabled) }'. Same defect one step " +
                "earlier, and just as invisible from here.",
            listOf("viewModelScope.launch { settings.setPureBlack(enabled) }"),
            codeLines(SETTINGS_VIEW_MODEL).filter { "settings.setPureBlack(" in it },
        )
    }

    /**
     * The FOURTH copy of the default, and the one the previous commit message forgot when it said
     * there were three: the composable's own parameter. It is unreachable today — the single call
     * site always passes a value, and that call is pinned above — so this rule guards a drift rather
     * than a live defect. It is a literal on purpose: [app.sterna.ui.theme.SternaTheme] takes its
     * other defaults as literals too, and importing a settings constant into the theme signature to
     * save one line would be the coupling, not the cure.
     */
    @Test fun `the theme's own default is off too`() {
        assertEquals(
            "Theme.kt must take the flag twice and only twice: without a default in the helper, " +
                "which is always called with one, and defaulting to false in the composable. " +
                "Nothing reaches that default while the activity passes a value, but flipped it " +
                "would turn the black on for any future caller that does not — a preview, a second " +
                "activity, a test harness.",
            listOf("pureBlack: Boolean,", "pureBlack: Boolean = false,"),
            codeLines(THEME).filter { it.startsWith("pureBlack:") },
        )
    }

    // -- the backup ------------------------------------------------------------------------------

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            "snapshotBackup must carry 'pureBlack = pureBlack.first(),' — without it the switch " +
                "is absent from every export, and a restore on a new device silently puts the " +
                "ordinary dark theme back for someone who had chosen black.",
            listOf("pureBlack = pureBlack.first(),"),
            repository.filter { it.startsWith("pureBlack = pureBlack") },
        )
        assertEquals(
            "restoreBackup must apply it: 'backup.pureBlack?.let { setPureBlack(it) }'. Exported " +
                "and never read back is the same defect one step later. The '?.let' is the part " +
                "that matters: an absent field means 'leave as is', not 'off'.",
            listOf("backup.pureBlack?.let { setPureBlack(it) }"),
            repository.filter { it.startsWith("backup.pureBlack") },
        )
    }

    // -- reading the sources ---------------------------------------------------------------------

    /** Where the pinned switch starts inside a Theme-section body, by its title string. */
    private fun switchAt(section: List<String>): Int =
        section.indexOfFirst { it == EXPECTED_SWITCH[1] }.let { if (it <= 0) -1 else it - 1 }

    /**
     * The body of `SettingsSection(stringResource(R.string.settings_theme_section)) {` — each line
     * paired with its brace depth RELATIVE to the section, which is the whole point here: depth 0
     * is a direct child of the section, depth 1 is inside the Android-12 guard. Closed by counting
     * braces, not by waiting for the next section (see UnreadTintWiringTest for why).
     */
    private fun themeSection(): List<Pair<Int, String>> {
        val lines = codeLines(SETTINGS_SCREEN)
        val opener = "SettingsSection(stringResource(R.string.settings_theme_section)) {"
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) {
            "no '$opener' in SettingsScreen.kt — the section moved or was reshaped, and this lint " +
                "must be taught the new shape rather than left green over a section it never read"
        }
        var depth = 1
        val body = mutableListOf<Pair<Int, String>>()
        for (line in lines.drop(at + 1)) {
            // A line that opens with '}' belongs to the block it closes, not to the one outside it.
            val lineDepth = if (line.startsWith("}")) depth - 1 else depth
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return body
            body += (lineDepth - 1) to line
        }
        error("the 'Theme' section is never closed in SettingsScreen.kt")
    }

    /** Locates [expected]'s first line in [file] and compares the block that follows it, whole. */
    private fun assertBlock(file: File, expected: List<String>, what: String) {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { it == expected.first() }
        check(at >= 0) { "no '${expected.first()}' in ${file.name} — $what is gone" }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order. Nothing in this repo executes " +
                "these lines. Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        const val EXPECTED_ACTIVITY_COLLECT =
            "val pureBlack by settings.pureBlack.collectAsState(initial = PURE_BLACK_DEFAULT)"
        const val EXPECTED_THEME_CALL =
            "SternaTheme(themeMode = themeMode, dynamicColor = dynamicColor, pureBlack = pureBlack) {"

        val EXPECTED_SWITCH = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_pure_black_title),",
            "subtitle = stringResource(R.string.settings_pure_black_subtitle),",
            "checked = pureBlack,",
            "onCheckedChange = viewModel::setPureBlack,",
            ")",
        )

        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_REPOSITORY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
        val MAIN_ACTIVITY: File by lazy { File(root, "app/src/main/kotlin/app/sterna/MainActivity.kt") }
        val SETTINGS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        }
        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }
        val THEME: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/theme/Theme.kt") }
    }
}
