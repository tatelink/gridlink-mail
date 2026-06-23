package app.jmail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A coloured monogram avatar — initial + a colour derived from the address.
 * Privacy-first: never loads a remote photo, no network leak (DESIGN.md).
 */
@Composable
fun Monogram(seed: String, label: String, modifier: Modifier = Modifier, color: Color? = null) {
    val background = color ?: remember(seed) { monogramColor(seed) }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialOf(label, seed),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Desaturated, evenly-distributed colour from a stable hash of the seed. */
private fun monogramColor(seed: String): Color {
    var hash = 0
    for (c in seed) hash = c.code + (hash shl 5) - hash
    val hue = ((hash % 360) + 360) % 360
    return Color.hsl(hue.toFloat(), saturation = 0.42f, lightness = 0.52f)
}

private fun initialOf(label: String, fallback: String): String {
    val source = label.ifBlank { fallback }
    return source.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}
