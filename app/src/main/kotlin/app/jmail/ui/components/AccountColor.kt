package app.jmail.ui.components

import androidx.compose.ui.graphics.Color

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
