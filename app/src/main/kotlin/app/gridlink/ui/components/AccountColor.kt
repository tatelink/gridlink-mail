package app.gridlink.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Black or white, whichever has the higher WCAG contrast ratio on [background].
 *
 * Monogram initials and the swatch check-mark sit on arbitrary, user-chosen
 * accent colours (and on hashed avatar colours). A hard-coded white fails AA on
 * the light end of the palette — e.g. white on yellow #FDD835 is only 1.40:1 and
 * white on orange #FB8C00 only 2.37:1, both unreadable. Picking the higher-
 * contrast foreground keeps every avatar legible (black on yellow = 15.05:1).
 * Luminance per WCAG 2.x relative-luminance.
 */
fun onAccentColor(background: Color): Color {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    val l = 0.2126f * channel(background.red) +
        0.7152f * channel(background.green) +
        0.0722f * channel(background.blue)
    val whiteContrast = 1.05f / (l + 0.05f)
    val blackContrast = (l + 0.05f) / 0.05f
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

/** Curated accent-colour palette a user can assign to an account. */
object AccountPalette {
    val colors: List<Color> = listOf(
        Color(0xFFE53935), // red
        Color(0xFFFB8C00), // orange
        Color(0xFFFDD835), // yellow
        Color(0xFF43A047), // green
        Color(0xFF00897B), // teal
        Color(0xFF1E88E5), // blue
        Color(0xFF5E35B1), // deep purple
        Color(0xFFD81B60), // pink
    )
}

/** An account's stored accent colour (ARGB) as a Compose [Color], or null for auto. */
fun accountColorOf(argb: Int?): Color? = argb?.let { Color(it) }

/**
 * Every account's accent colour, chosen ones honoured and the rest handed a distinct one.
 *
 * ## 🔴 Why this is assignment rather than a hash of the address
 * The colour is the only thing naming the account on a merged inbox row (the text label came off
 * when the bar took the job), so two accounts sharing one is not a cosmetic collision, it is the row
 * answering "whose is this" wrongly. A hash cannot promise otherwise: eight colours and any two
 * addresses collide about one time in eight, silently, and the user's only recourse would be to
 * discover the setting and fix it by hand. Assignment can promise it, so it does.
 *
 * Chosen colours are taken first and are never moved: an override is the user's answer, and a later
 * account arriving must not repaint an account they already coloured. What CAN move is an
 * auto-assigned colour, when a later override claims it. That is the right way round — an automatic
 * choice yielding to a deliberate one — and the alternative is two accounts the same colour.
 *
 * [accounts] is (id, chosen colour or null) in stored order, and the order is what makes the result
 * stable: the same accounts in the same order always produce the same map, so the colours do not
 * shuffle between the mail list and settings, or across a restart.
 *
 * Past [AccountPalette.colors].size accounts there is nothing honest left to hand out, so the
 * palette repeats from the top. Eight accounts is already well past what this is designed for, and a
 * repeat is better than a ninth colour invented off the end of a curated set.
 */
fun resolveAccountColors(accounts: List<Pair<String, Int?>>): Map<String, Int> {
    val palette = AccountPalette.colors.map { it.toArgb() }
    val taken = accounts.mapNotNull { it.second }.toMutableSet()
    val resolved = LinkedHashMap<String, Int>(accounts.size)
    var wrap = 0
    accounts.forEach { (id, chosen) ->
        val color = chosen
            ?: palette.firstOrNull { it !in taken }
            // Every colour is spoken for: repeat rather than invent, and step through the palette so
            // a ninth and tenth account are at least not the same colour as each other.
            ?: palette[wrap++ % palette.size]
        taken += color
        resolved[id] = color
    }
    return resolved
}
