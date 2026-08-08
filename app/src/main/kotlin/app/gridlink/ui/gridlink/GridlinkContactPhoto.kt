package app.gridlink.ui.gridlink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.gridlink.core.jmap.model.ContactCardPhoto
import java.io.ByteArrayOutputStream

/**
 * The two directions a contact photo travels: a picked image becoming a [ContactCardPhoto], and a
 * [ContactCardPhoto] becoming pixels on a card.
 *
 * ## Why encoding re-compresses instead of passing the file through
 * A camera-roll picture is 3-12 MB, and this photo is stored INSIDE the card — a JSContact data
 * URI on one path, a PHOTO line on the other — so every sync of the address book re-downloads
 * every byte of it forever. 512px at JPEG 85 is ~40 KB, indistinguishable at card size, and
 * safely under [app.gridlink.core.data.contacts.VCard]'s 2 MiB photo ceiling, which a pass-through
 * of an original would blow past routinely (and the parser treats an oversized photo as absent,
 * so the "saved" photo would vanish on the next sync — silently).
 *
 * ## Why decode failure is null and not a throw
 * Both directions face bytes this app did not produce: a picked file can be a corrupt download,
 * a synced card can carry a photo some other client wrote badly. In both cases the card is still
 * a card; only the picture is refused.
 */

/** [uri] scaled to fit [GRIDLINK_PHOTO_MAX_EDGE] and re-encoded as JPEG. Blocking: call off main. */
fun gridlinkEncodeContactPhoto(context: Context, uri: Uri): ContactCardPhoto? {
    val resolver = context.contentResolver
    // Bounds first, so a 100-megapixel original never materialises in memory: inSampleSize gets
    // the decode within 2x of the target, and the exact scale below finishes the job.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    } catch (_: Exception) {
        return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= GRIDLINK_PHOTO_MAX_EDGE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = try {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    } catch (_: Exception) {
        return null
    }

    val longEdge = maxOf(decoded.width, decoded.height)
    val scaled = if (longEdge > GRIDLINK_PHOTO_MAX_EDGE) {
        val ratio = GRIDLINK_PHOTO_MAX_EDGE.toFloat() / longEdge
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
    } else {
        decoded
    }

    val bytes = ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, GRIDLINK_PHOTO_JPEG_QUALITY, out)
        out.toByteArray()
    }
    scaled.recycle()
    return ContactCardPhoto("image/jpeg", java.util.Base64.getEncoder().encodeToString(bytes))
}

/**
 * [photo] as something drawable, cached against the base64 so a recomposition never re-decodes.
 * Null in, null out; undecodable in, null out — the caller renders nothing either way.
 */
@Composable
fun rememberGridlinkContactPhoto(photo: ContactCardPhoto?): ImageBitmap? =
    remember(photo?.base64) {
        photo?.let {
            try {
                val bytes = java.util.Base64.getDecoder().decode(it.base64)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

/** Longest edge after encode. Card-sized, not gallery-sized: see the file comment. */
const val GRIDLINK_PHOTO_MAX_EDGE = 512

private const val GRIDLINK_PHOTO_JPEG_QUALITY = 85
