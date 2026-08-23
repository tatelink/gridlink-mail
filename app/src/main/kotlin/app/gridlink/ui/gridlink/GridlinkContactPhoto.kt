package app.gridlink.ui.gridlink

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
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

/**
 * [uri] scaled to fit [GRIDLINK_PHOTO_MAX_EDGE] and re-encoded as JPEG. Blocking: call off main.
 *
 * ## 🔴 Two decoders, and the newer one goes first
 * [BitmapFactory] refuses formats the platform itself can read. The one that matters here is HEIF:
 * a Samsung phone shooting in "high efficiency" writes `.heic`, the gallery hands back a perfectly
 * good `content://` uri for it, and `decodeStream` answers null. That is indistinguishable, from
 * here, from a corrupt file — and the caller's honest response to "could not read it" is to leave
 * the old photo alone, so picking a photo appeared to do nothing at all.
 *
 * [ImageDecoder] (API 28) reads everything the platform supports, HEIF and AVIF included, and
 * resamples during the decode rather than after it. [BitmapFactory] stays as the fallback because
 * this app runs back to API 26, where [ImageDecoder] does not exist.
 */
fun gridlinkEncodeContactPhoto(context: Context, uri: Uri): ContactCardPhoto? {
    val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeScaledWithImageDecoder(context, uri) ?: decodeScaledWithBitmapFactory(context, uri)
    } else {
        decodeScaledWithBitmapFactory(context, uri)
    } ?: return null

    val bytes = ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, GRIDLINK_PHOTO_JPEG_QUALITY, out)
        out.toByteArray()
    }
    scaled.recycle()
    return ContactCardPhoto("image/jpeg", java.util.Base64.getEncoder().encodeToString(bytes))
}

/** [uri] decoded already at target size, or null when the platform cannot read it either. */
@RequiresApi(Build.VERSION_CODES.P)
private fun decodeScaledWithImageDecoder(context: Context, uri: Uri): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        // 🔴 Software, and not the default. ImageDecoder hands back a HARDWARE bitmap when it can,
        // and a hardware bitmap has no pixels in this process: `compress` on one fails. The whole
        // point of decoding here is to read the pixels back out.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longEdge = maxOf(info.size.width, info.size.height)
        if (longEdge > GRIDLINK_PHOTO_MAX_EDGE) {
            val ratio = GRIDLINK_PHOTO_MAX_EDGE.toFloat() / longEdge
            decoder.setTargetSize(
                (info.size.width * ratio).toInt().coerceAtLeast(1),
                (info.size.height * ratio).toInt().coerceAtLeast(1),
            )
        }
    }
} catch (_: Exception) {
    null
}

/** The API 26/27 path, and the fallback for anything [ImageDecoder] would not take. */
private fun decodeScaledWithBitmapFactory(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    // Bounds first, so a 100-megapixel original never materialises in memory: inSampleSize gets
    // the decode within 2x of the target, and the exact scale below finishes the job.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeStream(resolver, uri, bounds)
    // Covers a uri that would not open as well as one that opened and held nothing readable: a
    // bounds pass hands back no bitmap either way, so the measurements are the only evidence.
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= GRIDLINK_PHOTO_MAX_EDGE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = decodeStream(resolver, uri, options) ?: return null

    val longEdge = maxOf(decoded.width, decoded.height)
    if (longEdge <= GRIDLINK_PHOTO_MAX_EDGE) return decoded
    val ratio = GRIDLINK_PHOTO_MAX_EDGE.toFloat() / longEdge
    return Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * ratio).toInt().coerceAtLeast(1),
        (decoded.height * ratio).toInt().coerceAtLeast(1),
        true,
    ).also { if (it !== decoded) decoded.recycle() }
}

/**
 * One pass of [uri] through [BitmapFactory], reading [options] and writing its results back into it.
 *
 * Null for a uri that will not open (the provider is gone, the grant has lapsed) as much as for
 * bytes that will not parse, because the caller treats those the same: it has no photo either way.
 */
private fun decodeStream(resolver: ContentResolver, uri: Uri, options: BitmapFactory.Options): Bitmap? = try {
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
} catch (_: Exception) {
    null
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
