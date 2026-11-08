package app.gridlink.core.data.settings

/**
 * Which rung of the Gridlink screens' ladder is pinned, or [AUTO] to follow the day.
 *
 * Deliberately a data-layer enum of its own rather than the UI's `GridlinkMode?`, for the same
 * reason [ThemeMode] has SYSTEM instead of a nullable: "follow the clock" is a choice the user
 * made and a value worth storing, not the absence of one. The UI maps AUTO to a null override at
 * the boundary. Persisted BY NAME, so an unknown value from a newer build reads back as AUTO
 * rather than throwing.
 *
 * 🔴 Mode only. Until 2026-11-08 this enum also held the colour presets (Graphite, Deep Blue,
 * Ember, Forest, Sand), each of which pinned one fixed brightness. Colour is now its own choice,
 * [GridlinkPreset], so any colour works in any mode. The old names still arrive from storage and
 * [gridlinkAppearanceOf] is the one place that reads them.
 */
enum class GridlinkPalette { AUTO, DAY, NIGHT, OLED }

/**
 * The colour family the Gridlink screens wear, independent of [GridlinkPalette]'s brightness.
 *
 * [CLASSIC] is the original look: Day's blue gradient, Night's aurora, OLED's amber on black. Every
 * other entry has its own Day, Night and OLED version, so picking a colour never fixes the mode.
 * Persisted by name, falling back to [CLASSIC] for a name this build does not know.
 */
enum class GridlinkPreset { CLASSIC, GRAPHITE, DEEP_BLUE, EMBER, FOREST, SAND }

/** The two halves of the Gridlink look as stored: a mode choice and a colour choice. */
data class GridlinkAppearance(
    val palette: GridlinkPalette = GridlinkPalette.AUTO,
    val preset: GridlinkPreset = GridlinkPreset.CLASSIC,
)

/**
 * Reads the two stored strings back into an appearance, migrating the single-choice layout.
 *
 * Before the split, one key held either a rung or a preset, and every preset implied its own
 * brightness: Sand was a light look and the other four were dark. So a stored `EMBER` with no colour
 * key means "Ember, always Night", and `SAND` means "Sand, always Day". Mapping them that way is
 * what keeps an existing user's screen exactly as it was on the day they update.
 *
 * A colour key, once written, wins over anything implied by the old value. Unknown names (from a
 * newer build, or a corrupted file) fall back to Auto and Classic rather than throwing.
 */
fun gridlinkAppearanceOf(storedPalette: String?, storedPreset: String?): GridlinkAppearance {
    val legacy = LEGACY_PRESET_PALETTES[storedPalette]
    val palette = GridlinkPalette.entries.firstOrNull { it.name == storedPalette }
        ?: legacy?.palette
        ?: GridlinkPalette.AUTO
    val preset = GridlinkPreset.entries.firstOrNull { it.name == storedPreset }
        ?: legacy?.preset
        ?: GridlinkPreset.CLASSIC
    return GridlinkAppearance(palette, preset)
}

/** What each pre-split preset name meant: its colour, pinned to the one brightness it came in. */
private val LEGACY_PRESET_PALETTES = mapOf(
    "GRAPHITE" to GridlinkAppearance(GridlinkPalette.NIGHT, GridlinkPreset.GRAPHITE),
    "DEEP_BLUE" to GridlinkAppearance(GridlinkPalette.NIGHT, GridlinkPreset.DEEP_BLUE),
    "EMBER" to GridlinkAppearance(GridlinkPalette.NIGHT, GridlinkPreset.EMBER),
    "FOREST" to GridlinkAppearance(GridlinkPalette.NIGHT, GridlinkPreset.FOREST),
    "SAND" to GridlinkAppearance(GridlinkPalette.DAY, GridlinkPreset.SAND),
)
