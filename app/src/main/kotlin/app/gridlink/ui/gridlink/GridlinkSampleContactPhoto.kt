package app.gridlink.ui.gridlink

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.gridlink.BuildConfig

/**
 * A stand-in photograph for a card that has none, in debug builds only.
 *
 * ## Why this exists, and why it is not a feature
 * Tate asked for the hero to be judged with pictures in it: *"You can use stock photos for the
 * first 10 contacts if you want to test, don't overwrite, just insert stock photo if no other
 * image."* Almost nothing in a real address book carries a photo, so without this the hero could
 * only ever be reviewed in its no-photo form, and the things that most need looking at — the crop,
 * whether the scrim keeps white text readable over a bright picture, whether a name survives being
 * laid over a busy one — would never be on screen.
 *
 * ## 🔴 Nothing is written anywhere
 * "Don't overwrite" is the load-bearing half of that instruction, and it is honoured structurally
 * rather than by care: this returns pixels to one composable and touches no repository, no card and
 * no server. [GridlinkSampleContacts.GridlinkContact.photo] is still null for these contacts, so an
 * edit-and-save of a card wearing a borrowed picture saves a card with no photo, exactly as it would
 * have before. The stand-in cannot reach the wire because it never becomes card data.
 *
 * ## 🔴 Debug only, twice over
 * The [BuildConfig.DEBUG] gate is the obvious half. The other half is that the images live in
 * `app/src/debug/assets/`, so a release APK does not contain them and could not show one if the
 * gate were ever removed by accident. A shipped mail client that invents a face for someone would
 * be a straightforward lie about who wrote to you.
 *
 * The image is chosen by a stable hash of the contact id, so the same person keeps the same picture
 * across every open of the card — a stand-in that reshuffled on each visit would make the hero
 * impossible to judge and the app look broken.
 */
@Composable
fun rememberGridlinkSampleContactPhoto(id: String): ImageBitmap? {
    if (!BuildConfig.DEBUG) return null
    val context = LocalContext.current
    return remember(id) {
        var hash = 0
        for (ch in id) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
        val name = "%s/%02d.jpg".format(SAMPLE_PHOTO_DIR, hash % SAMPLE_PHOTO_COUNT + 1)
        try {
            context.assets.open(name).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        } catch (_: java.io.IOException) {
            // The debug source set is present but the assets are not, which is what a checkout with
            // the sample photos pruned looks like. The hero's own no-photo form is the answer.
            null
        }
    }
}

private const val SAMPLE_PHOTO_DIR = "gridlink-sample-photos"
private const val SAMPLE_PHOTO_COUNT = 10
